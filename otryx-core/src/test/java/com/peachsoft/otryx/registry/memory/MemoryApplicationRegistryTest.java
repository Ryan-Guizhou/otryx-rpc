package com.peachsoft.otryx.registry.memory;

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
import com.peachsoft.otryx.registry.ApplicationRegistryFactory;
import com.peachsoft.otryx.registry.ApplicationServiceResolver;
import com.peachsoft.otryx.registry.RegistryOptions;
import com.peachsoft.otryx.registry.ManifestSnapshotCache;
import com.peachsoft.otryx.registry.RegistrySubscription;
import com.peachsoft.otryx.registry.ServiceMappingCache;
import com.peachsoft.otryx.registry.ServiceMappingKey;
import com.peachsoft.otryx.registry.ServiceMappingOwner;
import com.peachsoft.otryx.spi.ExtensionLoader;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/**
 * 验证进程内应用成员、原子订阅、Manifest 和真实 Provider 解析端到端控制面。
 *
 * @Author OpenAI
 * @Version 1.0.0-SNAPSHOT
 * @CreateTime 2026/10/15 18:30
 */
class MemoryApplicationRegistryTest {

    private static final ApplicationKey APP = new ApplicationKey("production", "payments");
    private static final ServiceKey PAYMENT = new ServiceKey("demo.Payment", "1.0.0", "default");
    private static final String SCHEMA = "a".repeat(64);

    @Test
    void providerAndConsumerMembershipIsApplicationScopedAndIdempotent() {
        try (MemoryApplicationRegistry registry = new MemoryApplicationRegistry()) {
            ApplicationInstance provider = provider(APP, "p1", "revision-1");
            ApplicationInstance upgraded = provider(APP, "p1", "revision-2");
            ApplicationInstance consumer = consumer(APP, "c1");
            List<Integer> observations = new ArrayList<>();
            try (RegistrySubscription subscription = registry.subscribe(APP,
                    snapshot -> observations.add(snapshot.instances().size()))) {
                registry.registrar().orElseThrow().register(provider).toCompletableFuture().join();
                registry.register(provider).toCompletableFuture().join();
                long revision = registry.lookup(APP).toCompletableFuture().join().revision();
                registry.register(provider).toCompletableFuture().join();
                assertEquals(revision, registry.lookup(APP).toCompletableFuture().join().revision());
                registry.register(consumer).toCompletableFuture().join();
                assertEquals(2, registry.lookup(APP).toCompletableFuture().join().instances().size());
                registry.register(upgraded).toCompletableFuture().join();
                registry.unregister(provider).toCompletableFuture().join();
                assertTrue(registry.lookup(APP).toCompletableFuture().join().instances().contains(upgraded));
                registry.unregister(upgraded).toCompletableFuture().join();
                assertEquals(List.of(0, 1, 2, 2, 1), observations);
            }
            assertEquals(1, registry.lookup(APP).toCompletableFuture().join().instances().size());
            assertFalse(registry.lookup(APP).toCompletableFuture().join().instances().getFirst().routableProvider());
        }
    }

    @Test
    void concurrentInstancesStayUniqueWithIndependentNamespaceAndClosedFailFast() {
        MemoryApplicationRegistry registry = new MemoryApplicationRegistry();
        CompletableFuture<?>[] writes = IntStream.range(0, 96)
                .mapToObj(i -> CompletableFuture.runAsync(() ->
                        registry.register(consumer(APP, "consumer-" + i)).toCompletableFuture().join()))
                .toArray(CompletableFuture[]::new);
        CompletableFuture.allOf(writes).join();
        assertEquals(96, registry.lookup(APP).toCompletableFuture().join().instances().size());
        ApplicationKey other = new ApplicationKey("test", "payments");
        assertTrue(registry.lookup(other).toCompletableFuture().join().instances().isEmpty());
        registry.close();
        assertThrows(CompletionException.class,
                () -> registry.register(consumer(APP, "new")).toCompletableFuture().join());
        assertThrows(CompletionException.class, () -> registry.lookup(APP).toCompletableFuture().join());
    }

