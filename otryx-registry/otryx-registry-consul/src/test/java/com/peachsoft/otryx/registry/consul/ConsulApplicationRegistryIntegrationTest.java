package com.peachsoft.otryx.registry.consul;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.peachsoft.otryx.api.ApplicationInstance;
import com.peachsoft.otryx.api.ApplicationKey;
import com.peachsoft.otryx.api.ApplicationManifest;
import com.peachsoft.otryx.api.ApplicationRole;
import com.peachsoft.otryx.api.ExportedService;
import com.peachsoft.otryx.api.RpcEndpoint;
import com.peachsoft.otryx.api.RpcIds;
import com.peachsoft.otryx.api.ServiceKey;
import com.peachsoft.otryx.registry.RegistryOptions;
import com.peachsoft.otryx.registry.RegistrySubscription;
import com.peachsoft.otryx.registry.ServiceMappingKey;
import com.peachsoft.otryx.registry.ServiceMappingOwner;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * 对真实 Consul Agent/KV/Session/TTL 运行应用级成员和 Mapping 全生命周期验收。
 *
 * <p>没有 CONSUL_TEST_ENDPOINT 时跳过，CI 单独启动固定 Consul 容器；
 * 不允许以进程内 HTTP 模拟器替代实际 Session 与 CAS 竞态证明。
 *
 * @Author OpenAI
 * @Version 1.0.0-SNAPSHOT
 * @CreateTime 2026/10/16 14:12
 */
@Timeout(value = 180, unit = TimeUnit.SECONDS)
class ConsulApplicationRegistryIntegrationTest {

    @Test
    void consumerOnlyAndProviderRolesAreVisibleWithoutFakeRpcEndpoint() throws Exception {
        ApplicationKey app = app("roles");
        ApplicationInstance a = consumer(app, "consumer-a");
        ApplicationInstance b = consumer(app, "consumer-b");
        ApplicationInstance provider = provider(app, "provider-a", 19631);

        try (ConsulApplicationRegistry left = registry(app);
             ConsulApplicationRegistry right = registry(app);
             ConsulApplicationRegistry third = registry(app)) {
            left.register(a).toCompletableFuture().get(10, TimeUnit.SECONDS);
            right.register(b).toCompletableFuture().get(10, TimeUnit.SECONDS);
            third.register(provider).toCompletableFuture().get(10, TimeUnit.SECONDS);
            assertTrue(await(Duration.ofSeconds(25), () ->
                    left.lookup(app).toCompletableFuture().join().instances()
                            .containsAll(Set.of(a, b, provider))));
            assertEquals(3, left.lookup(app).toCompletableFuture().join().instances().size());
            assertFalse(a.routableProvider());
            assertFalse(b.routableProvider());

            ApplicationInstance both = new ApplicationInstance(
                    provider.instanceId(), app, ApplicationRole.BOTH,
                    provider.rpcEndpoint(), provider.weight(), provider.manifestRevision(), Map.of());
            third.register(both).toCompletableFuture().get(10, TimeUnit.SECONDS);
            assertTrue(await(Duration.ofSeconds(25), () ->
                    left.lookup(app).toCompletableFuture().join().instances().contains(both)));
            third.unregister(provider).toCompletableFuture().join();
            assertTrue(left.lookup(app).toCompletableFuture().join().instances().contains(both));
            third.unregister(both).toCompletableFuture().join();
            assertTrue(await(Duration.ofSeconds(25), () ->
                    left.lookup(app).toCompletableFuture().join().instances().size() == 2));
        }
    }

    @Test
    void thirtyInterfacesProduceOnlyOneNamingMemberAndOneOwnedMapping() throws Exception {
        ApplicationKey app = app("density");
        ApplicationInstance provider = provider(app, "provider-a", 19632);
        Set<ServiceKey> exported = new HashSet<>();
        for (int index = 0; index < 30; index++) {
            exported.add(new ServiceKey("demo.Billing" + index, "1.0.0", "default"));
        }
        ServiceKey sample = exported.iterator().next();
        try (ConsulApplicationRegistry registry = registry(app)) {
            ServiceMappingOwner owner = registry.serviceMappingRepository().claim(app, provider.instanceId())
                    .toCompletableFuture().get(20, TimeUnit.SECONDS);
            registry.serviceMappingRepository().publish(owner, exported).toCompletableFuture()
                    .get(20, TimeUnit.SECONDS);
            registry.register(provider).toCompletableFuture().get(15, TimeUnit.SECONDS);
            assertTrue(await(Duration.ofSeconds(25), () ->
                    registry.lookup(app).toCompletableFuture().join().instances().equals(List.of(provider))));
            assertEquals(Set.of(app), registry.serviceMappingRepository()
                    .lookup(new ServiceMappingKey(app.namespace(), sample))
                    .toCompletableFuture().get(20, TimeUnit.SECONDS).applications());
            registry.serviceMappingRepository().revoke(owner).toCompletableFuture().get(20, TimeUnit.SECONDS);
            assertTrue(registry.serviceMappingRepository()
                    .lookup(new ServiceMappingKey(app.namespace(), sample))
                    .toCompletableFuture().join().applications().isEmpty());
        }
    }

