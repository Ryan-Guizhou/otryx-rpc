package com.peachsoft.otryx.registry.consul;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.peachsoft.otryx.api.ApplicationInstance;
import com.peachsoft.otryx.api.ApplicationKey;
import com.peachsoft.otryx.api.ApplicationManifest;
import com.peachsoft.otryx.api.ApplicationRole;
import com.peachsoft.otryx.api.ExportedService;
import com.peachsoft.otryx.api.RpcEndpoint;
import com.peachsoft.otryx.api.RpcIds;
import com.peachsoft.otryx.api.ServiceKey;
import com.peachsoft.otryx.registry.ApplicationRegistry;
import com.peachsoft.otryx.registry.RegistryCapability;
import com.peachsoft.otryx.registry.ServiceMappingKey;
import com.peachsoft.otryx.registry.ServiceMappingOwner;
import com.peachsoft.otryx.spi.ExtensionLoader;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * 使用真实 Consul Agent、Health 和 KV API 验证 V2 应用目录及 Fencing。
 *
 * <p>仅在外部启动真实 Consul 并设置 consul.test.endpoint 时执行。
 * 进程内 Fake Agent 测试不得替代这里的注册、TTL、CAS 和 ACL 语义。
 *
 * @Author OpenAI
 * @Version 1.0.0-SNAPSHOT
 * @CreateTime 2026/10/16 15:00
 */
@EnabledIfSystemProperty(named = "consul.test.endpoint", matches = ".+")
@Timeout(value = 120, unit = TimeUnit.SECONDS)
class ConsulApplicationRegistryIntegrationTest {

    private static final Duration AWAIT_BUDGET = Duration.ofSeconds(15);
    private static final int RPC_BASE_PORT = 19100;
    private static final String ENDPOINT = System.getProperty(
            "consul.test.endpoint", "http://127.0.0.1:8500");

    @Test
    void providerConsumerAndBothAreRealApplicationMembers() throws Exception {
        String ns = unique();
        ApplicationKey app = new ApplicationKey(ns, "orders");
        ApplicationInstance provider = provider(app, "provider", RPC_BASE_PORT);
        ApplicationInstance consumer = consumer(app, "consumer");
        try (ConsulApplicationRegistry a = registry(ns);
             ConsulApplicationRegistry b = registry(ns)) {
            assertTrue(a.capabilities().supports(RegistryCapability.REGISTRATION));
            assertFalse(a.capabilities().supports(RegistryCapability.REVISION));
            a.register(provider).toCompletableFuture().get(10, TimeUnit.SECONDS);
            b.register(consumer).toCompletableFuture().get(10, TimeUnit.SECONDS);
            assertTrue(await(AWAIT_BUDGET, () -> a.lookup(app)
                    .toCompletableFuture().join().instances().size() == 2));
            assertEquals(Set.of(provider, consumer),
                    Set.copyOf(a.lookup(app).toCompletableFuture().join().instances()));
            assertFalse(a.lookup(app).toCompletableFuture().join().instances().stream()
                    .filter(instance -> instance.role() == ApplicationRole.CONSUMER)
                    .findFirst().orElseThrow().routableProvider());
            ApplicationInstance both = new ApplicationInstance(
                    "consumer", app, ApplicationRole.BOTH,
                    new RpcEndpoint("127.0.0.1", RPC_BASE_PORT + 1),
                    50, "rolling-v2", Map.of());
            b.register(both).toCompletableFuture().join();
            assertTrue(await(AWAIT_BUDGET, () -> a.lookup(app)
                    .toCompletableFuture().join().instances().contains(both)));
            b.unregister(consumer).toCompletableFuture().join();
            assertTrue(a.lookup(app).toCompletableFuture().join().instances().contains(both));
            b.unregister(both).toCompletableFuture().join();
            assertTrue(await(AWAIT_BUDGET, () -> a.lookup(app)
                    .toCompletableFuture().join().instances().equals(List.of(provider))));
        }
    }

