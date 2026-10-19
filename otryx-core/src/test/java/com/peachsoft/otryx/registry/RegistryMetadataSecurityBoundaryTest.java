package com.peachsoft.otryx.registry;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.peachsoft.otryx.api.ApplicationKey;
import com.peachsoft.otryx.api.ApplicationManifest;
import com.peachsoft.otryx.api.ExportedService;
import com.peachsoft.otryx.api.RpcIds;
import com.peachsoft.otryx.api.ServiceKey;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import org.junit.jupiter.api.Test;

/**
 * 在缓存真实接受点验证不可信 SPI 响应，且禁止配置描述泄漏未知值。
 *
 * <p>这些回归仅使用既有公共 API，可在安全实现前的准确主干上证明原始行为缺口。
 *
 * @Author OpenAI
 * @Version 1.0.0-SNAPSHOT
 * @CreateTime 2026/10/19 11:20
 */
class RegistryMetadataSecurityBoundaryTest {

    private static final ApplicationKey APPLICATION = new ApplicationKey("security-test", "provider");
    private static final ServiceMappingKey KEY =
            new ServiceMappingKey("security-test", new ServiceKey("demo.Api", "1", "default"));

    @Test
    void rejectsManifestFromAnotherApplicationDespiteVerifiedOverride() {
        ApplicationManifest expected = manifest(APPLICATION, "1");
        ApplicationManifest foreign = manifest(new ApplicationKey("security-test", "foreign"), "1");
        try (ManifestSnapshotCache cache = new ManifestSnapshotCache(manifestsReturning(foreign))) {
            assertThrows(CompletionException.class, () ->
                    cache.lookup(APPLICATION, expected.revision()).toCompletableFuture().join());
        }
    }

    @Test
    void rejectsManifestFromAnotherRevisionDespiteVerifiedOverride() {
        ApplicationManifest expected = manifest(APPLICATION, "1");
        ApplicationManifest foreign = manifest(APPLICATION, "2");
        try (ManifestSnapshotCache cache = new ManifestSnapshotCache(manifestsReturning(foreign))) {
            assertThrows(CompletionException.class, () ->
                    cache.lookup(APPLICATION, expected.revision()).toCompletableFuture().join());
        }
    }

    @Test
    void rejectsWrongMappingKeyDespiteVerifiedOverride() {
        ServiceMappingKey foreign =
                new ServiceMappingKey("security-test", new ServiceKey("demo.Other", "1", "default"));
        ServiceMappingRepository repository = mappingsReturning(new ServiceMappingSnapshot(
                foreign, Set.of(APPLICATION), 7));
        try (ServiceMappingCache cache = new ServiceMappingCache(repository, Duration.ofSeconds(10), Duration.ZERO)) {
            assertThrows(CompletionException.class, () -> cache.refresh(KEY).toCompletableFuture().join());
            assertTrue(cache.current(KEY).isEmpty());
        }
    }

    @Test
    void optionsNeverRenderEndpointQueryOrFragments() {
        String querySecret = "query-secret-for-regression";
        String fragmentSecret = "fragment-secret-for-regression";
        RegistryOptions options = new RegistryOptions(
                List.of("https://localhost:2379/private?access=" + querySecret + "#" + fragmentSecret),
                "security-test", Map.of());
        assertFalse(options.toString().contains(querySecret));
        assertFalse(options.toString().contains(fragmentSecret));
        assertFalse(options.toString().contains("/private"));
    }

    @Test
    void optionsNeverRenderUnclassifiedOrNestedProviderValues() {
        String unclassifiedSecret = "unclassified-secret-for-regression";
        String nestedSecret = "nested-secret-for-regression";
        RegistryOptions options = new RegistryOptions(List.of("http://localhost:8761"), "security-test",
                Map.of("apiKey", unclassifiedSecret,
                        "eurekaMetadataEndpoints", "https://user:" + nestedSecret + "@localhost:2379"));
        assertFalse(options.toString().contains(unclassifiedSecret));
        assertFalse(options.toString().contains(nestedSecret));
    }

    private static ApplicationManifest manifest(ApplicationKey application, String version) {
        ServiceKey service = new ServiceKey("demo.Api", version, "default");
        return ApplicationManifest.create(application, List.of(new ExportedService(
                service, RpcIds.serviceId(service), Set.of(1), "a".repeat(64))));
    }

    private static ApplicationManifestRepository manifestsReturning(ApplicationManifest value) {
        return new ApplicationManifestRepository() {
            @Override
            public CompletionStage<Void> publish(ApplicationManifest manifest) {
                return CompletableFuture.failedFuture(new UnsupportedOperationException("Read-only test source"));
            }

            @Override
            public CompletionStage<Optional<ApplicationManifest>> lookup(ApplicationKey application, String revision) {
                return CompletableFuture.completedFuture(Optional.of(value));
            }

            @Override
            public CompletionStage<Optional<ApplicationManifest>> lookupVerified(
                    ApplicationKey application, String revision) {
                return lookup(application, revision);
            }
        };
    }

    private static ServiceMappingRepository mappingsReturning(ServiceMappingSnapshot value) {
        return new ServiceMappingRepository() {
            @Override
            public CompletionStage<ServiceMappingOwner> claim(ApplicationKey application, String instanceId) {
                return CompletableFuture.failedFuture(new UnsupportedOperationException("Read-only test source"));
            }

            @Override
            public CompletionStage<Void> publish(ServiceMappingOwner owner, Set<ServiceKey> services) {
                return CompletableFuture.failedFuture(new UnsupportedOperationException("Read-only test source"));
            }

            @Override
            public CompletionStage<Void> revoke(ServiceMappingOwner owner) {
                return CompletableFuture.failedFuture(new UnsupportedOperationException("Read-only test source"));
            }

            @Override
            public CompletionStage<ServiceMappingSnapshot> lookup(ServiceMappingKey key) {
                return CompletableFuture.completedFuture(value);
            }

            @Override
            public CompletionStage<ServiceMappingSnapshot> lookupVerified(ServiceMappingKey key) {
                return lookup(key);
            }
        };
    }
}
