package com.peachsoft.otryx.registry.etcd;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import com.peachsoft.otryx.api.ApplicationInstance;
import com.peachsoft.otryx.api.ApplicationKey;
import com.peachsoft.otryx.api.ApplicationManifest;
import com.peachsoft.otryx.api.ApplicationRole;
import com.peachsoft.otryx.api.ExportedService;
import com.peachsoft.otryx.api.RpcEndpoint;
import com.peachsoft.otryx.api.RpcIds;
import com.peachsoft.otryx.api.ServiceKey;
import com.peachsoft.otryx.observability.RpcObserver;
import com.peachsoft.otryx.registry.ApplicationRegistryContractTestKit;
import com.peachsoft.otryx.registry.ApplicationDirectorySnapshot;
import com.peachsoft.otryx.registry.ApplicationRegistryListener;
import com.peachsoft.otryx.registry.RegistrySubscription;
import com.peachsoft.otryx.registry.ServiceMappingKey;
import com.peachsoft.otryx.registry.ServiceMappingOwner;
import io.etcd.jetcd.ByteSequence;
import io.etcd.jetcd.Client;
import io.etcd.jetcd.options.GetOption;
import io.etcd.jetcd.test.EtcdClusterExtension;
import io.vertx.core.Vertx;
import java.net.URI;
import java.nio.charset.StandardCharsets;
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
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.Extension;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * 使用真实 Etcd 验证应用级 Lease、Mapping CAS、Manifest 与 Watch 恢复。
 *
 * <p>默认创建真实 Docker 集群；显式设置 ETCD_TEST_ENDPOINT 时复用真实外部后端。
 * 外部后端仍执行租约到期、撤销与 Watch 恢复测试，只有需要控制集群进程的重启场景不适用。
 *
 * @Author OpenAI
 * @Version 1.0.0-SNAPSHOT
 * @CreateTime 2026/10/16 10:33
 */
@Timeout(value = 90, unit = TimeUnit.SECONDS)
class EtcdApplicationRegistryIntegrationTest {

    private static final String EXTERNAL_ENDPOINT = System.getenv("ETCD_TEST_ENDPOINT");
    private static final boolean EXTERNAL_CLUSTER = EXTERNAL_ENDPOINT != null && !EXTERNAL_ENDPOINT.isBlank();
    private static final EtcdClusterExtension CLUSTER = EXTERNAL_CLUSTER ? null : EtcdClusterExtension.builder()
            .withClusterName("otryx-etcd-v2-it")
            .withNodes(1)
            .withSsl(false)
            .build();

    @RegisterExtension
    static final Extension BACKEND = EXTERNAL_CLUSTER ? (BeforeAllCallback) context -> { } : CLUSTER;

    @Test
    void registrationFromVertxEventLoopMustUseCompatibleClient() throws Exception {
        String namespace = unique("event-loop");
        ApplicationKey application = new ApplicationKey(namespace, "orders");
        ApplicationInstance instance = provider(application, "event-loop-provider", 18101);
        Vertx vertx = Vertx.vertx();
        try (EtcdApplicationRegistry registry = registry(namespace)) {
            CompletableFuture<Void> registered = new CompletableFuture<>();
            vertx.runOnContext(ignored -> {
                try {
                    assertNotNull(Vertx.currentContext());
                    assertTrue(Vertx.currentContext().isEventLoopContext());
                    registry.register(instance).whenComplete((result, error) -> {
                        if (error == null) {
                            registered.complete(null);
                        } else {
                            registered.completeExceptionally(error);
                        }
                    });
                } catch (Throwable error) {
                    registered.completeExceptionally(error);
                }
            });
            registered.get(10, TimeUnit.SECONDS);
            assertEquals(List.of(instance), registry.lookup(application)
                    .toCompletableFuture().get(10, TimeUnit.SECONDS).instances());
            registry.unregister(instance).toCompletableFuture().get(10, TimeUnit.SECONDS);
        } finally {
            vertx.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
        }
    }

