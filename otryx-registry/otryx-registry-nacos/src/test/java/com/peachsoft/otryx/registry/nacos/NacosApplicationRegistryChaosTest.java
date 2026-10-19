package com.peachsoft.otryx.registry.nacos;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.peachsoft.otryx.api.ApplicationInstance;
import com.peachsoft.otryx.api.ApplicationKey;
import com.peachsoft.otryx.api.ApplicationRole;
import com.peachsoft.otryx.api.RpcEndpoint;
import com.peachsoft.otryx.api.ServiceKey;
import com.peachsoft.otryx.registry.ApplicationDirectorySnapshot;
import com.peachsoft.otryx.registry.RegistryOptions;
import com.peachsoft.otryx.registry.RegistrySubscription;
import com.peachsoft.otryx.registry.ServiceMappingKey;
import com.peachsoft.otryx.registry.ServiceMappingOwner;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Nacos V2 独立 Provider/Consumer 的真实容器网络暂停恢复测试。
 *
 * <p>在 nacos-chaos Profile 启动；暂停期间第三个客户端的成员注册不能虚报成功。
 * 恢复后直接查询与已有订阅必须都发现新成员，并保留原先持久化的 Mapping。
 *
 * @Author OpenAI
 * @Version 1.0.0-SNAPSHOT
 * @CreateTime 2026/10/16 13:42
 */
@Tag("chaos")
@Timeout(value = 150, unit = TimeUnit.SECONDS)
class NacosApplicationRegistryChaosTest {

    private static final long PAUSE_OBSERVATION_MILLIS = 1500L;

    @Test
    void providerAndConsumerMembershipRecoverWithoutMisrouting() throws Exception {
        String endpoint = System.getenv("NACOS_TEST_ENDPOINT");
        String container = System.getenv("NACOS_CHAOS_CONTAINER");
        assumeTrue(endpoint != null && !endpoint.isBlank(), "NACOS_TEST_ENDPOINT is required");
        assumeTrue(container != null && !container.isBlank(), "NACOS_CHAOS_CONTAINER is required");
        String group = "OTRYX_V2_CHAOS_" + UUID.randomUUID().toString().replace("-", "");
        NacosV2MetadataBootstrap.initialize(RegistryOptions.trustedDevelopment(
                List.of(endpoint), "public", Map.of("nacosGroup", group)));
        ApplicationKey application = new ApplicationKey("public", "chaos");
        ApplicationInstance provider = new ApplicationInstance("provider-a", application, ApplicationRole.PROVIDER,
                new RpcEndpoint("127.0.0.1", 19811), 100, "sha256:" + "b".repeat(64), Map.of());
        ApplicationInstance consumer = new ApplicationInstance("consumer-a", application, ApplicationRole.CONSUMER,
                null, 0, null, Map.of());
        ApplicationInstance second = new ApplicationInstance("provider-b", application, ApplicationRole.PROVIDER,
                new RpcEndpoint("127.0.0.1", 19812), 100, provider.manifestRevision(), Map.of());
        ServiceKey service = new ServiceKey("demo.Chaos", "1.0.0", "default");
        ServiceMappingKey key = new ServiceMappingKey(application.namespace(), service);
        AtomicReference<ApplicationDirectorySnapshot> latest = new AtomicReference<>();
        boolean paused = false;
        try (NacosApplicationRegistry server = registry(endpoint, group);
             NacosApplicationRegistry client = registry(endpoint, group);
             NacosApplicationRegistry joining = registry(endpoint, group);
             RegistrySubscription ignored = client.subscribe(application, latest::set)) {
            ServiceMappingOwner owner = server.serviceMappingRepository()
                    .claim(application, provider.instanceId()).toCompletableFuture()
                    .get(15, TimeUnit.SECONDS);
            server.serviceMappingRepository().publish(owner, Set.of(service))
                    .toCompletableFuture().get(15, TimeUnit.SECONDS);
            server.register(provider).toCompletableFuture().get(15, TimeUnit.SECONDS);
            client.register(consumer).toCompletableFuture().get(15, TimeUnit.SECONDS);
            assertTrue(await(Duration.ofSeconds(30),
                    () -> client.lookup(application).toCompletableFuture().join()
                            .instances().containsAll(Set.of(provider, consumer))
                            && latest.get() != null
                            && latest.get().instances().containsAll(Set.of(provider, consumer))));

            docker("pause", container);
            paused = true;
            CompletableFuture<Void> duringPause = joining.register(second).toCompletableFuture();
            Thread.sleep(PAUSE_OBSERVATION_MILLIS);
            assertFalse(duringPause.isDone() && !duringPause.isCompletedExceptionally(),
                    "Application registration must not succeed while Nacos is paused");
            docker("unpause", container);
            paused = false;

            try {
                duringPause.get(15, TimeUnit.SECONDS);
            } catch (ExecutionException | TimeoutException transientFailure) {
                assertTrue(await(Duration.ofSeconds(30), () -> registerEventually(joining, second)));
            }
            assertTrue(await(Duration.ofSeconds(40),
                    () -> client.lookup(application).toCompletableFuture().join()
                            .instances().containsAll(Set.of(provider, consumer, second))
                            && latest.get() != null
                            && latest.get().instances().containsAll(Set.of(provider, consumer, second))));
            assertTrue(await(Duration.ofSeconds(40),
                    () -> server.serviceMappingRepository().lookup(key).toCompletableFuture()
                            .join().applications().contains(application)));
            assertEquals(0, client.lookup(application).toCompletableFuture().join().instances().stream()
                    .filter(member -> member.role() == ApplicationRole.CONSUMER)
                    .filter(ApplicationInstance::routableProvider).count());
        } finally {
            if (paused) {
                docker("unpause", container);
            }
        }
    }

    private static NacosApplicationRegistry registry(String endpoint, String group) {
        return (NacosApplicationRegistry) new NacosApplicationRegistryFactory().create(
                RegistryOptions.trustedDevelopment(List.of(endpoint), "public", Map.of(
                        "nacosGroup", group, "nacosCluster", "DEFAULT",
                        "nacosMemberHost", "127.0.0.1")));
    }

    private static boolean registerEventually(NacosApplicationRegistry registry, ApplicationInstance member)
            throws InterruptedException {
        try {
            registry.register(member).toCompletableFuture().get(5, TimeUnit.SECONDS);
            return true;
        } catch (ExecutionException | TimeoutException transientFailure) {
            return false;
        }
    }

    private static void docker(String action, String container) throws IOException, InterruptedException {
        Process process = new ProcessBuilder("docker", action, container)
                .redirectErrorStream(true).start();
        assertEquals(0, process.waitFor(), "Docker chaos operation failed");
    }

    private static boolean await(Duration duration, CheckedBoolean check) throws Exception {
        long deadline = System.nanoTime() + duration.toNanos();
        while (System.nanoTime() < deadline) {
            try {
                if (check.accept()) {
                    return true;
                }
            } catch (RuntimeException ignored) {
                // Transient upstream downtime; next bounded sample can recover.
            }
            Thread.sleep(200L);
        }
        return check.accept();
    }

    @FunctionalInterface
    private interface CheckedBoolean {
        boolean accept() throws Exception;
    }
}
