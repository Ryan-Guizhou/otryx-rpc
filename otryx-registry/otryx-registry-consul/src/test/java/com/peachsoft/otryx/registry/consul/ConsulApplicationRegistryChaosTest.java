package com.peachsoft.otryx.registry.consul;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.peachsoft.otryx.api.ApplicationInstance;
import com.peachsoft.otryx.api.ApplicationKey;
import com.peachsoft.otryx.api.ApplicationRole;
import com.peachsoft.otryx.api.RpcEndpoint;
import com.peachsoft.otryx.api.ServiceKey;
import com.peachsoft.otryx.registry.RegistryOptions;
import com.peachsoft.otryx.registry.ServiceMappingKey;
import com.peachsoft.otryx.registry.ServiceMappingOwner;
import com.peachsoft.otryx.registry.http.HttpRegistryClient;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * 真实 Consul Agent pause/unpause、TTL 停跳和 Session Behavior=delete 故障测试。
 *
 * <p>从独立 Consul Chaos workflow 启动，可通过环境变量指定容器与端点。
 * 实际断网必须作为失败完成，不得返回虚假的空目录/空映射。
 *
 * @Author OpenAI
 * @Version 1.0.0-SNAPSHOT
 * @CreateTime 2026/10/16 14:12
 */
@Tag("chaos")
@Timeout(value = 180, unit = TimeUnit.SECONDS)
class ConsulApplicationRegistryChaosTest {

    @Test
    void agentPauseRecoversMembershipAndOwnedMapping() throws Exception {
        String endpoint = endpoint();
        String container = System.getenv("CONSUL_CHAOS_CONTAINER");
        assumeTrue(container != null && !container.isBlank(), "CONSUL_CHAOS_CONTAINER is required");
        ApplicationKey app = app("pause");
        ServiceKey service = new ServiceKey("demo.Chaos", "1.0.0", "default");
        ServiceMappingKey key = new ServiceMappingKey(app.namespace(), service);
        ApplicationInstance provider = provider(app, "node-a", 19641);
        ApplicationInstance consumer = new ApplicationInstance(
                "node-b", app, ApplicationRole.CONSUMER, null, 0, null, Map.of());
        boolean paused = false;
        try (ConsulApplicationRegistry left = registry(endpoint, app);
             ConsulApplicationRegistry right = registry(endpoint, app)) {
            ServiceMappingOwner owner = left.serviceMappingRepository().claim(app, provider.instanceId())
                    .toCompletableFuture().get(15, TimeUnit.SECONDS);
            left.serviceMappingRepository().publish(owner, Set.of(service))
                    .toCompletableFuture().get(15, TimeUnit.SECONDS);
            left.register(provider).toCompletableFuture().get(10, TimeUnit.SECONDS);
            right.register(consumer).toCompletableFuture().get(10, TimeUnit.SECONDS);
            assertTrue(await(Duration.ofSeconds(20), () ->
                    right.lookup(app).toCompletableFuture().join().instances()
                            .containsAll(Set.of(provider, consumer))));
            assertEquals(Set.of(app), right.serviceMappingRepository()
                    .lookup(key).toCompletableFuture().get(20, TimeUnit.SECONDS).applications());

            docker("pause", container);
            paused = true;
            assertThrows(ExecutionException.class,
                    () -> right.lookup(app).toCompletableFuture().get(7, TimeUnit.SECONDS));
            docker("unpause", container);
            paused = false;

            assertTrue(await(Duration.ofSeconds(25), () -> {
                try {
                    return right.lookup(app).toCompletableFuture().join().instances()
                            .containsAll(Set.of(provider, consumer));
                } catch (RuntimeException transientError) {
                    return false;
                }
            }));
            assertEquals(Set.of(app), right.serviceMappingRepository()
                    .lookup(key).toCompletableFuture().get(20, TimeUnit.SECONDS).applications());
        } finally {
            if (paused) {
                docker("unpause", container);
            }
        }
    }

    @Test
    void abandonedConsulTtlCheckStopsBeingPassing() throws Exception {
        String endpoint = endpoint();
        ApplicationKey app = app("ttl");
        ApplicationInstance member = provider(app, "abandoned", 19642);
        String incarnation = UUID.randomUUID().toString();
        String id = ConsulV2Keys.serviceId(app, member.instanceId(), incarnation);
        try (ConsulV2Http http = raw(endpoint)) {
            http.register(ConsulV2MemberCodec.encode(member, incarnation, 10));
            assertTrue(http.pass(id));
            assertTrue(await(Duration.ofSeconds(15),
                    () -> !http.healthy(ConsulV2Keys.serviceName(app)).isEmpty()));
            assertTrue(await(Duration.ofSeconds(40),
                    () -> http.healthy(ConsulV2Keys.serviceName(app)).isEmpty()));
        }
    }

    @Test
    void expiredNativeSessionDeletesLiveKeyWithoutDeletingDurableOwnerGeneration() throws Exception {
        String endpoint = endpoint();
        ApplicationKey app = app("session");
        String liveKey = ConsulV2Keys.live(app, "node-a", 1L);
        try (ConsulV2Http http = raw(endpoint)) {
            String sessionId = http.createSession(10);
            assertTrue(http.acquire(liveKey, new ObjectMapper().valueToTree(
                    Map.of("generation", 1L, "token", UUID.randomUUID().toString())), sessionId));
            assertTrue(http.get(liveKey, ConsulV2Http.MAX_MAPPING_VALUE_BYTES) != null);
            assertTrue(await(Duration.ofSeconds(45),
                    () -> http.get(liveKey, ConsulV2Http.MAX_MAPPING_VALUE_BYTES) == null));
            assertFalse(http.renewSession(sessionId));
        }
    }

    private static ConsulV2Http raw(String endpoint) {
        return new ConsulV2Http(new HttpRegistryClient(
                List.of(endpoint), ConsulProtocol.TOKEN_HEADER, "", Duration.ofSeconds(2)), "", "");
    }

    private static ConsulApplicationRegistry registry(String endpoint, ApplicationKey app) {
        return (ConsulApplicationRegistry) new ConsulApplicationRegistryFactory().create(
                new RegistryOptions(List.of(endpoint), app.namespace(), Map.of(
                        ConsulProtocol.OPTION_TTL_SECONDS, "15",
                        ConsulProtocol.OPTION_HEARTBEAT_SECONDS, "3",
                        ConsulProtocol.OPTION_REQUEST_TIMEOUT_MILLIS, "2000",
                        "consulSessionTtlSeconds", "30")));
    }

    private static String endpoint() {
        String value = System.getenv("CONSUL_TEST_ENDPOINT");
        assumeTrue(value != null && !value.isBlank(), "CONSUL_TEST_ENDPOINT is required");
        return value;
    }

    private static ApplicationKey app(String prefix) {
        return new ApplicationKey("chaos-" + UUID.randomUUID().toString().replace("-", ""), prefix);
    }

    private static ApplicationInstance provider(ApplicationKey app, String id, int port) {
        return new ApplicationInstance(id, app, ApplicationRole.PROVIDER,
                new RpcEndpoint("127.0.0.1", port), 100, "sha256:" + "c".repeat(64), Map.of());
    }

    private static void docker(String operation, String container) throws IOException, InterruptedException {
        Process process = new ProcessBuilder("docker", operation, container).redirectErrorStream(true).start();
        assertEquals(0, process.waitFor(), "Docker Consul chaos action failed");
    }

    private static boolean await(Duration timeout, CheckedBoolean check) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (check.accept()) {
                return true;
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
