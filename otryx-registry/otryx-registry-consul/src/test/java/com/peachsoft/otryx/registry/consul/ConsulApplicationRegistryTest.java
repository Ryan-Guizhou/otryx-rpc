package com.peachsoft.otryx.registry.consul;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.peachsoft.otryx.api.RpcEndpoint;
import com.peachsoft.otryx.api.ApplicationInstance;
import com.peachsoft.otryx.api.ApplicationKey;
import com.peachsoft.otryx.api.ApplicationRole;
import com.peachsoft.otryx.registry.ApplicationRegistry;
import com.peachsoft.otryx.registry.ApplicationDirectorySnapshot;
import com.peachsoft.otryx.registry.ApplicationRegistryListener;
import com.peachsoft.otryx.registry.RegistrySubscription;
import com.peachsoft.otryx.registry.RegistryCapability;
import com.peachsoft.otryx.registry.RegistryOptions;
import com.peachsoft.otryx.spi.ExtensionLoader;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.List;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * 使用本地 HTTP Agent 模拟器验证应用注册、Session 归属、续约和健康过滤。
 *
 * <p>模拟器只验证请求及故障处理；真实 TTL/CAS/ACL 由独立集成测试证明。
 *
 * @Author Ryan
 * @Version 1.0.0
 * @CreateTime 2026/10/10 18:00
 */
class ConsulApplicationRegistryTest {

    @Test
    void registersDiscoversUnregistersAndPreservesCredentialIsolation() throws Exception {
        try (FakeAgent agent = new FakeAgent();
                ApplicationRegistry registry = new ConsulApplicationRegistryFactory().create(options(agent))) {
            assertTrue(registry.capabilities().supports(RegistryCapability.LEASE));
            assertTrue(registry.capabilities().supports(RegistryCapability.HEALTH));
            assertFalse(registry.capabilities().supports(RegistryCapability.REVISION));
            AtomicReference<ApplicationDirectorySnapshot> latest = new AtomicReference<>();
            try (RegistrySubscription ignored = registry.subscribe(application(), latest::set)) {
                await(() -> latest.get() != null && latest.get().instances().isEmpty());
                registry.registrar().orElseThrow().register(instance()).toCompletableFuture().get(5, TimeUnit.SECONDS);
                await(() -> latest.get().instances().equals(List.of(instance())));
                registry.registrar().orElseThrow().unregister(instance())
                        .toCompletableFuture().get(5, TimeUnit.SECONDS);
                await(() -> latest.get().instances().isEmpty());
            }
            assertEquals(1, agent.registers.get());
            assertTrue(agent.heartbeats.get() > 0);
            assertEquals("test-acl-token", agent.seenToken.get());
        }
    }

    @Test
    void shouldReregisterAfterAgentForgetsTtlCheck() throws Exception {
        try (FakeAgent agent = new FakeAgent();
                ApplicationRegistry registry = new ConsulApplicationRegistryFactory().create(options(agent))) {
            registry.registrar().orElseThrow().register(instance())
                    .toCompletableFuture().get(5, TimeUnit.SECONDS);
            agent.service.set(null);
            agent.passing.set(false);
            await(() -> agent.registers.get() >= 2
                    && registry.lookup(application()).toCompletableFuture().get(5, TimeUnit.SECONDS)
                            .instances().equals(List.of(instance())));
            assertEquals(1, registry.lookup(application())
                    .toCompletableFuture().get(5, TimeUnit.SECONDS)
                    .instances().size());
        }
    }

    @Test
    void filtersNonPassingInstancesWithoutLeakingAclToken() throws Exception {
        try (FakeAgent agent = new FakeAgent();
                ApplicationRegistry registry = new ConsulApplicationRegistryFactory().create(options(agent))) {
            registry.registrar().orElseThrow().register(instance())
                    .toCompletableFuture().get(5, TimeUnit.SECONDS);
            agent.passing.set(false);
            assertTrue(registry.lookup(application())
                    .toCompletableFuture().get(5, TimeUnit.SECONDS).instances().isEmpty());
            assertFalse(options(agent).toString().contains("test-acl-token"));
        }
    }

    @Test
    void refusesMultipleAgentEndpointsToProtectLocalTtlLeases() {
        RegistryOptions options = new RegistryOptions(
                java.util.List.of("http://127.0.0.1:8500", "http://127.0.0.1:8501"),
                "environment-a", Map.of());
        assertThrows(IllegalArgumentException.class,
                () -> new ConsulApplicationRegistryFactory().create(options));
    }

    @Test
    void loadsFactoryBySpi() {
        assertTrue(ExtensionLoader.getLoader(
                com.peachsoft.otryx.registry.ApplicationRegistryFactory.class)
                .getExtension("consul") instanceof ConsulApplicationRegistryFactory);
    }