    @Test
    void mappingCasFencesOldOwnerAcrossIndependentClients() throws Exception {
        String ns = unique();
        ApplicationKey app = new ApplicationKey(ns, "payments");
        ServiceKey service = service("Payment");
        ApplicationInstance member = provider(app, "node-1", RPC_BASE_PORT + 2);
        try (ConsulApplicationRegistry first = registry(ns);
             ConsulApplicationRegistry second = registry(ns)) {
            ServiceMappingOwner old = first.serviceMappingRepository()
                    .claim(app, "node-1").toCompletableFuture().join();
            ServiceMappingOwner newer = second.serviceMappingRepository()
                    .claim(app, "node-1").toCompletableFuture().join();
            assertTrue(newer.generation() > old.generation());
            assertThrows(CompletionException.class, () -> first.serviceMappingRepository()
                    .publish(old, Set.of(service)).toCompletableFuture().join());
            second.serviceMappingRepository().publish(newer, Set.of(service)).toCompletableFuture().join();
            second.register(member).toCompletableFuture().join();
            assertTrue(await(AWAIT_BUDGET, () -> first.serviceMappingRepository()
                    .lookup(new ServiceMappingKey(ns, service))
                    .toCompletableFuture().join().applications().equals(Set.of(app))));
            first.serviceMappingRepository().revoke(old).toCompletableFuture().join();
            assertEquals(Set.of(app), second.serviceMappingRepository().lookupVerified(
                    new ServiceMappingKey(ns, service)).toCompletableFuture().join().applications());
            second.serviceMappingRepository().revoke(newer).toCompletableFuture().join();
            assertTrue(second.serviceMappingRepository().lookup(new ServiceMappingKey(ns, service))
                    .toCompletableFuture().join().applications().isEmpty());
            ServiceMappingOwner future = first.serviceMappingRepository()
                    .claim(app, "node-1").toCompletableFuture().join();
            assertTrue(future.generation() > newer.generation());
        }
    }

    @Test
    void immutableManifestPersistsAndRejectsTamperedKv() throws Exception {
        String ns = unique();
        ApplicationKey app = new ApplicationKey(ns, "inventory");
        ApplicationManifest first = manifest(app, 1);
        ApplicationManifest second = manifest(app, 2);
        assertNotEquals(first.revision(), second.revision());
        try (ConsulApplicationRegistry registry = registry(ns);
             ConsulV2Client raw = raw()) {
            registry.manifestRepository().publish(first).toCompletableFuture().join();
            registry.manifestRepository().publish(first).toCompletableFuture().join();
            registry.manifestRepository().publish(second).toCompletableFuture().join();
            assertEquals(first, registry.manifestRepository()
                    .lookupVerified(app, first.revision()).toCompletableFuture().join().orElseThrow());
            assertEquals(second, registry.manifestRepository()
                    .lookupVerified(app, second.revision()).toCompletableFuture().join().orElseThrow());
            assertTrue(registry.manifestRepository().lookup(app,
                    "sha256:" + "0".repeat(64)).toCompletableFuture().join().isEmpty());
            String key = new ConsulV2Identity(ns).manifestKey(app, first.revision());
            long version = raw.get(key).orElseThrow().modifyIndex();
            assertTrue(raw.cas(key, version, new byte[] {1, 2, 3}));
            assertThrows(CompletionException.class, () -> registry.manifestRepository()
                    .lookupVerified(app, first.revision()).toCompletableFuture().join());
        }
    }

    @Test
    void thirtyContractsUseOnlyTenApplicationMembers() throws Exception {
        String ns = unique();
        ApplicationKey app = new ApplicationKey(ns, "batch");
        Set<ServiceKey> thirty = new java.util.HashSet<>();
        for (int index = 0; index < 30; index++) {
            thirty.add(service("Batch" + index));
        }
        List<ConsulApplicationRegistry> writers = new ArrayList<>();
        try {
            for (int index = 0; index < 10; index++) {
                ConsulApplicationRegistry writer = registry(ns);
                writers.add(writer);
                String instanceId = "node-" + index;
                ServiceMappingOwner owner = writer.serviceMappingRepository().claim(app, instanceId)
                        .toCompletableFuture().join();
                writer.serviceMappingRepository().publish(owner, thirty).toCompletableFuture().join();
                writer.register(provider(app, instanceId, RPC_BASE_PORT + 10 + index))
                        .toCompletableFuture().join();
            }
            assertTrue(await(AWAIT_BUDGET, () -> writers.getFirst().lookup(app)
                    .toCompletableFuture().join().instances().size() == 10));
            assertEquals(10, writers.getFirst().lookup(app).toCompletableFuture().join().instances().size());
            assertEquals(Set.of(app), writers.getFirst().serviceMappingRepository()
                    .lookup(new ServiceMappingKey(ns, thirty.iterator().next()))
                    .toCompletableFuture().join().applications());
        } finally {
            writers.forEach(ConsulApplicationRegistry::close);
        }
    }