    @Test
    void providerAndConsumerRegisterRealApplicationMembersNotServiceKeys() throws Exception {
        String namespace = unique("roles");
        ApplicationKey app = new ApplicationKey(namespace, "orders");
        ApplicationInstance provider = provider(app, "provider-a", 18101);
        ApplicationInstance consumer = consumer(app, "consumer-a");

        try (EtcdApplicationRegistry first = registry(namespace);
             EtcdApplicationRegistry second = registry(namespace);
             Client raw = rawClient()) {
            first.register(provider).toCompletableFuture().get(10, TimeUnit.SECONDS);
            second.register(consumer).toCompletableFuture().get(10, TimeUnit.SECONDS);

            ApplicationDirectorySnapshot directory = first.lookup(app).toCompletableFuture().join();
            assertEquals(Set.of(provider, consumer), Set.copyOf(directory.instances()));
            assertFalse(directory.instances().stream()
                    .filter(member -> member.role() == ApplicationRole.CONSUMER)
                    .findFirst().orElseThrow().routableProvider());
            assertEquals(2, raw.getKVClient().get(
                    bytes(new EtcdV2Paths(namespace).memberPrefix(app)),
                    GetOption.builder().isPrefix(true).build()).get(10, TimeUnit.SECONDS).getCount());

            first.unregister(provider).toCompletableFuture().get(10, TimeUnit.SECONDS);
            assertEquals(List.of(consumer), second.lookup(app).toCompletableFuture().join().instances());
        }
    }

    @Test
    void mappingMustFenceOldOwnerAndKeepOtherLiveProvider() throws Exception {
        String namespace = unique("mapping");
        ApplicationKey app = new ApplicationKey(namespace, "payment");
        ServiceKey service = new ServiceKey("demo.Payment", "1.0.0", "default");
        ServiceMappingKey mappingKey = new ServiceMappingKey(namespace, service);

        try (EtcdApplicationRegistry first = registry(namespace);
             EtcdApplicationRegistry second = registry(namespace)) {
            ServiceMappingOwner a = first.serviceMappingRepository().claim(app, "node-a")
                    .toCompletableFuture().join();
            ServiceMappingOwner b = second.serviceMappingRepository().claim(app, "node-b")
                    .toCompletableFuture().join();
            first.serviceMappingRepository().publish(a, Set.of(service)).toCompletableFuture().join();
            second.serviceMappingRepository().publish(b, Set.of(service)).toCompletableFuture().join();
            first.register(provider(app, "node-a", 18102)).toCompletableFuture().join();
            second.register(provider(app, "node-b", 18103)).toCompletableFuture().join();

            assertEquals(Set.of(app), first.serviceMappingRepository()
                    .lookupVerified(mappingKey).toCompletableFuture().join().applications());
            first.serviceMappingRepository().revoke(a).toCompletableFuture().join();
            first.unregister(provider(app, "node-a", 18102)).toCompletableFuture().join();
            assertEquals(Set.of(app), second.serviceMappingRepository()
                    .lookup(mappingKey).toCompletableFuture().join().applications());

            ServiceMappingOwner newer = first.serviceMappingRepository().claim(app, "node-a")
                    .toCompletableFuture().join();
            assertTrue(newer.generation() > a.generation());
            assertThrows(CompletionException.class,
                    () -> first.serviceMappingRepository().publish(a, Set.of(service))
                            .toCompletableFuture().join());
            first.serviceMappingRepository().revoke(a).toCompletableFuture().join();
            assertEquals(Set.of(app), second.serviceMappingRepository()
                    .lookup(mappingKey).toCompletableFuture().join().applications());
            second.serviceMappingRepository().revoke(b).toCompletableFuture().join();
            assertTrue(first.serviceMappingRepository().lookup(mappingKey)
                    .toCompletableFuture().join().applications().isEmpty());
        }
    }

    @Test
    void immutableManifestSupportsRollingRevisionsAndRejectsTampering() throws Exception {
        String namespace = unique("manifest");
        ApplicationKey app = new ApplicationKey(namespace, "inventory");
        ServiceKey firstService = new ServiceKey("demo.Stock", "1.0.0", "default");
        ServiceKey secondService = new ServiceKey("demo.Price", "1.0.0", "default");
        ApplicationManifest v1 = manifest(app, firstService);
        ApplicationManifest v2 = ApplicationManifest.create(
                app, List.of(export(firstService), export(secondService)));

        try (EtcdApplicationRegistry registry = registry(namespace);
             Client raw = rawClient()) {
            registry.manifestRepository().publish(v1).toCompletableFuture().join();
            registry.manifestRepository().publish(v1).toCompletableFuture().join();
            registry.manifestRepository().publish(v2).toCompletableFuture().join();
            assertEquals(v1, registry.manifestRepository().lookupVerified(app, v1.revision())
                    .toCompletableFuture().join().orElseThrow());
            assertEquals(v2, registry.manifestRepository().lookupVerified(app, v2.revision())
                    .toCompletableFuture().join().orElseThrow());

            raw.getKVClient().put(
                    bytes(new EtcdV2Paths(namespace).manifestKey(app, v1.revision())),
                    ByteSequence.from(new byte[] {0, 1, 2})).join();
            assertThrows(CompletionException.class,
                    () -> registry.manifestRepository().lookupVerified(app, v1.revision())
                            .toCompletableFuture().join());
        }
    }