    @Test
    void fixedPollingIntervalRejectsBusyLoopsAndUnboundedDelay() {
        for (String interval : List.of("0", "199", "60001")) {
            RegistryOptions options = new RegistryOptions(List.of("http://127.0.0.1:8500"),
                    application().namespace(), Map.of("consulPollIntervalMillis", interval));
            assertThrows(IllegalArgumentException.class,
                    () -> new ConsulApplicationRegistryFactory().create(options));
        }
    }

    @Test
    void failedInitialPollingCannotPublishHealthyEmptyDirectory() throws Exception {
        try (FakeAgent agent = new FakeAgent();
             ApplicationRegistry registry = new ConsulApplicationRegistryFactory().create(options(agent))) {
            agent.failLookup.set(true);
            Notifications notifications = new Notifications();
            try (RegistrySubscription ignored = registry.subscribe(application(), notifications)) {
                await(() -> notifications.failures.get() > 0);
                assertEquals(0, notifications.snapshots.get());
                assertEquals(0, notifications.successes.get());
                agent.failLookup.set(false);
                await(() -> notifications.successes.get() > 0 && notifications.snapshots.get() == 1);
                assertTrue(notifications.latest.get().instances().isEmpty());
            }
        }
    }

    @Test
    void failedPollingKeepsSnapshotAndQuietRecoveryConfirmsHealth() throws Exception {
        try (FakeAgent agent = new FakeAgent();
             ApplicationRegistry registry = new ConsulApplicationRegistryFactory().create(options(agent))) {
            registry.registrar().orElseThrow().register(instance()).toCompletableFuture().get(5, TimeUnit.SECONDS);
            Notifications notifications = new Notifications();
            try (RegistrySubscription ignored = registry.subscribe(application(), notifications)) {
                await(() -> notifications.snapshots.get() == 1 && notifications.successes.get() > 0);
                ApplicationDirectorySnapshot original = notifications.latest.get();
                agent.failLookup.set(true);
                await(() -> notifications.failures.get() > 0);
                assertEquals(original, notifications.latest.get());
                int healthyBefore = notifications.successes.get();
                agent.failLookup.set(false);
                await(() -> notifications.successes.get() > healthyBefore);
                assertEquals(original, notifications.latest.get());
                assertEquals(1, notifications.snapshots.get());
                registry.registrar().orElseThrow().unregister(instance())
                        .toCompletableFuture().get(5, TimeUnit.SECONDS);
                await(() -> notifications.latest.get().instances().isEmpty());
                assertTrue(notifications.latest.get().revision() > original.revision());
            }
        }
    }

    private static RegistryOptions options(FakeAgent agent) {
        return new RegistryOptions(
                java.util.List.of(agent.endpoint()), "environment-a",
                Map.of("consulToken", "test-acl-token",
                        "consulTtlSeconds", "10",
                        "consulHeartbeatSeconds", "1",
                        "consulPollIntervalMillis", "200"));
    }

    private static ApplicationKey application() {
        return new ApplicationKey("environment-a", "greeting");
    }

    private static ApplicationInstance instance() {
        return new ApplicationInstance("consul-provider", application(), ApplicationRole.PROVIDER,
                new RpcEndpoint("127.0.0.1", 19090), 175, "sha256:" + "a".repeat(64), Map.of("zone", "test"));
    }

