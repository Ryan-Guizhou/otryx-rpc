package com.peachsoft.otryx.registry.eureka;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.peachsoft.otryx.api.ApplicationInstance;
import com.peachsoft.otryx.api.ApplicationKey;
import com.peachsoft.otryx.api.ApplicationRole;
import com.peachsoft.otryx.api.RpcEndpoint;
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
 * 真 Eureka Server 重启后，验证 UP Member 订阅错误通知、租约恢复和 Consumer 隔离。
 *
 * <p>仅隔离 CI 传入明确测试脚本时允许控制 Server 进程；禁止主动操作生产 Eureka。
 *
 * @Author OpenAI
 * @Version 1.0.0-SNAPSHOT
 * @CreateTime 2026/10/16 18:09
 */
@EnabledIfSystemProperty(named = "eureka.test.control", matches = ".+")
@Timeout(value = 180, unit = TimeUnit.SECONDS)
class EurekaApplicationRegistryChaosTest {

    private static final String CONSUL = "http://127.0.0.1:8500";
    private static final String EUREKA = "http://127.0.0.1:8761/eureka";

    @Test
    void realServerRestartRetainsCorrectApplicationRoutingAndLeaseRecovery() throws Exception {
        String ns = "chaos-" + UUID.randomUUID().toString().substring(0, 8);
        ApplicationKey app = new ApplicationKey(ns, "switch");
        ApplicationInstance provider = new ApplicationInstance("provider", app, ApplicationRole.PROVIDER,
                new RpcEndpoint("127.0.0.1", 19391), 100, "sha256:" + "a".repeat(64), Map.of());
        ApplicationInstance consumer = new ApplicationInstance("consumer", app, ApplicationRole.CONSUMER,
                null, 0, null, Map.of());
        CountDownLatch initial = new CountDownLatch(1);
        CountDownLatch failed = new CountDownLatch(1);
        CountDownLatch recovered = new CountDownLatch(1);
        AtomicBoolean outageObserved = new AtomicBoolean();

        try (EurekaApplicationRegistry server = registry(ns);
             EurekaApplicationRegistry client = registry(ns);
             RegistrySubscription ignored = client.subscribe(app, new ApplicationRegistryListener() {
                 @Override
                 public void onSnapshot(ApplicationDirectorySnapshot snapshot) {
                     if (snapshot.instances().containsAll(List.of(provider, consumer))) {
                         initial.countDown();
                         if (outageObserved.get()) {
                             recovered.countDown();
                         }
                     }
                 }

                 @Override
                 public void onSynchronizationFailed(Throwable error) {
                     outageObserved.set(true);
                     failed.countDown();
                 }
             })) {
            server.register(provider).toCompletableFuture().get(10, TimeUnit.SECONDS);
            server.register(consumer).toCompletableFuture().get(10, TimeUnit.SECONDS);
            assertTrue(initial.await(25, TimeUnit.SECONDS));
            String script = System.getProperty("eureka.test.control");
            control(script, "stop");
            try {
                assertTrue(failed.await(20, TimeUnit.SECONDS),
                        "Eureka subscription must signal unavailable server");
            } finally {
                control(script, "start");
            }
            assertTrue(recovered.await(65, TimeUnit.SECONDS),
                    "Eureka provider and Consumer members must reappear after a real restart");
            assertTrue(client.lookup(app).toCompletableFuture().join().instances().stream()
                    .filter(ApplicationInstance::routableProvider).allMatch(member -> member.equals(provider)));
        }
    }

    private static EurekaApplicationRegistry registry(String namespace) {
        return (EurekaApplicationRegistry) new EurekaApplicationRegistryFactory().create(
                new RegistryOptions(List.of(EUREKA), namespace, Map.of(
                        "eurekaMetadataBackend", "consul",
                        "eurekaMetadataEndpoints", CONSUL,
                        "eurekaMemberHost", "127.0.0.1",
                        "eurekaLeaseSeconds", "9",
                        "eurekaHeartbeatSeconds", "1",
                        "eurekaPollIntervalMillis", "200",
                        "eurekaRequestTimeoutMillis", "1000",
                        "eurekaMetadata.consulTtlSeconds", "10",
                        "eurekaMetadata.consulHeartbeatSeconds", "1")));
    }

    private static void control(String script, String action) throws Exception {
        Process operation = new ProcessBuilder("bash", script, action)
                .redirectErrorStream(true).start();
        if (!operation.waitFor(80, TimeUnit.SECONDS) || operation.exitValue() != 0) {
            operation.destroyForcibly();
            throw new IllegalStateException("Isolated Eureka fixture control failed: " + action);
        }
    }
}
