package com.peachsoft.otryx.registry.eureka;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.peachsoft.otryx.api.RpcEndpoint;
import com.peachsoft.otryx.api.ApplicationInstance;
import com.peachsoft.otryx.api.ApplicationKey;
import com.peachsoft.otryx.api.ApplicationRole;
import com.peachsoft.otryx.registry.ApplicationDirectorySnapshot;
import com.peachsoft.otryx.registry.ApplicationRegistryListener;
import com.peachsoft.otryx.registry.RegistrySubscription;
import com.peachsoft.otryx.registry.ServiceMappingOwnerSnapshot;
import com.peachsoft.otryx.registry.http.HttpRegistryClient;
import com.peachsoft.otryx.registry.memory.MemoryApplicationRegistry;
import com.peachsoft.otryx.registry.RegistryCapability;
import com.peachsoft.otryx.registry.RegistryOptions;
import com.peachsoft.otryx.spi.ExtensionLoader;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * 使用本地 Eureka REST 模拟器验证应用成员、续约和健康发现。
 *
 * <p>SDK/HTTP 故障回归不替代真实 Eureka 与外置 CAS 后端集成证据。
 *
 * @Author Ryan
 * @Version 1.0.0
 * @CreateTime 2026/10/10 18:00
 */
class EurekaApplicationRegistryTest {

    @Test
    void registersRenewsDiscoversAndUnregisters() throws Exception {
        try (FakeEureka backend = new FakeEureka();
                EurekaApplicationRegistry registry = registry(backend)) {
            assertTrue(registry.capabilities().supports(RegistryCapability.LEASE));
            assertFalse(registry.capabilities().supports(RegistryCapability.REVISION));
            AtomicReference<ApplicationDirectorySnapshot> latest = new AtomicReference<>();
            try (RegistrySubscription ignored = registry.subscribe(application(), latest::set)) {
                await(() -> latest.get() != null && latest.get().instances().isEmpty());
                registry.register(instance()).toCompletableFuture().get(5, TimeUnit.SECONDS);
                await(() -> latest.get().instances().equals(List.of(instance())));
                await(() -> backend.renewals.get() > 0);
                registry.unregister(instance()).toCompletableFuture().get(5, TimeUnit.SECONDS);
                await(() -> latest.get().instances().isEmpty());
            }
            assertEquals(1, backend.registers.get());
            assertTrue(backend.authSeen.get().startsWith("Basic "));
        }
    }

    @Test
    void selfHealsUnknownLeaseAndFiltersNonUpInstance() throws Exception {
        try (FakeEureka backend = new FakeEureka();
                EurekaApplicationRegistry registry = registry(backend)) {
            registry.registrar().orElseThrow().register(instance())
                    .toCompletableFuture().get(5, TimeUnit.SECONDS);
            backend.remote.set(null);
            await(() -> backend.registers.get() > 1);
            assertEquals(1, registry.lookup(application())
                    .toCompletableFuture().get(5, TimeUnit.SECONDS)
                    .instances().size());
            backend.down.set(true);
            assertTrue(registry.lookup(application())
                    .toCompletableFuture().get(5, TimeUnit.SECONDS)
                    .instances().isEmpty());
            assertFalse(options(backend).toString().contains("test-password"));
        }
    }

    @Test
    void loadsEurekaAdapterBySpi() {
        assertTrue(ExtensionLoader.getLoader(
                com.peachsoft.otryx.registry.ApplicationRegistryFactory.class)
                .getExtension("eureka") instanceof EurekaApplicationRegistryFactory);
    }

    @Test
    void failedInitialPollingCannotFabricateHealthyEmptyDirectory() throws Exception {
        try (FakeEureka backend = new FakeEureka();
             EurekaApplicationRegistry registry = registry(backend)) {
            backend.failLookup.set(true);
            Notifications listener = new Notifications();
            try (RegistrySubscription ignored = registry.subscribe(application(), listener)) {
                await(() -> listener.failures.get() > 0);
                assertEquals(0, listener.snapshots.get());
                assertEquals(0, listener.successes.get());
                backend.failLookup.set(false);
                await(() -> listener.successes.get() > 0 && listener.snapshots.get() == 1);
                assertTrue(listener.latest.get().instances().isEmpty());
            }
        }
    }