    @Test
    void twoProcessesClaimSameOwnerAndStaleGenerationCannotReappear() throws Exception {
        ApplicationKey app = app("fencing");
        ServiceKey key = new ServiceKey("demo.Invoice", "1.0.0", "default");
        ServiceMappingKey mapping = new ServiceMappingKey(app.namespace(), key);
        ApplicationInstance member = provider(app, "node-a", 19633);
        try (ConsulApplicationRegistry first = registry(app);
             ConsulApplicationRegistry second = registry(app)) {
            ServiceMappingOwner firstOwner = first.serviceMappingRepository()
                    .claim(app, member.instanceId()).toCompletableFuture().get(20, TimeUnit.SECONDS);
            first.serviceMappingRepository().publish(firstOwner, Set.of(key))
                    .toCompletableFuture().get(20, TimeUnit.SECONDS);
            first.register(member).toCompletableFuture().get(15, TimeUnit.SECONDS);
            assertEquals(Set.of(app), first.serviceMappingRepository()
                    .lookup(mapping).toCompletableFuture().get(20, TimeUnit.SECONDS).applications());

            ServiceMappingOwner secondOwner = second.serviceMappingRepository()
                    .claim(app, member.instanceId()).toCompletableFuture().get(20, TimeUnit.SECONDS);
            assertTrue(secondOwner.generation() > firstOwner.generation());
            assertThrows(CompletionException.class, () ->
                    first.serviceMappingRepository().publish(firstOwner, Set.of(key))
                            .toCompletableFuture().join());
            first.serviceMappingRepository().revoke(firstOwner).toCompletableFuture().join();
            assertTrue(second.serviceMappingRepository()
                    .lookup(mapping).toCompletableFuture().join().applications().isEmpty());

            second.serviceMappingRepository().publish(secondOwner, Set.of(key)).toCompletableFuture().join();
            assertEquals(Set.of(app), second.serviceMappingRepository()
                    .lookup(mapping).toCompletableFuture().join().applications());
        }
    }

    @Test
    void concurrentDifferentOwnersMustNotLoseApplicationServiceMapping() throws Exception {
        ApplicationKey app = app("concurrent");
        ApplicationInstance a = provider(app, "node-a", 19634);
        ApplicationInstance b = provider(app, "node-b", 19635);
        ServiceKey key = new ServiceKey("demo.Payments", "1.0.0", "default");
        ServiceMappingKey mapping = new ServiceMappingKey(app.namespace(), key);
        try (ConsulApplicationRegistry left = registry(app);
             ConsulApplicationRegistry right = registry(app)) {
            ServiceMappingOwner ownerA = left.serviceMappingRepository().claim(app, a.instanceId())
                    .toCompletableFuture().get(20, TimeUnit.SECONDS);
            ServiceMappingOwner ownerB = right.serviceMappingRepository().claim(app, b.instanceId())
                    .toCompletableFuture().get(20, TimeUnit.SECONDS);
            CompletableFuture.allOf(
                    left.serviceMappingRepository().publish(ownerA, Set.of(key)).toCompletableFuture(),
                    right.serviceMappingRepository().publish(ownerB, Set.of(key)).toCompletableFuture())
                    .get(20, TimeUnit.SECONDS);
            left.register(a).toCompletableFuture().get(10, TimeUnit.SECONDS);
            right.register(b).toCompletableFuture().get(10, TimeUnit.SECONDS);
            assertEquals(Set.of(app), left.serviceMappingRepository()
                    .lookup(mapping).toCompletableFuture().get(20, TimeUnit.SECONDS).applications());
            left.serviceMappingRepository().revoke(ownerA).toCompletableFuture().join();
            left.unregister(a).toCompletableFuture().join();
            assertEquals(Set.of(app), right.serviceMappingRepository()
                    .lookup(mapping).toCompletableFuture().join().applications());
            right.serviceMappingRepository().revoke(ownerB).toCompletableFuture().join();
            assertTrue(left.serviceMappingRepository()
                    .lookup(mapping).toCompletableFuture().join().applications().isEmpty());
        }
    }

