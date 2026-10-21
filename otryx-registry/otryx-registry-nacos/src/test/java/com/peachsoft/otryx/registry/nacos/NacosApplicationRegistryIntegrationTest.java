package com.peachsoft.otryx.registry.nacos;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.alibaba.nacos.api.naming.NamingService;
import com.peachsoft.otryx.api.ApplicationInstance;
import com.peachsoft.otryx.api.ApplicationKey;
import com.peachsoft.otryx.api.ApplicationManifest;
import com.peachsoft.otryx.api.ApplicationRole;
import com.peachsoft.otryx.api.ExportedService;
import com.peachsoft.otryx.api.RpcEndpoint;
import com.peachsoft.otryx.api.RpcIds;
import com.peachsoft.otryx.api.ServiceKey;
import com.peachsoft.otryx.registry.ApplicationDirectorySnapshot;
import com.peachsoft.otryx.registry.ApplicationRegistryContractTestKit;
import com.peachsoft.otryx.registry.ApplicationRegistryFactory;
import com.peachsoft.otryx.registry.RegistryOptions;
import com.peachsoft.otryx.registry.RegistrySubscription;
import com.peachsoft.otryx.registry.ServiceMappingKey;
import com.peachsoft.otryx.registry.ServiceMappingOwner;
import com.peachsoft.otryx.spi.ExtensionLoader;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * 对真实 Nacos 3.2.4 Naming 与 Config 验证 V2 应用级成员及 CAS 元数据一致性。
 *
 * <p>没有 NACOS_TEST_ENDPOINT 时跳过；CI 在可访问的独立 Nacos 容器执行，
 * 不以 MockNamingService 替代 SDK 生命周期及服务端事务语义。
 *
 * @Author OpenAI
 * @Version 1.0.0-SNAPSHOT
 * @CreateTime 2026/10/16 13:42
 */
@Timeout(value = 150, unit = TimeUnit.SECONDS)
class NacosApplicationRegistryIntegrationTest {

    @Test
    void factoryIsDiscoverableAndRejectsCredentialInEndpoint() {
        ApplicationRegistryFactory factory = ExtensionLoader.getLoader(
                ApplicationRegistryFactory.class).getExtension("nacos");
        assertEquals(NacosApplicationRegistryFactory.class, factory.getClass());
        assertThrows(IllegalArgumentException.class, () -> factory.create(RegistryOptions.trustedDevelopment(
                List.of("admin:secret@127.0.0.1:8848"), "public", Map.of())));
    }

    @Test
    void consumerOnlyMembersOnSameIpStayDistinctAndNonRoutable() throws Exception {
        String group = unique("OTRYX_V2_ROLES");
        ApplicationKey app = app("orders");
        ApplicationInstance first = consumer(app, "consumer-a");
        ApplicationInstance second = consumer(app, "consumer-b");
        try (NacosApplicationRegistry left = registry(group);
             NacosApplicationRegistry right = registry(group)) {
            left.register(first).toCompletableFuture().get(15, TimeUnit.SECONDS);
            right.register(second).toCompletableFuture().get(15, TimeUnit.SECONDS);
            assertTrue(await(Duration.ofSeconds(25), () -> {
                ApplicationDirectorySnapshot snapshot = left.lookup(app).toCompletableFuture()
                        .get(5, TimeUnit.SECONDS);
                return snapshot.instances().containsAll(List.of(first, second));
            }));
            assertEquals(Set.of(first, second), Set.copyOf(left.lookup(app)
                    .toCompletableFuture().join().instances()));
            assertFalse(first.routableProvider());
            assertFalse(second.routableProvider());
        }
    }

    @Test
    @Timeout(value = 300, unit = TimeUnit.SECONDS)
    void thirtyRealContractsAndTenIndependentClientsSatisfySharedApplicationContract() throws Exception {
        String group = unique("OTRYX_APPLICATION_CONTRACT");
        bootstrap(group);
        String endpoint = System.getenv("NACOS_TEST_ENDPOINT");
        NamingService raw = com.alibaba.nacos.api.NacosFactory.createNamingService(
                NacosApplicationRegistryFactory.clientProperties(
                        RegistryOptions.trustedDevelopment(List.of(endpoint), "public", Map.of()),
                        List.of(endpoint), "public"));
        try {
            ApplicationRegistryContractTestKit.verify("nacos", "public", 10, () -> registry(group),
                    application -> raw.getAllInstances(NacosV2Names.serviceName(application), group, false).size(),
                    Duration.ofSeconds(30));
        } finally {
            raw.shutDown();
        }
    }