    @Test
    void failedPollingPreservesLastGoodThenQuietRecoveryConfirmsHealth() throws Exception {
        try (FakeEureka backend = new FakeEureka();
             EurekaApplicationRegistry registry = registry(backend)) {
            registry.register(instance()).toCompletableFuture().get(5, TimeUnit.SECONDS);
            Notifications listener = new Notifications();
            try (RegistrySubscription ignored = registry.subscribe(application(), listener)) {
                await(() -> listener.snapshots.get() == 1 && listener.successes.get() > 0);
                ApplicationDirectorySnapshot original = listener.latest.get();
                backend.failLookup.set(true);
                await(() -> listener.failures.get() > 0);
                assertEquals(original, listener.latest.get());
                assertEquals(1, listener.snapshots.get());
                int healthyBefore = listener.successes.get();
                backend.failLookup.set(false);
                await(() -> listener.successes.get() > healthyBefore);
                assertEquals(original, listener.latest.get());
                assertEquals(1, listener.snapshots.get());
                registry.unregister(instance()).toCompletableFuture().get(5, TimeUnit.SECONDS);
                await(() -> listener.latest.get().instances().isEmpty());
                assertTrue(listener.latest.get().revision() > original.revision());
            }
        }
    }

    @Test
    void closedApplicationRegistryRejectsNewReadsRegistrationsAndSubscriptions() throws Exception {
        try (FakeEureka backend = new FakeEureka()) {
            EurekaApplicationRegistry registry = registry(backend);
            registry.close();
            registry.close();
            assertThrows(IllegalStateException.class, () -> registry.lookup(application()));
            assertThrows(IllegalStateException.class, () -> registry.register(instance()));
            assertThrows(IllegalStateException.class, () -> registry.subscribe(application(), ignored -> { }));
        }
    }

    private static RegistryOptions options(FakeEureka backend) {
        return new RegistryOptions(
                java.util.List.of(backend.endpoint()), "environment-a",
                Map.of("eurekaUsername", "test-user",
                        "eurekaPassword", "test-password",
                        "eurekaLeaseSeconds", "9",
                        "eurekaHeartbeatSeconds", "1",
                        "eurekaPollIntervalMillis", "200"));
    }

    private static ApplicationKey application() {
        return new ApplicationKey("environment-a", "greeting");
    }

    private static ApplicationInstance instance() {
        return new ApplicationInstance("eureka-provider", application(), ApplicationRole.PROVIDER,
                new RpcEndpoint("127.0.0.1", 19090), 155, "sha256:" + "a".repeat(64), Map.of("zone", "test"));
    }

    private static EurekaApplicationRegistry registry(FakeEureka backend) {
        MemoryApplicationRegistry metadata = new MemoryApplicationRegistry();
        String credential = "Basic " + Base64.getEncoder().encodeToString(
                "test-user:test-password".getBytes(StandardCharsets.UTF_8));
        return new EurekaApplicationRegistry(
                new HttpRegistryClient(List.of(backend.endpoint()), "Authorization", credential, Duration.ofSeconds(2)),
                new EurekaV2Executor(), new EurekaV2Identity(application().namespace()), metadata,
                metadata.manifestRepository(), metadata.serviceMappingRepository(),
                key -> CompletableFuture.completedFuture(new ServiceMappingOwnerSnapshot(key, Map.of(), 0)),
                "127.0.0.1", Duration.ofSeconds(9), Duration.ofSeconds(1), Duration.ofMillis(100));
    }