    @Test
    void leaseRevocationRecoversMembershipAndWatchPublishesNewRevision() throws Exception {
        String namespace = unique("recovery");
        ApplicationKey app = new ApplicationKey(namespace, "catalog");
        ApplicationInstance member = provider(app, "node-lease", 18104);
        CountDownLatch seen = new CountDownLatch(1);
        AtomicReference<ApplicationDirectorySnapshot> latest = new AtomicReference<>();

        try (EtcdApplicationRegistry writer = registry(namespace);
             EtcdApplicationRegistry reader = registry(namespace);
             Client raw = rawClient();
             RegistrySubscription ignored = reader.subscribe(app, snapshot -> {
                 latest.set(snapshot);
                 if (snapshot.instances().contains(member)) {
                     seen.countDown();
                 }
             })) {
            writer.register(member).toCompletableFuture().get(10, TimeUnit.SECONDS);
            assertTrue(seen.await(10, TimeUnit.SECONDS));
            String path = new EtcdV2Paths(namespace).memberKey(app, member.instanceId());
            long oldLease = raw.getKVClient().get(bytes(path)).get(10, TimeUnit.SECONDS)
                    .getKvs().getFirst().getLease();
            assertTrue(oldLease > 0);
            raw.getLeaseClient().revoke(oldLease).get(10, TimeUnit.SECONDS);

            assertTrue(await(Duration.ofSeconds(20), () -> {
                var response = raw.getKVClient().get(bytes(path)).get(5, TimeUnit.SECONDS);
                return !response.getKvs().isEmpty()
                        && response.getKvs().getFirst().getLease() != oldLease
                        && response.getKvs().getFirst().getLease() > 0;
            }));
            assertTrue(await(Duration.ofSeconds(10), () -> reader.lookup(app)
                    .toCompletableFuture().join().instances().contains(member)));
        }
    }

    @Test
    void clusterRestartRestoresRegistrationAndApplicationSubscription() throws Exception {
        assumeFalse(EXTERNAL_CLUSTER, "Cluster restart requires the managed Docker backend");
        String namespace = unique("restart");
        ApplicationKey application = new ApplicationKey(namespace, "restart");
        ApplicationInstance provider = provider(application, "restart-provider", 18111);
        ApplicationInstance consumer = consumer(application, "restart-consumer");
        AtomicReference<ApplicationDirectorySnapshot> latest = new AtomicReference<>();
        Client client = Client.builder().target("cluster://" + CLUSTER.clusterName()).build();
        try (EtcdApplicationRegistry registry = new EtcdApplicationRegistry(client, 2, namespace, RpcObserver.noop());
             RegistrySubscription ignored = registry.subscribe(application, latest::set)) {
            registry.register(provider).toCompletableFuture().get(10, TimeUnit.SECONDS);
            assertTrue(await(Duration.ofSeconds(10), () -> latest.get() != null
                    && latest.get().instances().contains(provider)));
            CLUSTER.restart(2, TimeUnit.SECONDS);
            assertTrue(await(Duration.ofSeconds(20), () -> registry.lookup(application)
                    .toCompletableFuture().get(5, TimeUnit.SECONDS).instances().contains(provider)));
            assertTrue(await(Duration.ofSeconds(20), () -> {
                registry.register(consumer).toCompletableFuture().get(5, TimeUnit.SECONDS);
                return true;
            }));
            assertTrue(await(Duration.ofSeconds(20), () -> latest.get() != null
                    && Set.copyOf(latest.get().instances()).equals(Set.of(provider, consumer))));
        }
    }