    @Test
    void twoIndependentProvidersKeepMappingAfterOneLeavesAndStaleOwnerCannotPublish() throws Exception {
        String group = unique("OTRYX_V2_FENCE");
        bootstrap(group);
        ApplicationKey app = app("billing");
        ServiceKey key = new ServiceKey("demo.Billing", "1.0.0", "default");
        ServiceMappingKey mapping = new ServiceMappingKey(app.namespace(), key);
        ApplicationInstance a = provider(app, "node-a", 19610);
        ApplicationInstance b = provider(app, "node-b", 19611);

        try (NacosApplicationRegistry left = registry(group);
             NacosApplicationRegistry right = registry(group)) {
            ServiceMappingOwner first = left.serviceMappingRepository()
                    .claim(app, a.instanceId()).toCompletableFuture().join();
            ServiceMappingOwner second = right.serviceMappingRepository()
                    .claim(app, b.instanceId()).toCompletableFuture().join();
            CompletableFuture.allOf(
                    left.serviceMappingRepository().publish(first, Set.of(key)).toCompletableFuture(),
                    right.serviceMappingRepository().publish(second, Set.of(key)).toCompletableFuture()
            ).get(30, TimeUnit.SECONDS);
            left.register(a).toCompletableFuture().join();
            right.register(b).toCompletableFuture().join();
            assertEquals(Set.of(app), right.serviceMappingRepository().lookup(mapping)
                    .toCompletableFuture().join().applications());

            left.serviceMappingRepository().revoke(first).toCompletableFuture().join();
            left.unregister(a).toCompletableFuture().join();
            assertEquals(Set.of(app), right.serviceMappingRepository().lookup(mapping)
                    .toCompletableFuture().join().applications());

            ServiceMappingOwner next = left.serviceMappingRepository()
                    .claim(app, a.instanceId()).toCompletableFuture().join();
            assertTrue(next.generation() > first.generation());
            assertThrows(CompletionException.class, () ->
                    left.serviceMappingRepository().publish(first, Set.of(key)).toCompletableFuture().join());
            left.serviceMappingRepository().revoke(first).toCompletableFuture().join();
            assertEquals(Set.of(app), right.serviceMappingRepository().lookup(mapping)
                    .toCompletableFuture().join().applications());

            right.serviceMappingRepository().revoke(second).toCompletableFuture().join();
            assertTrue(right.serviceMappingRepository().lookup(mapping)
                    .toCompletableFuture().join().applications().isEmpty());
        }
    }

    @Test
    void independentClientsMustPreserveConcurrentFirstClaimsInTheSameShard() throws Exception {
        String group = unique("OTRYX_V2_CONCURRENT");
        bootstrap(group);
        ApplicationKey app = app("analytics");
        String firstId = "node-a";
        int shard = NacosV2Names.shard(app, firstId);
        String secondId = null;
        for (int index = 0; index < 1000; index++) {
            String candidate = "node-b-" + index;
            if (NacosV2Names.shard(app, candidate) == shard) {
                secondId = candidate;
                break;
            }
        }
        assertTrue(secondId != null);
        ServiceKey firstKey = new ServiceKey("demo.AnalyticsFirst", "1.0.0", "default");
        ServiceKey secondKey = new ServiceKey("demo.AnalyticsSecond", "1.0.0", "default");
        try (NacosApplicationRegistry first = registry(group);
             NacosApplicationRegistry second = registry(group)) {
            String finalId = secondId;
            CompletableFuture<ServiceMappingOwner> claimA = first.serviceMappingRepository()
                    .claim(app, firstId).toCompletableFuture();
            CompletableFuture<ServiceMappingOwner> claimB = second.serviceMappingRepository()
                    .claim(app, finalId).toCompletableFuture();
            ServiceMappingOwner ownerA = claimA.get(25, TimeUnit.SECONDS);
            ServiceMappingOwner ownerB = claimB.get(25, TimeUnit.SECONDS);
            CompletableFuture.allOf(
                    first.serviceMappingRepository().publish(ownerA, Set.of(firstKey)).toCompletableFuture(),
                    second.serviceMappingRepository().publish(ownerB, Set.of(secondKey)).toCompletableFuture()
            ).get(25, TimeUnit.SECONDS);
            assertEquals(Set.of(app), first.serviceMappingRepository().lookup(
                    new ServiceMappingKey(app.namespace(), firstKey))
                    .toCompletableFuture().join().applications());
            assertEquals(Set.of(app), second.serviceMappingRepository().lookup(
                    new ServiceMappingKey(app.namespace(), secondKey))
                    .toCompletableFuture().join().applications());
        }
    }