    private static void await(java.util.function.BooleanSupplier condition)
            throws InterruptedException {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < until) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(100L);
        }
        assertTrue(condition.getAsBoolean());
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

    private static final class FakeEureka implements AutoCloseable {
        private final ObjectMapper mapper = new ObjectMapper();
        private final HttpServer server;
        private final AtomicReference<JsonNode> remote = new AtomicReference<>();
        private final AtomicReference<String> authSeen = new AtomicReference<>();
        private final AtomicInteger registers = new AtomicInteger();
        private final AtomicBoolean down = new AtomicBoolean();
        private final AtomicBoolean failLookup = new AtomicBoolean();
        private final AtomicInteger renewals = new AtomicInteger();

        private FakeEureka() throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/eureka/apps/", this::onRequest);
            server.start();
        }

        private String endpoint() {
            return "http://127.0.0.1:" + server.getAddress().getPort() + "/eureka";
        }

        private void onRequest(HttpExchange ex) throws IOException {
            authSeen.set(ex.getRequestHeaders().getFirst("Authorization"));
            String credentials = "Basic " + Base64.getEncoder().encodeToString(
                    "test-user:test-password".getBytes(StandardCharsets.UTF_8));
            if (!credentials.equals(authSeen.get())) {
                respond(ex, 401, "{}");
                return;
            }
            String path = ex.getRequestURI().getPath();
            String prefix = "/eureka/apps/";
            String servicePath = path.substring(prefix.length());
            String[] parts = servicePath.split("/");
            if ("POST".equals(ex.getRequestMethod()) && parts.length == 1) {
                JsonNode info = mapper.readTree(ex.getRequestBody()).path("instance");
                assertEquals(parts[0], info.path("app").asText());
                assertEquals("UP", info.path("status").asText());
                assertEquals("127.0.0.1", info.path("hostName").asText());
                assertEquals("127.0.0.1", info.path("ipAddr").asText());
                assertEquals(19090, info.path("port").path("$").asInt());
                assertEquals("true", info.path("port").path("@enabled").asText());
                assertEquals("false", info.path("securePort").path("@enabled").asText());
                assertEquals("MyOwn", info.path("dataCenterInfo").path("name").asText());
                assertEquals("com.netflix.appinfo.InstanceInfo$DefaultDataCenterInfo",
                        info.path("dataCenterInfo").path("@class").asText());
                assertEquals(9, info.path("leaseInfo").path("durationInSecs").asInt());
                assertEquals(1, info.path("leaseInfo").path("renewalIntervalInSecs").asInt());
                assertEquals("eureka-provider",
                        info.path("metadata").path("otryxV2Instance").asText());
                assertEquals("155", info.path("metadata").path("otryxV2Weight").asText());
                remote.set(info);
                down.set(false);
                registers.incrementAndGet();
                respond(ex, 204, "");
                return;
            }
            if ("GET".equals(ex.getRequestMethod()) && parts.length == 1) {
                if (failLookup.get()) {
                    respond(ex, 503, "{}");
                    return;
                }
                JsonNode node = remote.get();
                if (node == null) {
                    respond(ex, 404, "{}");
                    return;
                }
                if (down.get()) {
                    ObjectNode changed = node.deepCopy();
                    changed.put("status", "DOWN");
                    node = changed;
                }
                respond(ex, 200, mapper.valueToTree(Map.of(
                        "application", Map.of("instance", java.util.List.of(node)))).toString());
                return;
            }
            if ("PUT".equals(ex.getRequestMethod()) && parts.length == 2) {
                renewals.incrementAndGet();
                respond(ex, remote.get() == null ? 404 : 200, "{}");
                return;
            }
            if ("DELETE".equals(ex.getRequestMethod()) && parts.length == 2) {
                remote.set(null);
                respond(ex, 200, "{}");
                return;
            }
            respond(ex, 404, "{}");
        }

        private static void respond(HttpExchange ex, int status, String value)
                throws IOException {
            byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
            try (var out = ex.getResponseBody()) {
                if (bytes.length > 0) {
                    out.write(bytes);
                }
            }
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }
}
