package com.peachsoft.otryx.registry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
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
import com.peachsoft.otryx.registry.memory.MemoryServiceMappingRepository;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/**
 * 验证 Application 解析仅发布 Manifest + Schema 兼容的真实 Provider 实例。
 *
 * @Author OpenAI
 * @Version 1.0.0-SNAPSHOT
 * @CreateTime 2026/10/15 18:30
 */
class ApplicationServiceResolverTest {

    private static final ServiceKey SERVICE = new ServiceKey("demo.Payment", "1.0.0", "default");
    private static final ServiceMappingKey KEY = new ServiceMappingKey("production", SERVICE);
    private static final String SCHEMA = "a".repeat(64);
    private static final String OTHER_SCHEMA = "b".repeat(64);

    @Test
    void resolvesMultipleApplicationsAndRevisionsWithoutRoutingPureConsumer() {
        Fixture f = new Fixture();
        f.addProvider("payments", "a1", SCHEMA);
        f.addProvider("payments", "a2", OTHER_SCHEMA);
        f.addConsumer("payments", "c1");
        f.addProvider("alternate", "b1", SCHEMA);
        VerifiedProviderSnapshot result = f.resolver.refresh(KEY, SCHEMA).toCompletableFuture().join();
        assertEquals(Set.of("a1", "b1"),
                result.providers().stream().map(ApplicationInstance::instanceId)
                        .collect(java.util.stream.Collectors.toSet()));
        assertEquals(ApplicationServiceResolver.Status.HEALTHY, f.resolver.status(KEY, SCHEMA));
        int discoveries = f.discovery.calls.get();
        int manifests = f.manifests.calls.get();
        for (int i = 0; i < 10; i++) {
            assertEquals(result, f.resolver.current(KEY, SCHEMA).orElseThrow());
        }
        assertEquals(discoveries, f.discovery.calls.get());
        assertEquals(manifests, f.manifests.calls.get());
        assertThrows(UnsupportedOperationException.class,
                () -> result.providers().add(result.providers().getFirst()));
        f.close();
    }

    @Test
    void legitimatelyEmptyMappingIsHealthyButMissingManifestIsNot() {
        Fixture empty = new Fixture();
        VerifiedProviderSnapshot snapshot = empty.resolver.refresh(KEY, SCHEMA).toCompletableFuture().join();
        assertTrue(snapshot.providers().isEmpty());
        assertEquals(ApplicationServiceResolver.Status.HEALTHY, empty.resolver.status(KEY, SCHEMA));
        empty.close();

        Fixture missing = new Fixture();
        ApplicationInstance instance = missing.addProvider("payments", "only", SCHEMA);
        missing.manifests.data.remove(instance.manifestRevision());
        assertThrows(CompletionException.class,
                () -> missing.resolver.refresh(KEY, SCHEMA).toCompletableFuture().join());
        assertEquals(ApplicationServiceResolver.Status.NOT_READY, missing.resolver.status(KEY, SCHEMA));
        assertTrue(missing.resolver.current(KEY, SCHEMA).isEmpty());
        missing.close();
    }

    @Test
    void discoveryFailureRetainsOnlyTimeBoundedLastKnownGood() {
        Fixture f = new Fixture();
        f.addProvider("payments", "a1", SCHEMA);
        f.resolver.refresh(KEY, SCHEMA).toCompletableFuture().join();
        f.discovery.fail = true;
        assertThrows(CompletionException.class, () ->
                f.resolver.refresh(KEY, SCHEMA).toCompletableFuture().join());
        assertEquals(ApplicationServiceResolver.Status.LAST_KNOWN_GOOD, f.resolver.status(KEY, SCHEMA));
        assertEquals(1, f.resolver.current(KEY, SCHEMA).orElseThrow().providers().size());
        f.clock.addAndGet(Duration.ofSeconds(3).toNanos());
        assertEquals(ApplicationServiceResolver.Status.STALE, f.resolver.status(KEY, SCHEMA));
        assertTrue(f.resolver.current(KEY, SCHEMA).isEmpty());
        f.close();
    }

