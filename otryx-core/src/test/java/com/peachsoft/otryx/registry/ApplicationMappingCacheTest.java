package com.peachsoft.otryx.registry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.peachsoft.otryx.api.ApplicationKey;
import com.peachsoft.otryx.api.ApplicationManifest;
import com.peachsoft.otryx.api.ServiceKey;
import java.time.Duration;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/**
 * 验证映射与 Manifest 的单飞、短期负缓存、失效窗口及关闭时序。
 *
 * @Author OpenAI
 * @Version 1.0.0-SNAPSHOT
 * @CreateTime 2026/10/15 18:30
 */
class ApplicationMappingCacheTest {

    private static final ApplicationKey APP = new ApplicationKey("test", "backend");
    private static final ServiceMappingKey KEY =
            new ServiceMappingKey("test", new ServiceKey("demo.Api", "1", "default"));
    private static final String REVISION = "sha256:" + "a".repeat(64);

    @Test
    void mappingFailureMustNotReplaceHealthySnapshotWithFakeEmpty() {
        AtomicLong clock = new AtomicLong(100);
        StubMapping repository = new StubMapping();
        ServiceMappingCache cache = new ServiceMappingCache(
                repository, Duration.ofNanos(10), Duration.ZERO, clock::get);
        assertTrue(cache.current(KEY).isEmpty());
        assertTrue(cache.refresh(KEY).toCompletableFuture().join().applications().isEmpty());
        assertTrue(cache.current(KEY).isPresent());
        repository.fail = true;
        assertThrows(CompletionException.class, () -> cache.refresh(KEY).toCompletableFuture().join());
        assertTrue(cache.current(KEY).isPresent());
        clock.addAndGet(10);
        assertTrue(cache.current(KEY).isEmpty());
        cache.close();
    }

    @Test
    void manifestNegativeCacheExpiresAndPendingLookupIsShared() {
        AtomicLong clock = new AtomicLong(100);
        AtomicInteger calls = new AtomicInteger();
        CompletableFuture<Optional<ApplicationManifest>> gate = new CompletableFuture<>();
        ApplicationManifestRepository backend = new ApplicationManifestRepository() {
            @Override
            public CompletionStage<Void> publish(ApplicationManifest manifest) {
                return CompletableFuture.completedFuture(null);
            }

            @Override
            public CompletionStage<Optional<ApplicationManifest>> lookup(
                    ApplicationKey app, String revision) {
                calls.incrementAndGet();
                return gate;
            }
        };
        ManifestSnapshotCache cache = new ManifestSnapshotCache(backend, clock::get);
        CompletableFuture<Optional<ApplicationManifest>> first =
                cache.lookup(APP, REVISION).toCompletableFuture();
        CompletableFuture<Optional<ApplicationManifest>> second =
                cache.lookup(APP, REVISION).toCompletableFuture();
        assertSame(first, second);
        assertEquals(1, calls.get());
        gate.complete(Optional.empty());
        assertTrue(first.join().isEmpty());
        assertTrue(cache.lookup(APP, REVISION).toCompletableFuture().join().isEmpty());
        assertEquals(1, calls.get());
        clock.addAndGet(Duration.ofSeconds(3).toNanos());
        assertTrue(cache.lookup(APP, REVISION).toCompletableFuture().join().isEmpty());
        assertEquals(2, calls.get());
        cache.close();
    }

    @Test
    void closeMustFailPendingMetadataLookupAndBlockNewOnes() {
        CompletableFuture<Optional<ApplicationManifest>> backendResponse = new CompletableFuture<>();
        ApplicationManifestRepository backend = new ApplicationManifestRepository() {
            @Override
            public CompletionStage<Void> publish(ApplicationManifest manifest) {
                return CompletableFuture.completedFuture(null);
            }

            @Override
            public CompletionStage<Optional<ApplicationManifest>> lookup(
                    ApplicationKey app, String revision) {
                return backendResponse;
            }
        };
        ManifestSnapshotCache cache = new ManifestSnapshotCache(backend);
        CompletableFuture<?> pending = cache.lookup(APP, REVISION).toCompletableFuture();
        cache.close();
        assertTrue(pending.isCompletedExceptionally());
        assertThrows(CompletionException.class,
                () -> cache.lookup(APP, REVISION).toCompletableFuture().join());
        backendResponse.complete(Optional.empty());
    }

    private static final class StubMapping implements ServiceMappingRepository {
        private volatile boolean fail;

        @Override
        public CompletionStage<ServiceMappingOwner> claim(ApplicationKey app, String instanceId) {
            return CompletableFuture.completedFuture(new ServiceMappingOwner(app, instanceId, 1));
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
            if (fail) {
                return CompletableFuture.failedFuture(new IllegalStateException("No registry connection"));
            }
            return CompletableFuture.completedFuture(new ServiceMappingSnapshot(key, Set.of(), 1));
        }
    }
}