    @Test
    void abandonedClientLeaseExpiresWithoutRemovingAnotherApplicationMember() throws Exception {
        String namespace = unique("expiry");
        ApplicationKey application = new ApplicationKey(namespace, "lease-expiry");
        ApplicationInstance provider = provider(application, "abandoned", 18112);
        ApplicationInstance consumer = consumer(application, "survivor");
        Client abandoned = rawClient();
        try (EtcdApplicationRegistry writer = new EtcdApplicationRegistry(
                     abandoned, 1, namespace, RpcObserver.noop());
             EtcdApplicationRegistry reader = registry(namespace)) {
            writer.register(provider).toCompletableFuture().get(10, TimeUnit.SECONDS);
            reader.register(consumer).toCompletableFuture().get(10, TimeUnit.SECONDS);
            assertEquals(Set.of(provider, consumer),
                    Set.copyOf(reader.lookup(application).toCompletableFuture().join().instances()));
            abandoned.close();
            assertTrue(await(Duration.ofSeconds(15), () -> reader.lookup(application)
                    .toCompletableFuture().get(5, TimeUnit.SECONDS).instances().equals(List.of(consumer))));
        }
    }

    @Test
    void invalidApplicationNamespacesAreRejectedBeforeNetworkAccess() {
        String namespace = unique("isolation");
        ApplicationKey foreign = new ApplicationKey(unique("foreign"), "orders");
        try (EtcdApplicationRegistry registry = registry(namespace)) {
            assertThrows(IllegalArgumentException.class,
                    () -> registry.lookup(foreign));
            assertThrows(IllegalArgumentException.class,
                    () -> registry.register(consumer(foreign, "bad-member")));
        }
    }


    @Test
    @Timeout(value = 300, unit = TimeUnit.SECONDS)
    void thirtyRealContractsAndTenIndependentClientsSatisfySharedApplicationContract() throws Exception {
        String namespace = unique("contract");
        try (Client raw = rawClient()) {
            ApplicationRegistryContractTestKit.verify("etcd", namespace, 10, () -> registry(namespace),
                    application -> raw.getKVClient().get(
                            bytes(new EtcdV2Paths(namespace).memberPrefix(application)),
                            GetOption.builder().isPrefix(true).build()).get(10, TimeUnit.SECONDS).getCount(),
                    Duration.ofSeconds(20));
        }
    }

    @Test
    void compactedWatchReportsFailureAndResynchronizesFullApplicationDirectory() throws Exception {
        String namespace = unique("compaction");
        ApplicationKey app = new ApplicationKey(namespace, "gateway");
        ApplicationInstance first = provider(app, "node-first", 18301);
        ApplicationInstance second = consumer(app, "node-second");
        CountDownLatch recovered = new CountDownLatch(1);
        CountDownLatch failed = new CountDownLatch(1);
        CountDownLatch healthyAfterFailure = new CountDownLatch(1);
        AtomicReference<ApplicationDirectorySnapshot> latest = new AtomicReference<>();
        ApplicationRegistryListener listener = new ApplicationRegistryListener() {
            @Override
            public void onSnapshot(ApplicationDirectorySnapshot snapshot) {
                latest.set(snapshot);
                if (snapshot.instances().contains(first) && snapshot.instances().contains(second)) {
                    recovered.countDown();
                }
            }

            @Override
            public void onSynchronizationFailed(Throwable error) {
                failed.countDown();
            }

            @Override
            public void onSynchronizationSucceeded() {
                if (failed.getCount() == 0) {
                    healthyAfterFailure.countDown();
                }
            }
        };

        try (EtcdApplicationRegistry writer = registry(namespace);
             Client raw = rawClient()) {
            writer.register(first).toCompletableFuture().join();
            long stale = writer.lookup(app).toCompletableFuture().join().revision();
            writer.register(second).toCompletableFuture().join();
            long compactAt = writer.lookup(app).toCompletableFuture().join().revision();
            assertTrue(compactAt > stale);
            raw.getKVClient().compact(compactAt).get(10, TimeUnit.SECONDS);
            try (RegistrySubscription ignored = writer.subscribeFromRevision(app, listener, stale)) {
                assertTrue(failed.await(15, TimeUnit.SECONDS));
                assertTrue(recovered.await(15, TimeUnit.SECONDS));
                assertTrue(healthyAfterFailure.await(15, TimeUnit.SECONDS));
                assertTrue(latest.get().revision() >= compactAt);
            }
        }
    }