    @Test
    void agentForgetsTtlRegistrationThenHeartbeatsRecover() throws Exception {
        String ns = unique();
        ApplicationKey app = new ApplicationKey(ns, "recovery");
        ApplicationInstance member = consumer(app, "reconnect");
        try (ConsulApplicationRegistry registry = registry(ns);
             ConsulV2Client raw = raw()) {
            registry.register(member).toCompletableFuture().join();
            assertTrue(await(AWAIT_BUDGET, () ->
                    registry.lookup(app).toCompletableFuture().join().instances().contains(member)));
            String id = new ConsulV2Identity(ns).serviceId(member);
            raw.json("PUT", "/v1/agent/service/deregister/" + id, null);
            assertTrue(await(Duration.ofSeconds(20), () -> registry.lookup(app)
                    .toCompletableFuture().join().instances().contains(member)));
        }
    }

    @Test
    void namespaceIsolationAndSpiMustBeStrict() {
        String ns = unique();
        try (ConsulApplicationRegistry registry = registry(ns)) {
            assertThrows(IllegalArgumentException.class,
                    () -> registry.lookup(new ApplicationKey("foreign", "orders")));
            assertThrows(IllegalArgumentException.class,
                    () -> registry.register(consumer(new ApplicationKey("foreign", "orders"), "unauthorized")));
            ApplicationRegistryFactoryCheck.check();
        }
    }

    @Test
    void sessionClaimRejectsAnotherProcessWithSameApplicationInstanceId() throws Exception {
        String ns = unique();
        ApplicationKey app = new ApplicationKey(ns, "ownership");
        ApplicationInstance member = consumer(app, "single-instance");
        try (ConsulApplicationRegistry first = registry(ns);
             ConsulApplicationRegistry second = registry(ns);
             ConsulV2Client client = raw()) {
            first.register(member).toCompletableFuture().join();
            String key = new ConsulV2Identity(ns).memberClaimKey(app, member.instanceId());
            assertTrue(!client.get(key).orElseThrow().session().isBlank());
            assertThrows(CompletionException.class,
                    () -> second.register(member).toCompletableFuture().join());
            assertTrue(await(AWAIT_BUDGET, () -> first.lookup(app)
                    .toCompletableFuture().join().instances().contains(member)));
            first.unregister(member).toCompletableFuture().join();
            assertTrue(await(AWAIT_BUDGET, () -> {
                try {
                    return client.get(key).isEmpty();
                } catch (Exception failure) {
                    throw new IllegalStateException(failure);
                }
            }));
            second.register(member).toCompletableFuture().join();
            assertTrue(await(AWAIT_BUDGET, () -> second.lookup(app)
                    .toCompletableFuture().join().instances().contains(member)));
        }
    }

    @Test
    void concurrentMappingClaimsMustSerializeByRealConsulCas() throws Exception {
        String namespace = unique();
        ApplicationKey app = new ApplicationKey(namespace, "casrace");
        ServiceKey service = service("Concurrent");
        try (ConsulApplicationRegistry first = registry(namespace);
             ConsulApplicationRegistry second = registry(namespace)) {
            var firstClaim = first.serviceMappingRepository().claim(app, "same-provider")
                    .toCompletableFuture();
            var secondClaim = second.serviceMappingRepository().claim(app, "same-provider")
                    .toCompletableFuture();
            ServiceMappingOwner firstOwner = firstClaim.get(10, TimeUnit.SECONDS);
            ServiceMappingOwner secondOwner = secondClaim.get(10, TimeUnit.SECONDS);
            assertNotEquals(firstOwner.generation(), secondOwner.generation());
            ConsulApplicationRegistry winner = firstOwner.generation() > secondOwner.generation()
                    ? first : second;
            ConsulApplicationRegistry loser = winner == first ? second : first;
            ServiceMappingOwner active = winner == first ? firstOwner : secondOwner;
            ServiceMappingOwner stale = loser == first ? firstOwner : secondOwner;
            assertThrows(CompletionException.class, () -> loser.serviceMappingRepository()
                    .publish(stale, Set.of(service)).toCompletableFuture().join());
            winner.serviceMappingRepository().publish(active, Set.of(service))
                    .toCompletableFuture().join();
            winner.register(provider(app, "same-provider", RPC_BASE_PORT + 42))
                    .toCompletableFuture().join();
            assertEquals(Set.of(app), winner.serviceMappingRepository()
                    .lookup(new ServiceMappingKey(namespace, service))
                    .toCompletableFuture().join().applications());
        }
    }

