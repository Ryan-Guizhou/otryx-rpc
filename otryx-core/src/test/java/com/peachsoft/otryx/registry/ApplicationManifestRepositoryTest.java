package com.peachsoft.otryx.registry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.peachsoft.otryx.api.ApplicationKey;
import com.peachsoft.otryx.api.ApplicationManifest;
import com.peachsoft.otryx.api.ApplicationManifestCodec;
import com.peachsoft.otryx.api.ExportedService;
import com.peachsoft.otryx.api.RpcIds;
import com.peachsoft.otryx.api.ServiceKey;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletionException;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/**
 * 验证 Manifest Repository 的不可变发布、并发幂等和可信查找合同。
 *
 * <p>本测试使用本地测试仓库验证 SPI；真实 Adapter 的持久化/GC 由后续 Issue 实施。
 *
 * @Author OpenAI
 * @Version 1.0.0-SNAPSHOT
 * @CreateTime 2026/10/15 17:48
 */
class ApplicationManifestRepositoryTest {

    private static final ApplicationKey APPLICATION = new ApplicationKey("default", "payments");

    @Test
    void shouldStoreMultipleRevisionsAndReadIndependently() {
        ApplicationManifestRepository repository = new TestRepository();
        ApplicationManifest a = manifest("1.0.0");
        ApplicationManifest b = manifest("2.0.0");
        repository.publish(a).toCompletableFuture().join();
        repository.publish(b).toCompletableFuture().join();
        assertEquals(a, repository.lookupVerified(APPLICATION, a.revision())
                .toCompletableFuture().join().orElseThrow());
        assertEquals(b, repository.lookupVerified(APPLICATION, b.revision())
                .toCompletableFuture().join().orElseThrow());
        assertFalse(repository.lookupVerified(
                        new ApplicationKey("another", "payments"), a.revision())
                .toCompletableFuture().join().isPresent());
        assertEquals(2, ((TestRepository) repository).size());
    }

    @Test
    void sameRevisionShouldBeConcurrentIdempotent() {
        ApplicationManifestRepository repository = new TestRepository();
        ApplicationManifest manifest = manifest("1.0.0");
        CompletableFuture<?>[] operations = IntStream.range(0, 64)
                .mapToObj(ignored -> CompletableFuture.runAsync(() ->
                        repository.publish(manifest).toCompletableFuture().join()))
                .toArray(CompletableFuture[]::new);
        CompletableFuture.allOf(operations).join();
        assertEquals(1, ((TestRepository) repository).size());
        assertTrue(repository.lookupVerified(APPLICATION, manifest.revision())
                .toCompletableFuture().join().isPresent());
    }

    @Test
    void lookupVerifiedShouldFailClosedOnWrongApplicationOrRevision() {
        ApplicationManifest expected = manifest("1.0.0");
        ApplicationManifest unexpected = manifest("2.0.0");
        ApplicationManifestRepository corrupt = new TestRepository() {
            @Override
            public CompletionStage<Optional<ApplicationManifest>> lookup(
                    ApplicationKey application, String revision) {
                return CompletableFuture.completedFuture(Optional.of(unexpected));
            }
        };
        assertThrows(CompletionException.class, () ->
                corrupt.lookupVerified(APPLICATION, expected.revision()).toCompletableFuture().join());
        assertThrows(IllegalArgumentException.class, () ->
                corrupt.lookupVerified(APPLICATION, "not-a-revision"));
    }

    @Test
    void shouldTreatUnknownRevisionAsAbsentRatherThanUsingAnotherVersion() {
        ApplicationManifestRepository repository = new TestRepository();
        ApplicationManifest valid = manifest("1.0.0");
        repository.publish(valid).toCompletableFuture().join();
        ApplicationManifest unrelated = manifest("2.0.0");
        assertTrue(repository.lookupVerified(APPLICATION, unrelated.revision())
                .toCompletableFuture().join().isEmpty());
        assertEquals(1, ((TestRepository) repository).size());
    }

    private static ApplicationManifest manifest(String version) {
        ServiceKey key = new ServiceKey("demo.Payments", version, "default");
        ExportedService service = new ExportedService(
                key, RpcIds.serviceId(key), Set.of(1), "b".repeat(64));
        return ApplicationManifest.create(APPLICATION, List.of(service));
    }

    private static class TestRepository implements ApplicationManifestRepository {

        private final Map<String, byte[]> manifests = new ConcurrentHashMap<>();

        @Override
        public CompletionStage<Void> publish(ApplicationManifest manifest) {
            String key = key(manifest.application(), manifest.revision());
            manifests.compute(key, (ignored, existing) -> {
                byte[] candidate = manifest.canonicalBytes();
                if (existing != null && !java.util.Arrays.equals(existing, candidate)) {
                    throw new IllegalArgumentException("Immutable Manifest key collision");
                }
                return existing == null ? candidate : existing;
            });
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletionStage<Optional<ApplicationManifest>> lookup(
                ApplicationKey application, String revision) {
            byte[] stored = manifests.get(key(application, revision));
            return CompletableFuture.completedFuture(Optional.ofNullable(stored)
                    .map(ApplicationManifestCodec::decode));
        }

        int size() {
            return manifests.size();
        }

        private static String key(ApplicationKey application, String revision) {
            return application.namespace() + "/" + application.applicationName() + "/" + revision;
        }
    }
}