    @Test
    void immutableManifestCanPublishAndVerifyTwoConcurrentRevisions() {
        String group = unique("OTRYX_V2_MANIFEST");
        ApplicationKey app = app("catalog");
        ServiceKey one = new ServiceKey("demo.Catalog", "1.0.0", "default");
        ServiceKey two = new ServiceKey("demo.Price", "1.0.0", "default");
        ApplicationManifest v1 = ApplicationManifest.create(app, List.of(export(one)));
        ApplicationManifest v2 = ApplicationManifest.create(app, List.of(export(one), export(two)));

        try (NacosApplicationRegistry first = registry(group);
             NacosApplicationRegistry second = registry(group)) {
            CompletableFuture.allOf(
                    first.manifestRepository().publish(v1).toCompletableFuture(),
                    second.manifestRepository().publish(v1).toCompletableFuture(),
                    second.manifestRepository().publish(v2).toCompletableFuture()).join();
            assertEquals(v1, second.manifestRepository()
                    .lookupVerified(app, v1.revision()).toCompletableFuture().join().orElseThrow());
            assertEquals(v2, first.manifestRepository()
                    .lookupVerified(app, v2.revision()).toCompletableFuture().join().orElseThrow());
            assertThrows(IllegalArgumentException.class,
                    () -> first.manifestRepository().lookup(
                            new ApplicationKey("foreign", "catalog"), v1.revision()));
        }
    }

    @Test
    void namingSubscriptionSeesRemoteProviderAndExplicitEmptyDirectory() throws Exception {
        String group = unique("OTRYX_V2_WATCH");
        ApplicationKey app = app("reports");
        ApplicationInstance member = provider(app, "provider-a", 19711);
        CountDownLatch seen = new CountDownLatch(1);
        CountDownLatch emptyAgain = new CountDownLatch(1);
        AtomicReference<List<ApplicationInstance>> latest = new AtomicReference<>();
        try (NacosApplicationRegistry writer = registry(group);
             NacosApplicationRegistry reader = registry(group);
             RegistrySubscription ignored = reader.subscribe(app, snapshot -> {
                 latest.set(snapshot.instances());
                 if (snapshot.instances().contains(member)) {
                     seen.countDown();
                 }
                 if (seen.getCount() == 0 && snapshot.instances().isEmpty()) {
                     emptyAgain.countDown();
                 }
             })) {
            writer.register(member).toCompletableFuture().join();
            assertTrue(seen.await(30, TimeUnit.SECONDS));
            writer.unregister(member).toCompletableFuture().join();
            assertTrue(emptyAgain.await(30, TimeUnit.SECONDS));
            assertTrue(latest.get().isEmpty());
        }
    }