    @Test
    void expiredSessionMustReacquireBeforeRestoringAgentMember() throws Exception {
        String ns = unique();
        ApplicationKey app = new ApplicationKey(ns, "sessions");
        ApplicationInstance member = consumer(app, "restart-session");
        String key = new ConsulV2Identity(ns).memberClaimKey(app, member.instanceId());
        try (ConsulApplicationRegistry registry = registry(ns);
             ConsulV2Client client = raw()) {
            registry.register(member).toCompletableFuture().join();
            String oldSession = client.get(key).orElseThrow().session();
            assertTrue(!oldSession.isBlank());
            client.json("PUT", "/v1/session/destroy/" + oldSession, null);
            assertTrue(await(Duration.ofSeconds(20), () -> {
                try {
                    return client.get(key).filter(entry -> !oldSession.equals(entry.session())).isPresent();
                } catch (Exception failure) {
                    throw new IllegalStateException(failure);
                }
            }));
            assertTrue(await(AWAIT_BUDGET, () -> registry.lookup(app)
                    .toCompletableFuture().join().instances().contains(member)));
        }
    }

    private static ConsulApplicationRegistry registry(String namespace) {
        return (ConsulApplicationRegistry) new ConsulApplicationRegistryFactory().create(
                new com.peachsoft.otryx.registry.RegistryOptions(
                        List.of(ENDPOINT), namespace, Map.of(
                                "consulTtlSeconds", "10",
                                "consulHeartbeatSeconds", "1",
                                "consulPollIntervalMillis", "200",
                                "consulRequestTimeoutMillis", "3000")));
    }

    private static ConsulV2Client raw() {
        return new ConsulV2Client(ENDPOINT, "", "", Duration.ofSeconds(5));
    }

    private static String unique() {
        return "it-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private static ServiceKey service(String suffix) {
        return new ServiceKey("demo." + suffix, "1.0.0", "default");
    }

    private static ApplicationManifest manifest(ApplicationKey app, int count) {
        List<ExportedService> exports = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            ServiceKey key = service("Inventory" + index);
            exports.add(new ExportedService(key, RpcIds.serviceId(key),
                    Set.of(1), "a".repeat(64)));
        }
        return ApplicationManifest.create(app, exports);
    }

    private static ApplicationInstance provider(ApplicationKey app, String id, int port) {
        return new ApplicationInstance(id, app, ApplicationRole.PROVIDER,
                new RpcEndpoint("127.0.0.1", port), 100, "test-revision", Map.of());
    }

    private static ApplicationInstance consumer(ApplicationKey app, String id) {
        return new ApplicationInstance(id, app, ApplicationRole.CONSUMER,
                null, 0, null, Map.of());
    }

    private static boolean await(Duration timeout, BooleanSupplier supplier) throws InterruptedException {
        long end = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < end) {
            if (supplier.getAsBoolean()) {
                return true;
            }
            Thread.sleep(200L);
        }
        return supplier.getAsBoolean();
    }

    private static final class ApplicationRegistryFactoryCheck {
        private ApplicationRegistryFactoryCheck() {
        }

        private static void check() {
            ApplicationRegistry registry = ExtensionLoader.getLoader(
                    com.peachsoft.otryx.registry.ApplicationRegistryFactory.class)
                    .getExtension("consul").create(new com.peachsoft.otryx.registry.RegistryOptions(
                            List.of(ENDPOINT), "spi-test", Map.of()));
            registry.close();
        }
    }
}
