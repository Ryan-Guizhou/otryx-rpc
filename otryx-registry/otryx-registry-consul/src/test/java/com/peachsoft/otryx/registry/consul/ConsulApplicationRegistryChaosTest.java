package com.peachsoft.otryx.registry.consul;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.peachsoft.otryx.api.ApplicationInstance;
import com.peachsoft.otryx.api.ApplicationKey;
import com.peachsoft.otryx.api.ApplicationRole;
import com.peachsoft.otryx.registry.ApplicationDirectorySnapshot;
import com.peachsoft.otryx.registry.ApplicationRegistryListener;
import com.peachsoft.otryx.registry.RegistryOptions;
import com.peachsoft.otryx.registry.RegistrySubscription;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * 真实 Consul Agent 暂停/恢复故障测试，验证订阅健康状态与成员 TTL 恢复。
 *
 * <p>只在 CI 提供 consul.chaos.container 时对预置隔离容器执行 docker pause/unpause。
 * 不对外部非测试环境自动执行故障注入。
 *
 * @Author OpenAI
 * @Version 1.0.0-SNAPSHOT
 * @CreateTime 2026/10/16 14:19
 */
@EnabledIfSystemProperty(named = "consul.chaos.container", matches = ".+")
@Timeout(value = 120, unit = TimeUnit.SECONDS)
class ConsulApplicationRegistryChaosTest {

    private static final int HTTP_TIMEOUT_MILLIS = 1000;
    private static final int POLL_INTERVAL_MILLIS = 200;
    private static final int PAUSE_SECONDS = 7;
    private static final int INITIAL_WAIT_SECONDS = 15;
    private static final int RECOVERY_WAIT_SECONDS = 30;

    @Test
    void pauseAndResumeRealAgentKeepsCorrectMemberAndHealthLifecycle() throws Exception {
        String container = System.getProperty("consul.chaos.container");
        String endpoint = System.getProperty("consul.test.endpoint", "http://127.0.0.1:8500");
        ApplicationKey application = new ApplicationKey(
                "chaos-" + UUID.randomUUID().toString().substring(0, 8), "consumer");
        ApplicationInstance consumer = new ApplicationInstance("only-consumer",
                application, ApplicationRole.CONSUMER, null, 0, null, Map.of());
        RegistryOptions options = new RegistryOptions(List.of(endpoint), application.namespace(), Map.of(
                "consulTtlSeconds", "10",
                "consulHeartbeatSeconds", "1",
                "consulPollIntervalMillis", Integer.toString(POLL_INTERVAL_MILLIS),
                "consulRequestTimeoutMillis", Integer.toString(HTTP_TIMEOUT_MILLIS)));
        AtomicBoolean hasFailed = new AtomicBoolean();
        CountDownLatch initialReady = new CountDownLatch(1);
        CountDownLatch failed = new CountDownLatch(1);
        CountDownLatch recovered = new CountDownLatch(1);
        try (ConsulApplicationRegistry registry = (ConsulApplicationRegistry)
                new ConsulApplicationRegistryFactory().create(options);
             RegistrySubscription ignored = registry.subscribe(application, new ApplicationRegistryListener() {
                 @Override
                 public void onSnapshot(ApplicationDirectorySnapshot snapshot) {
                     if (snapshot.instances().contains(consumer)) {
                         initialReady.countDown();
                         if (hasFailed.get()) {
                             recovered.countDown();
                         }
                     }
                 }

                 @Override
                 public void onSynchronizationSucceeded() {
                     if (hasFailed.get()) {
                         recovered.countDown();
                     }
                 }

                 @Override
                 public void onSynchronizationFailed(Throwable error) {
                     hasFailed.set(true);
                     failed.countDown();
                 }
             })) {
            registry.register(consumer).toCompletableFuture().get(10, TimeUnit.SECONDS);
            assertTrue(initialReady.await(INITIAL_WAIT_SECONDS, TimeUnit.SECONDS));
            docker("pause", container);
            try {
                assertTrue(failed.await(PAUSE_SECONDS, TimeUnit.SECONDS),
                        "Consul subscriptions must emit explicit failures while Agent is paused");
            } finally {
                docker("unpause", container);
            }
            assertTrue(recovered.await(RECOVERY_WAIT_SECONDS, TimeUnit.SECONDS),
                    "Consul subscription should become healthy after Agent recovery");
            assertTrue(registry.lookup(application).toCompletableFuture().get(10, TimeUnit.SECONDS)
                    .instances().contains(consumer));
        }
    }

    private static void docker(String operation, String container) throws Exception {
        Process process = new ProcessBuilder("docker", operation, container)
                .redirectErrorStream(true).start();
        if (!process.waitFor(10, TimeUnit.SECONDS) || process.exitValue() != 0) {
            process.destroyForcibly();
            throw new IllegalStateException("Consul test container control failed: " + operation);
        }
    }
}
