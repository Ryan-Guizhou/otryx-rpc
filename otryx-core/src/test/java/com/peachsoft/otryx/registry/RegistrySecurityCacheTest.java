package com.peachsoft.otryx.registry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.peachsoft.otryx.api.ApplicationKey;
import com.peachsoft.otryx.api.ApplicationManifest;
import com.peachsoft.otryx.api.ExportedService;
import com.peachsoft.otryx.api.RpcIds;
import com.peachsoft.otryx.api.ServiceKey;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

/**
 * 验证安全失效清缓存而不清 revision 下限，并保持取消和完成的单赢家语义。
 *
 * @Author OpenAI
 * @Version 1.0.0-SNAPSHOT
 * @CreateTime 2026/10/19 11:34
 */
class RegistrySecurityCacheTest {
    private static final ApplicationKey APP = new ApplicationKey("security-test", "provider");
    private static final ServiceKey SERVICE = new ServiceKey("demo.Api", "1", "default");
    private static final ServiceMappingKey KEY = new ServiceMappingKey(APP.namespace(), SERVICE);
    private static final ApplicationManifest MANIFEST = ApplicationManifest.create(APP, List.of(
            new ExportedService(SERVICE, RpcIds.serviceId(SERVICE), Set.of(1), "a".repeat(64))));

    @Test
    void securityInvalidationClearsBothPositiveAndNegativeManifestEntries() {
        MutableManifests source = new MutableManifests();
        source.lookup = revision -> CompletableFuture.completedFuture(
                revision.equals(MANIFEST.revision()) ? Optional.of(MANIFEST) : Optional.empty());
        String absent = "sha256:" + "b".repeat(64);
        try (ManifestSnapshotCache cache = new ManifestSnapshotCache(source)) {
            assertTrue(cache.lookup(APP, MANIFEST.revision()).toCompletableFuture().join().isPresent());
            assertTrue(cache.lookup(APP, absent).toCompletableFuture().join().isEmpty());
            cache.lookup(APP, MANIFEST.revision()).toCompletableFuture().join();
            cache.lookup(APP, absent).toCompletableFuture().join();
            assertEquals(2, source.reads.get());
            cache.invalidateForSecurity(RegistrySecurityFailure.AUTHORIZATION_REJECTED);
            cache.lookup(APP, MANIFEST.revision()).toCompletableFuture().join();
            cache.lookup(APP, absent).toCompletableFuture().join();
            assertEquals(4, source.reads.get());
        }
    }

    @Test
    void latePositiveAndNegativeManifestResultsCannotRefillInvalidatedGeneration() {
        for (boolean latePresent : List.of(true, false)) {
            MutableManifests source = new MutableManifests();
            UncancellableFuture<Optional<ApplicationManifest>> late = new UncancellableFuture<>();
            source.lookup = ignored -> late;
            try (ManifestSnapshotCache cache = new ManifestSnapshotCache(source)) {
                CompletableFuture<Optional<ApplicationManifest>> old =
                        cache.lookup(APP, MANIFEST.revision()).toCompletableFuture();
                cache.invalidateForSecurity(RegistrySecurityFailure.AUTHORIZATION_REJECTED);
                assertThrows(CompletionException.class, old::join);
                Optional<ApplicationManifest> fresh = latePresent ? Optional.empty() : Optional.of(MANIFEST);
                source.lookup = ignored -> CompletableFuture.completedFuture(fresh);
                assertEquals(fresh, cache.lookup(APP, MANIFEST.revision()).toCompletableFuture().join());
                late.complete(latePresent ? Optional.of(MANIFEST) : Optional.empty());
                assertEquals(fresh, cache.lookup(APP, MANIFEST.revision()).toCompletableFuture().join());
                assertEquals(2, source.reads.get());
            }
        }
    }