    @Test
    void anotherProcessMustNotTakeOverLiveInstanceIdOrDeleteItsNewRevision() {
        String namespace = unique("takeover");
        ApplicationKey app = new ApplicationKey(namespace, "shipping");
        ApplicationInstance original = provider(app, "instance-a", 18420);
        ApplicationInstance replacement = provider(app, "instance-a", 18421);
        try (EtcdApplicationRegistry oldProcess = registry(namespace);
             EtcdApplicationRegistry newProcess = registry(namespace)) {
            oldProcess.register(original).toCompletableFuture().join();
            assertThrows(CompletionException.class,
                    () -> newProcess.register(replacement).toCompletableFuture().join());
            assertEquals(List.of(original),
                    oldProcess.lookup(app).toCompletableFuture().join().instances());

            oldProcess.unregister(original).toCompletableFuture().join();
            newProcess.register(replacement).toCompletableFuture().join();
            oldProcess.unregister(original).toCompletableFuture().join();
            assertEquals(List.of(replacement),
                    newProcess.lookup(app).toCompletableFuture().join().instances());
        }
    }

    @Test
    void mappingMustRemainNonRoutableUntilProviderMembershipIsReady() {
        String namespace = unique("unready");
        ApplicationKey app = new ApplicationKey(namespace, "reports");
        ServiceKey service = new ServiceKey("demo.Report", "1.0.0", "default");
        ServiceMappingKey mappingKey = new ServiceMappingKey(namespace, service);
        try (EtcdApplicationRegistry registry = registry(namespace)) {
            ServiceMappingOwner owner = registry.serviceMappingRepository().claim(app, "reports-a")
                    .toCompletableFuture().join();
            registry.serviceMappingRepository().publish(owner, Set.of(service))
                    .toCompletableFuture().join();
            assertTrue(registry.serviceMappingRepository().lookup(mappingKey)
                    .toCompletableFuture().join().applications().isEmpty());

            ApplicationInstance onlyConsumer = consumer(app, "reports-a");
            registry.register(onlyConsumer).toCompletableFuture().join();
            assertTrue(registry.serviceMappingRepository().lookup(mappingKey)
                    .toCompletableFuture().join().applications().isEmpty());

            ApplicationInstance readyProvider = provider(app, "reports-a", 18422);
            registry.register(readyProvider).toCompletableFuture().join();
            assertEquals(Set.of(app), registry.serviceMappingRepository().lookup(mappingKey)
                    .toCompletableFuture().join().applications());

            registry.register(onlyConsumer).toCompletableFuture().join();
            assertTrue(registry.serviceMappingRepository().lookup(mappingKey)
                    .toCompletableFuture().join().applications().isEmpty());
        }
    }

    private static ApplicationManifest manifest(ApplicationKey app, ServiceKey key) {
        return ApplicationManifest.create(app, List.of(export(key)));
    }

    private static ExportedService export(ServiceKey key) {
        return new ExportedService(key, RpcIds.serviceId(key), Set.of(100), "a".repeat(64));
    }

    private static EtcdApplicationRegistry registry(String namespace) {
        return new EtcdApplicationRegistry(endpoints(), 3, namespace);
    }

    private static String[] endpoints() {
        if (EXTERNAL_CLUSTER) {
            return new String[] {EXTERNAL_ENDPOINT.trim()};
        }
        return CLUSTER.clientEndpoints().stream().map(URI::toString).toArray(String[]::new);
    }

    private static Client rawClient() {
        return Client.builder().endpoints(endpoints()).build();
    }

    private static ByteSequence bytes(String value) {
        return ByteSequence.from(value, StandardCharsets.UTF_8);
    }

    private static ApplicationInstance consumer(ApplicationKey app, String id) {
        return new ApplicationInstance(id, app, ApplicationRole.CONSUMER, null, 0, null, Map.of());
    }

    private static ApplicationInstance provider(ApplicationKey app, String id, int port) {
        return new ApplicationInstance(id, app, ApplicationRole.PROVIDER,
                new RpcEndpoint("127.0.0.1", port), 100, "sha256:" + "a".repeat(64), Map.of());
    }

    private static String unique(String name) {
        return "v2-" + name + '-' + UUID.randomUUID();
    }

    private static boolean await(Duration timeout, CheckedBoolean condition) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            try {
                if (condition.check()) {
                    return true;
                }
            } catch (Exception ignored) {
                // Transient cluster transition; next bounded poll retries.
            }
            Thread.sleep(100);
        }
        return condition.check();
    }

    @FunctionalInterface
    private interface CheckedBoolean {
        boolean check() throws Exception;
    }
}