    @Test
    void immutableManifestSupportsConcurrentIdenticalWriteAndTwoRevisions() throws Exception {
        ApplicationKey app = app("manifest");
        ServiceKey one = new ServiceKey("demo.Catalog", "1.0.0", "default");
        ServiceKey two = new ServiceKey("demo.Price", "1.0.0", "default");
        ApplicationManifest v1 = ApplicationManifest.create(app, List.of(export(one)));
        ApplicationManifest v2 = ApplicationManifest.create(app, List.of(export(one), export(two)));
        try (ConsulApplicationRegistry left = registry(app);
             ConsulApplicationRegistry right = registry(app)) {
            CompletableFuture.allOf(
                    left.manifestRepository().publish(v1).toCompletableFuture(),
                    right.manifestRepository().publish(v1).toCompletableFuture(),
                    right.manifestRepository().publish(v2).toCompletableFuture())
                    .get(20, TimeUnit.SECONDS);
            assertEquals(v1, right.manifestRepository()
                    .lookupVerified(app, v1.revision()).toCompletableFuture().join().orElseThrow());
            assertEquals(v2, left.manifestRepository()
                    .lookupVerified(app, v2.revision()).toCompletableFuture().join().orElseThrow());
        }
    }

    @Test
    void remoteSubscriptionDeliversProviderThenEmptyView() throws Exception {
        ApplicationKey app = app("watch");
        ApplicationInstance provider = provider(app, "node-a", 19636);
        CountDownLatch present = new CountDownLatch(1);
        CountDownLatch removed = new CountDownLatch(1);
        AtomicBoolean observed = new AtomicBoolean();
        try (ConsulApplicationRegistry writer = registry(app);
             ConsulApplicationRegistry watcher = registry(app);
             RegistrySubscription ignored = watcher.subscribe(app, snapshot -> {
                 if (snapshot.instances().contains(provider)) {
                     observed.set(true);
                     present.countDown();
                 } else if (snapshot.instances().isEmpty() && observed.get()) {
                     removed.countDown();
                 }
             })) {
            writer.register(provider).toCompletableFuture().join();
            assertTrue(present.await(25, TimeUnit.SECONDS));
            writer.unregister(provider).toCompletableFuture().join();
            assertTrue(removed.await(25, TimeUnit.SECONDS));
        }
    }

    private static ConsulApplicationRegistry registry(ApplicationKey app) {
        String endpoint = System.getenv("CONSUL_TEST_ENDPOINT");
        assumeTrue(endpoint != null && !endpoint.isBlank(), "CONSUL_TEST_ENDPOINT is required");
        return (ConsulApplicationRegistry) new ConsulApplicationRegistryFactory().create(
                new RegistryOptions(List.of(endpoint), app.namespace(), Map.of(
                        ConsulProtocol.OPTION_TTL_SECONDS, "15",
                        ConsulProtocol.OPTION_HEARTBEAT_SECONDS, "3",
                        ConsulProtocol.OPTION_POLL_INTERVAL_MILLIS, "250",
                        "consulSessionTtlSeconds", "30")));
    }

    private static ApplicationKey app(String prefix) {
        return new ApplicationKey("test-" + UUID.randomUUID().toString().replace("-", ""),
                prefix);
    }

    private static ApplicationInstance provider(ApplicationKey app, String id, int port) {
        return new ApplicationInstance(id, app, ApplicationRole.PROVIDER,
                new RpcEndpoint("127.0.0.1", port), 100, "sha256:" + "a".repeat(64), Map.of());
    }

    private static ApplicationInstance consumer(ApplicationKey app, String id) {
        return new ApplicationInstance(id, app, ApplicationRole.CONSUMER, null, 0, null, Map.of());
    }

    private static ExportedService export(ServiceKey key) {
        return new ExportedService(key, RpcIds.serviceId(key), Set.of(100), "a".repeat(64));
    }

    private static boolean await(Duration timeout, CheckedBoolean check) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (check.accept()) {
                return true;
            }
            Thread.sleep(100L);
        }
        return check.accept();
    }

    @FunctionalInterface
    private interface CheckedBoolean {
        boolean accept() throws Exception;
    }
}