    @Test
    void mappingSecurityInvalidationPreservesPreviouslyVerifiedRevisionFloor() {
        MutableMapping source = new MutableMapping();
        try (ServiceMappingCache cache = new ServiceMappingCache(source, Duration.ofSeconds(10), Duration.ZERO)) {
            assertEquals(10, cache.refresh(KEY).toCompletableFuture().join().revision());
            cache.invalidateForSecurity(RegistrySecurityFailure.AUTHORIZATION_REJECTED);
            assertTrue(cache.current(KEY).isEmpty());
            source.revision = 9;
            CompletionException failure = assertThrows(CompletionException.class,
                    () -> cache.refresh(KEY).toCompletableFuture().join());
            assertEquals(RegistrySecurityFailure.METADATA_INTEGRITY_REJECTED,
                    ((RegistrySecurityException) failure.getCause()).reason());
            assertTrue(cache.current(KEY).isEmpty());
            source.revision = 10;
            assertEquals(10, cache.refresh(KEY).toCompletableFuture().join().revision());
        }
    }

    @Test
    void cancellationReturnsManifestCapacityBeforeLateSourcesComplete() {
        MutableManifests source = new MutableManifests();
        List<UncancellableFuture<Optional<ApplicationManifest>>> cancelled = new ArrayList<>();
        try (ManifestSnapshotCache cache = new ManifestSnapshotCache(source)) {
            for (int index = 0; index < 64; index++) {
                UncancellableFuture<Optional<ApplicationManifest>> pending = new UncancellableFuture<>();
                source.lookup = ignored -> pending;
                cancelled.add(pending);
                CompletableFuture<Optional<ApplicationManifest>> request =
                        cache.lookup(APP, MANIFEST.revision()).toCompletableFuture();
                assertFalse(request.isDone());
                assertTrue(request.cancel(false));
            }
            UncancellableFuture<Optional<ApplicationManifest>> latest = new UncancellableFuture<>();
            source.lookup = ignored -> latest;
            CompletableFuture<Optional<ApplicationManifest>> current =
                    cache.lookup(APP, MANIFEST.revision()).toCompletableFuture();
            cancelled.forEach(future -> future.complete(Optional.empty()));
            assertFalse(current.isDone());
            assertFalse(current.complete(Optional.empty()));
            latest.complete(Optional.of(MANIFEST));
            assertEquals(Optional.of(MANIFEST), current.join());
        }
    }

    @Test
    void mappingPublishesCurrentBeforeDependentCompletionCallbacks() {
        MutableMapping source = new MutableMapping();
        try (ServiceMappingCache cache = new ServiceMappingCache(source, Duration.ofSeconds(10), Duration.ZERO)) {
            cache.refresh(KEY).thenAccept(snapshot -> assertSame(snapshot, cache.current(KEY).orElseThrow()))
                    .toCompletableFuture().join();
        }
    }

    private static final class MutableManifests implements ApplicationManifestRepository {
        private final AtomicInteger reads = new AtomicInteger();
        private Function<String, CompletionStage<Optional<ApplicationManifest>>> lookup;

        @Override
        public CompletionStage<Void> publish(ApplicationManifest manifest) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletionStage<Optional<ApplicationManifest>> lookup(ApplicationKey application, String revision) {
            reads.incrementAndGet();
            return lookup.apply(revision);
        }

        @Override
        public CompletionStage<Optional<ApplicationManifest>> lookupVerified(
                ApplicationKey application, String revision) {
            return lookup(application, revision);
        }
    }

    private static final class MutableMapping implements ServiceMappingRepository {
        private long revision = 10;

        @Override
        public CompletionStage<ServiceMappingOwner> claim(ApplicationKey application, String instanceId) {
            return CompletableFuture.completedFuture(new ServiceMappingOwner(application, instanceId, 1));
        }

        @Override
        public CompletionStage<Void> publish(ServiceMappingOwner owner, Set<ServiceKey> services) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletionStage<Void> revoke(ServiceMappingOwner owner) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletionStage<ServiceMappingSnapshot> lookup(ServiceMappingKey key) {
            return CompletableFuture.completedFuture(new ServiceMappingSnapshot(key, Set.of(APP), revision));
        }
    }

    private static final class UncancellableFuture<T> extends CompletableFuture<T> {
        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            return false;
        }
    }
}