    @Test
    void memoryControlPlaneResolvesThreeServicesWithSingleApplicationMembership() {
        try (MemoryApplicationRegistry registry = new MemoryApplicationRegistry();
                MemoryApplicationManifestRepository manifests = new MemoryApplicationManifestRepository()) {
            MemoryServiceMappingRepository mapping = new MemoryServiceMappingRepository();
            ApplicationKey caller = new ApplicationKey("production", "consumer");
            ServiceKey inventory = new ServiceKey("demo.Inventory", "1.0.0", "default");
            ServiceKey delivery = new ServiceKey("demo.Delivery", "1.0.0", "default");
            List<ServiceKey> services = List.of(PAYMENT, inventory, delivery);
            ApplicationManifest manifest = ApplicationManifest.create(APP, services.stream()
                    .map(key -> new ExportedService(key, RpcIds.serviceId(key), Set.of(1), SCHEMA))
                    .toList());
            manifests.publish(manifest).toCompletableFuture().join();
            manifests.publish(manifest).toCompletableFuture().join();
            ApplicationInstance provider = provider(APP, "p1", manifest.revision());
            ApplicationInstance consumer = consumer(caller, "c1");
            ServiceMappingOwner owner = mapping.claim(APP, "p1").toCompletableFuture().join();
            mapping.publish(owner, Set.copyOf(services)).toCompletableFuture().join();
            registry.register(provider).toCompletableFuture().join();
            registry.register(consumer).toCompletableFuture().join();
            assertEquals(1, registry.lookup(APP).toCompletableFuture().join().instances().size());
            assertEquals(1, registry.lookup(caller).toCompletableFuture().join().instances().size());
            assertFalse(consumer.routableProvider());

            try (ServiceMappingCache mappings = new ServiceMappingCache(
                    mapping, Duration.ofSeconds(2), Duration.ZERO);
                    ManifestSnapshotCache cache = new ManifestSnapshotCache(manifests);
                    ApplicationServiceResolver resolver = new ApplicationServiceResolver(
                            mappings, registry, cache, Duration.ofSeconds(2))) {
                for (ServiceKey service : services) {
                    var snapshot = resolver.refresh(new ServiceMappingKey("production", service), SCHEMA)
                            .toCompletableFuture().join();
                    assertEquals(List.of(provider), snapshot.providers());
                }
                assertEquals(1, registry.lookup(APP).toCompletableFuture().join().instances().size());
                registry.unregister(provider).toCompletableFuture().join();
                var after = resolver.refresh(new ServiceMappingKey("production", PAYMENT), SCHEMA)
                        .toCompletableFuture().join();
                assertTrue(after.providers().isEmpty());
                mapping.revoke(owner).toCompletableFuture().join();
                assertTrue(mapping.lookup(new ServiceMappingKey("production", PAYMENT))
                        .toCompletableFuture().join().applications().isEmpty());
                assertTrue(registry.lookup(caller).toCompletableFuture().join()
                        .instances().contains(consumer));
            }
        }
    }

    @Test
    void applicationRegistryFactoryMustResolveMemoryWithoutLegacySpi() {
        ApplicationRegistryFactory factory =
                ExtensionLoader.getLoader(ApplicationRegistryFactory.class).getDefaultExtension();
        try (var registry = factory.create(new RegistryOptions(List.of(), "production", Map.of()))) {
            registry.registrar().orElseThrow().register(consumer(APP, "factory-consumer"))
                    .toCompletableFuture().join();
            assertEquals(1, registry.lookup(APP).toCompletableFuture().join().instances().size());
        }
    }

    @Test
    void memoryManifestRepositoryPreservesMultipleRevisionsAndNamespaceIsolation() {
        try (MemoryApplicationManifestRepository repository = new MemoryApplicationManifestRepository()) {
            ServiceKey secondVersion = new ServiceKey("demo.Payment", "2.0.0", "default");
            ApplicationManifest first = ApplicationManifest.create(APP, List.of(
                    new ExportedService(PAYMENT, RpcIds.serviceId(PAYMENT), Set.of(1), SCHEMA)));
            ApplicationManifest second = ApplicationManifest.create(APP, List.of(
                    new ExportedService(secondVersion, RpcIds.serviceId(secondVersion), Set.of(1), SCHEMA)));
            repository.publish(first).toCompletableFuture().join();
            repository.publish(first).toCompletableFuture().join();
            repository.publish(second).toCompletableFuture().join();
            assertEquals(first, repository.lookupVerified(APP, first.revision())
                    .toCompletableFuture().join().orElseThrow());
            assertEquals(second, repository.lookupVerified(APP, second.revision())
                    .toCompletableFuture().join().orElseThrow());
            assertTrue(repository.lookupVerified(new ApplicationKey("test", "payments"), first.revision())
                    .toCompletableFuture().join().isEmpty());
        }
    }

    private static ApplicationInstance provider(ApplicationKey application, String id, String revision) {
        return new ApplicationInstance(id, application, ApplicationRole.PROVIDER,
                new RpcEndpoint("127.0.0.1", 18080), 100, revision, Map.of());
    }

    private static ApplicationInstance consumer(ApplicationKey application, String id) {
        return new ApplicationInstance(id, application, ApplicationRole.CONSUMER,
                null, 0, null, Map.of());
    }
}