    @Test
    void namingGroupsKeepTheSameApplicationIdentityIsolated() throws Exception {
        ApplicationKey application = app("group-isolation");
        ApplicationInstance leftMember = consumer(application, "same-logical-id");
        try (NacosApplicationRegistry left = registry(unique("GROUP_LEFT"));
             NacosApplicationRegistry right = registry(unique("GROUP_RIGHT"))) {
            left.register(leftMember).toCompletableFuture().get(15, TimeUnit.SECONDS);
            assertTrue(await(Duration.ofSeconds(25), () -> left.lookup(application)
                    .toCompletableFuture().join().instances().equals(List.of(leftMember))));
            assertTrue(right.lookup(application).toCompletableFuture().join().instances().isEmpty());
            right.register(leftMember).toCompletableFuture().get(15, TimeUnit.SECONDS);
            left.unregister(leftMember).toCompletableFuture().get(15, TimeUnit.SECONDS);
            assertTrue(await(Duration.ofSeconds(25), () -> right.lookup(application)
                    .toCompletableFuture().join().instances().equals(List.of(leftMember))));
            assertTrue(await(Duration.ofSeconds(25), () -> left.lookup(application)
                    .toCompletableFuture().join().instances().isEmpty()));
        }
    }

    @Test
    void logicalClustersFilterMembersWithoutConflatingApplicationIdentity() throws Exception {
        String group = unique("CLUSTER_ISOLATION");
        ApplicationKey application = app("cluster-isolation");
        ApplicationInstance leftMember = provider(application, "left", 19712);
        ApplicationInstance rightMember = consumer(application, "right");
        try (NacosApplicationRegistry left = registry(group, "LEFT");
             NacosApplicationRegistry right = registry(group, "RIGHT")) {
            left.register(leftMember).toCompletableFuture().get(15, TimeUnit.SECONDS);
            right.register(rightMember).toCompletableFuture().get(15, TimeUnit.SECONDS);
            assertTrue(await(Duration.ofSeconds(25), () -> left.lookup(application)
                    .toCompletableFuture().join().instances().equals(List.of(leftMember))));
            assertTrue(await(Duration.ofSeconds(25), () -> right.lookup(application)
                    .toCompletableFuture().join().instances().equals(List.of(rightMember))));
        }
    }

    private static void bootstrap(String group) {
        String endpoint = System.getenv("NACOS_TEST_ENDPOINT");
        assumeTrue(endpoint != null && !endpoint.isBlank(), "NACOS_TEST_ENDPOINT is required");
        NacosV2MetadataBootstrap.initialize(RegistryOptions.trustedDevelopment(
                List.of(endpoint), "public", Map.of("nacosGroup", group)));
    }

    private static NacosApplicationRegistry registry(String group) {
        return registry(group, "DEFAULT");
    }

    private static NacosApplicationRegistry registry(String group, String cluster) {
        String endpoint = System.getenv("NACOS_TEST_ENDPOINT");
        assumeTrue(endpoint != null && !endpoint.isBlank(), "NACOS_TEST_ENDPOINT is required");
        return (NacosApplicationRegistry) new NacosApplicationRegistryFactory().create(
                RegistryOptions.trustedDevelopment(List.of(endpoint), "public", Map.of(
                        "nacosGroup", group, "nacosCluster", cluster,
                        "nacosMemberHost", "127.0.0.1")));
    }

    private static ApplicationInstance provider(ApplicationKey app, String id, int port) {
        return new ApplicationInstance(id, app, ApplicationRole.PROVIDER,
                new RpcEndpoint("127.0.0.1", port), 100, "sha256:" + "a".repeat(64), Map.of());
    }

    private static ApplicationInstance consumer(ApplicationKey app, String id) {
        return new ApplicationInstance(id, app, ApplicationRole.CONSUMER, null, 0, null, Map.of());
    }

    private static ApplicationKey app(String name) {
        return new ApplicationKey("public", name);
    }

    private static ExportedService export(ServiceKey key) {
        return new ExportedService(key, RpcIds.serviceId(key), Set.of(100), "a".repeat(64));
    }

    private static String unique(String prefix) {
        return prefix + '_' + UUID.randomUUID().toString().replace("-", "");
    }

    private static boolean await(Duration duration, CheckedBoolean condition) throws Exception {
        long deadline = System.nanoTime() + duration.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.check()) {
                return true;
            }
            Thread.sleep(150L);
        }
        return condition.check();
    }

    @FunctionalInterface
    private interface CheckedBoolean {
        boolean check() throws Exception;
    }
}