    @Test
    void concurrentRefreshSharesPendingCallAndClosePreventsLatePublication() {
        Fixture f = new Fixture();
        f.addProvider("payments", "a1", SCHEMA);
        f.discovery.hold = new CompletableFuture<>();
        CompletableFuture<VerifiedProviderSnapshot> first =
                f.resolver.refresh(KEY, SCHEMA).toCompletableFuture();
        CompletableFuture<VerifiedProviderSnapshot> second =
                f.resolver.refresh(KEY, SCHEMA).toCompletableFuture();
        assertSame(first, second);
        assertFalse(first.isDone());
        f.resolver.close();
        assertTrue(first.isCompletedExceptionally());
        f.discovery.hold.complete(f.discovery.direct("payments"));
        assertTrue(f.resolver.current(KEY, SCHEMA).isEmpty());
        f.mappingCache.close();
        f.manifestCache.close();
    }

    private static final class Fixture {
        private final AtomicLong clock = new AtomicLong(100L);
        private final MemoryServiceMappingRepository mapping = new MemoryServiceMappingRepository();
        private final FakeDiscovery discovery = new FakeDiscovery();
        private final FakeManifests manifests = new FakeManifests();
        private final ServiceMappingCache mappingCache =
                new ServiceMappingCache(mapping, Duration.ofSeconds(2), Duration.ZERO, clock::get);
        private final ManifestSnapshotCache manifestCache = new ManifestSnapshotCache(manifests, clock::get);
        private final ApplicationServiceResolver resolver = new ApplicationServiceResolver(
                mappingCache, discovery, manifestCache, Duration.ofSeconds(2), clock::get);

        private ApplicationInstance addProvider(String appName, String instanceId, String schema) {
            ApplicationKey app = new ApplicationKey("production", appName);
            ExportedService exported = new ExportedService(
                    SERVICE, RpcIds.serviceId(SERVICE), Set.of(1), schema);
            ApplicationManifest manifest = ApplicationManifest.create(app, List.of(exported));
            manifests.data.put(manifest.revision(), manifest);
            ApplicationInstance instance = new ApplicationInstance(
                    instanceId, app, ApplicationRole.PROVIDER,
                    new RpcEndpoint("127.0.0.1", 19000), 100, manifest.revision(), Map.of());
            discovery.add(instance);
            ServiceMappingOwner owner = mapping.claim(app, instanceId).toCompletableFuture().join();
            mapping.publish(owner, Set.of(SERVICE)).toCompletableFuture().join();
            return instance;
        }

        private void addConsumer(String appName, String instanceId) {
            ApplicationKey app = new ApplicationKey("production", appName);
            discovery.add(new ApplicationInstance(instanceId, app, ApplicationRole.CONSUMER,
                    null, 0, null, Map.of()));
        }

        private void close() {
            resolver.close();
            mappingCache.close();
            manifestCache.close();
        }
    }

    private static final class FakeDiscovery implements ApplicationDiscovery {
        private final Map<ApplicationKey, List<ApplicationInstance>> values = new ConcurrentHashMap<>();
        private final AtomicInteger calls = new AtomicInteger();
        private volatile CompletableFuture<ApplicationDirectorySnapshot> hold;
        private volatile boolean fail;

        private void add(ApplicationInstance instance) {
            values.compute(instance.application(), (app, old) -> {
                List<ApplicationInstance> updated = old == null ? new ArrayList<>() : new ArrayList<>(old);
                updated.add(instance);
                return List.copyOf(updated);
            });
        }

        private ApplicationDirectorySnapshot direct(String name) {
            ApplicationKey app = new ApplicationKey("production", name);
            return new ApplicationDirectorySnapshot(app, values.getOrDefault(app, List.of()), 1);
        }

        @Override
        public CompletionStage<ApplicationDirectorySnapshot> lookup(ApplicationKey application) {
            calls.incrementAndGet();
            if (fail) {
                return CompletableFuture.failedFuture(new IllegalStateException("Registry unavailable"));
            }
            if (hold != null) {
                return hold;
            }
            return CompletableFuture.completedFuture(
                    new ApplicationDirectorySnapshot(application, values.getOrDefault(application, List.of()), 1));
        }

        @Override
        public RegistrySubscription subscribe(ApplicationKey application, ApplicationRegistryListener listener) {
            return () -> { };
        }
    }

    private static final class FakeManifests implements ApplicationManifestRepository {
        private final Map<String, ApplicationManifest> data = new ConcurrentHashMap<>();
        private final AtomicInteger calls = new AtomicInteger();

        @Override
        public CompletionStage<Void> publish(ApplicationManifest manifest) {
            data.put(manifest.revision(), manifest);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletionStage<Optional<ApplicationManifest>> lookup(ApplicationKey application, String revision) {
            calls.incrementAndGet();
            return CompletableFuture.completedFuture(Optional.ofNullable(data.get(revision)));
        }
    }
}
