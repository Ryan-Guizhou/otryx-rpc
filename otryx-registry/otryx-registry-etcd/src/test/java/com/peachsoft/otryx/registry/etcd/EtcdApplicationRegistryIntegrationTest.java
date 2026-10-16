package com.peachsoft.otryx.registry.etcd;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import com.peachsoft.otryx.registry.ApplicationDirectorySnapshot;
import com.peachsoft.otryx.registry.ApplicationRegistryListener;
import com.peachsoft.otryx.registry.RegistrySubscription;
import com.peachsoft.otryx.registry.ServiceMappingKey;
import com.peachsoft.otryx.registry.ServiceMappingOwner;
import io.etcd.jetcd.ByteSequence;
import io.etcd.jetcd.Client;
import io.etcd.jetcd.options.GetOption;
import io.etcd.jetcd.test.EtcdClusterExtension;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * 使用真实 Etcd 验证应用级 Lease、Mapping CAS、Manifest 与 Watch 恢复。
 *
 * <p>不允许用旧 ServiceKey Registry SPI 或伪造的纯 Consumer 端点通过验收。
 *
 * @Author OpenAI
 * @Version 1.0.0-SNAPSHOT
 * @CreateTime 2026/10/16 10:33
 */
@Timeout(value = 90, unit = TimeUnit.SECONDS)
class EtcdApplicationRegistryIntegrationTest {

    @RegisterExtension
    static final EtcdClusterExtension CLUSTER = EtcdClusterExtension.builder()
            .withClusterName("otryx-etcd-v2-it")
            .withNodes(1)
            .withSsl(false)
            .build();

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
    void thirtyInterfacesAndTenInstancesRegisterOnlyTenApplicationMembers() throws Exception {
        String namespace = unique("density");
        ApplicationKey app = new ApplicationKey(namespace, "batch");
        List<ServiceKey> services = new ArrayList<>();
        for (int index = 0; index < 30; index++) {
            services.add(new ServiceKey("demo.Batch" + index, "1.0.0", "default"));
        }
        List<EtcdApplicationRegistry> writers = new ArrayList<>();
        try (Client raw = rawClient()) {
            for (int index = 0; index < 10; index++) {
                EtcdApplicationRegistry writer = registry(namespace);
                writers.add(writer);
                String id = "batch-" + index;
                ServiceMappingOwner owner = writer.serviceMappingRepository()
                        .claim(app, id).toCompletableFuture().join();
                writer.serviceMappingRepository().publish(owner, Set.copyOf(services))
                        .toCompletableFuture().join();
                writer.register(provider(app, id, 18200 + index)).toCompletableFuture().join();
            }
            assertEquals(10, raw.getKVClient().get(
                    bytes(new EtcdV2Paths(namespace).memberPrefix(app)),
                    GetOption.builder().isPrefix(true).build()).join().getCount());
            assertEquals(10, writers.getFirst().lookup(app).toCompletableFuture().join().instances().size());
            for (ServiceKey service : List.of(services.getFirst(), services.get(15), services.getLast())) {
                assertEquals(Set.of(app), writers.getFirst().serviceMappingRepository()
                        .lookup(new ServiceMappingKey(namespace, service))
                        .toCompletableFuture().join().applications());
            }
        } finally {
            writers.forEach(EtcdApplicationRegistry::close);
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
                assertTrue(latest.get().revision() >= compactAt);
            }
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