    private static void await(CheckedCondition condition) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < end) {
            if (condition.matches()) {
                return;
            }
            Thread.sleep(100L);
        }
        assertTrue(condition.matches());
    }

    @FunctionalInterface
    private interface CheckedCondition {
        boolean matches() throws Exception;
    }

    private static final class FakeAgent implements AutoCloseable {
        private final ObjectMapper mapper = new ObjectMapper();
        private final HttpServer server;
        private final AtomicReference<JsonNode> service = new AtomicReference<>();
        private final AtomicReference<String> seenToken = new AtomicReference<>();
        private final AtomicBoolean passing = new AtomicBoolean();
        private final AtomicBoolean failLookup = new AtomicBoolean();
        private final AtomicInteger registers = new AtomicInteger();
        private final AtomicInteger heartbeats = new AtomicInteger();
        private final String session = UUID.randomUUID().toString();
        private final AtomicReference<byte[]> claim = new AtomicReference<>();

        private FakeAgent() throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/v1/agent/service/register", ex -> {
                seenToken.set(ex.getRequestHeaders().getFirst("X-Consul-Token"));
                JsonNode parsed = mapper.readTree(ex.getRequestBody());
                assertEquals(new ConsulV2Identity(application().namespace()).serviceName(application()),
                        parsed.path("Name").asText());
                assertEquals(new ConsulV2Identity(application().namespace()).serviceId(instance()),
                        parsed.path("ID").asText());
                assertEquals("127.0.0.1", parsed.path("Address").asText());
                assertEquals(19090, parsed.path("Port").asInt());
                assertEquals("consul-provider",
                        parsed.path("Meta").path("otryxV2Instance").asText());
                assertEquals("175",
                        parsed.path("Meta").path("otryxV2Weight").asText());
                JsonNode check = parsed.path("Check");
                assertEquals("10s", check.path("TTL").asText());
                assertEquals("1m", check.path("DeregisterCriticalServiceAfter").asText());
                assertEquals("critical", check.path("Status").asText());
                service.set(parsed);
                passing.set(false);
                registers.incrementAndGet();
                respond(ex, 200, "{}");
            });
            server.createContext("/v1/agent/check/pass/", ex -> {
                if (service.get() == null) {
                    respond(ex, 404, "{}");
                } else {
                    passing.set(true);
                    heartbeats.incrementAndGet();
                    respond(ex, 200, "{}");
                }
            });
            server.createContext("/v1/agent/service/deregister/", ex -> {
                service.set(null);
                passing.set(false);
                respond(ex, 200, "{}");
            });
            server.createContext("/v1/health/service/", ex -> {
                assertEquals("passing=true", ex.getRequestURI().getRawQuery());
                if (failLookup.get()) {
                    respond(ex, 503, "{}");
                    return;
                }
                JsonNode current = service.get();
                if (current == null || !passing.get()) {
                    respond(ex, 200, "[]");
                    return;
                }
                JsonNode payload = mapper.valueToTree(java.util.List.of(Map.of(
                        "Service", Map.of(
                                "ID", current.path("ID").asText(),
                                "Service", current.path("Name").asText(),
                                "Address", current.path("Address").asText(),
                                "Port", current.path("Port").asInt(),
                                "Meta", current.path("Meta")),
                        "Node", Map.of("Address", "127.0.0.1"),
                        "Checks", java.util.List.of(Map.of("Status", "passing")))));
                respond(ex, 200, payload.toString());
            });
            server.createContext("/v1/agent/service/", ex -> {
                JsonNode current = service.get();
                respond(ex, current == null ? 404 : 200, current == null ? "{}" : current.toString());
            });
            server.createContext("/v1/session/create", ex -> {
                JsonNode body = mapper.readTree(ex.getRequestBody());
                assertEquals("10s", body.path("TTL").asText());
                assertEquals("delete", body.path("Behavior").asText());
                respond(ex, 200, mapper.writeValueAsString(Map.of("ID", session)));
            });
            server.createContext("/v1/session/renew/", ex ->
                    respond(ex, 200, mapper.writeValueAsString(List.of(Map.of("ID", session)))));
            server.createContext("/v1/session/destroy/", ex -> {
                claim.set(null);
                respond(ex, 200, "true");
            });
            server.createContext("/v1/kv/", ex -> {
                String key = ex.getRequestURI().getPath().substring("/v1/kv/".length());
                assertEquals(new ConsulV2Identity(application().namespace())
                        .memberClaimKey(application(), instance().instanceId()), key);
                if ("PUT".equals(ex.getRequestMethod())) {
                    assertEquals("acquire=" + session, ex.getRequestURI().getRawQuery());
                    claim.set(ex.getRequestBody().readAllBytes());
                    respond(ex, 200, "true");
                } else if (claim.get() == null) {
                    respond(ex, 404, "{}");
                } else {
                    respond(ex, 200, mapper.writeValueAsString(List.of(Map.of(
                            "Key", key, "Value", Base64.getEncoder().encodeToString(claim.get()),
                            "ModifyIndex", 1, "Session", session))));
                }
            });
            server.start();
        }

        private String endpoint() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        private static void respond(HttpExchange exchange, int status, String body)
                throws IOException {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            try (var out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }

    private static final class Notifications implements ApplicationRegistryListener {
        private final AtomicReference<ApplicationDirectorySnapshot> latest = new AtomicReference<>();
        private final AtomicInteger snapshots = new AtomicInteger();
        private final AtomicInteger successes = new AtomicInteger();
        private final AtomicInteger failures = new AtomicInteger();

        @Override
        public void onSnapshot(ApplicationDirectorySnapshot snapshot) {
            latest.set(snapshot);
            snapshots.incrementAndGet();
        }

        @Override
        public void onSynchronizationSucceeded() {
            successes.incrementAndGet();
        }

        @Override
        public void onSynchronizationFailed(Throwable error) {
            failures.incrementAndGet();
        }
    }
}
