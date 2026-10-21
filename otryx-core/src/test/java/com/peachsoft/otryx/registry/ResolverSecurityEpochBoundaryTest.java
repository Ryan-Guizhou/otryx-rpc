package com.peachsoft.otryx.registry;

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
import com.peachsoft.otryx.registry.memory.MemoryApplicationRegistry;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import org.junit.jupiter.api.Test;

/**
 * 用旧公共 API 证明安全失效必须跨服务传播并阻断迟到发布。
 *
 * <p>错误归属 Directory 是可构造的真实 SPI 响应，不使用未来异常类型或新 guard。
 *
 * @Author OpenAI
 * @Version 1.0.0-SNAPSHOT
 * @CreateTime 2026/10/19 11:28
 */
class ResolverSecurityEpochBoundaryTest {
    private static final String SCHEMA = "a".repeat(64);
    private static final ApplicationKey APP_A = new ApplicationKey("security-test", "a");
    private static final ApplicationKey APP_B = new ApplicationKey("security-test", "b");
    private static final ServiceMappingKey KEY_A =
            new ServiceMappingKey("security-test", new ServiceKey("demo.A", "1", "default"));
    private static final ServiceMappingKey KEY_B =
            new ServiceMappingKey("security-test", new ServiceKey("demo.B", "1", "default"));

    @Test
    void wrongApplicationResponseInvalidatesOtherRouteWithoutRefreshingIt() {
        try (Fixture fixture = new Fixture()) {
            fixture.start();
            fixture.corruptA = true;
            assertThrows(CompletionException.class,
                    () -> fixture.resolver.refresh(KEY_A, SCHEMA).toCompletableFuture().join());
            assertTrue(fixture.resolver.current(KEY_B, SCHEMA).isEmpty());
        }
    }

    @Test
    void oldPendingOtherRouteCannotPublishAfterSecurityFailure() {
        try (Fixture fixture = new Fixture()) {
            fixture.start();
            fixture.heldB = new CompletableFuture<>();
            CompletableFuture<VerifiedProviderSnapshot> old =
                    fixture.resolver.refresh(KEY_B, SCHEMA).toCompletableFuture();
            fixture.corruptA = true;
            assertThrows(CompletionException.class,
                    () -> fixture.resolver.refresh(KEY_A, SCHEMA).toCompletableFuture().join());
            fixture.heldB.complete(fixture.memory.lookup(APP_B).toCompletableFuture().join());
            assertThrows(CompletionException.class, old::join);
            assertTrue(fixture.resolver.current(KEY_B, SCHEMA).isEmpty());
        }
    }

    @Test
    void oneRouteRecoveryCannotRestoreOtherRoutesOldSnapshot() {
        try (Fixture fixture = new Fixture()) {
            fixture.start();
            fixture.corruptA = true;
            assertThrows(CompletionException.class,
                    () -> fixture.resolver.refresh(KEY_A, SCHEMA).toCompletableFuture().join());
            fixture.corruptA = false;
            fixture.resolver.refresh(KEY_A, SCHEMA).toCompletableFuture().join();
            assertTrue(fixture.resolver.current(KEY_A, SCHEMA).isPresent());
            assertTrue(fixture.resolver.current(KEY_B, SCHEMA).isEmpty());
        }
    }

    private static final class Fixture implements AutoCloseable {
        private final MemoryApplicationRegistry memory = new MemoryApplicationRegistry();
        private boolean corruptA;
        private CompletableFuture<ApplicationDirectorySnapshot> heldB;
        private final ApplicationDiscovery discovery = new ApplicationDiscovery() {
            @Override
            public CompletionStage<ApplicationDirectorySnapshot> lookup(ApplicationKey application) {
                if (APP_A.equals(application) && corruptA) {
                    return CompletableFuture.completedFuture(new ApplicationDirectorySnapshot(APP_B, List.of(), 5));
                }
                if (APP_B.equals(application) && heldB != null) {
                    return heldB;
                }
                return memory.lookup(application);
            }

            @Override
            public RegistrySubscription subscribe(ApplicationKey application, ApplicationRegistryListener listener) {
                return memory.subscribe(application, listener);
            }
        };
        private final ServiceMappingCache mappings =
                new ServiceMappingCache(memory.serviceMappingRepository(), Duration.ofSeconds(10), Duration.ZERO);
        private final ManifestSnapshotCache manifests = new ManifestSnapshotCache(memory.manifestRepository());
        private final ApplicationServiceResolver resolver =
                new ApplicationServiceResolver(mappings, discovery, manifests, Duration.ofSeconds(10));

        private void start() {
            provider(APP_A, KEY_A.serviceKey(), 19090);
            provider(APP_B, KEY_B.serviceKey(), 19091);
            resolver.refresh(KEY_A, SCHEMA).toCompletableFuture().join();
            resolver.refresh(KEY_B, SCHEMA).toCompletableFuture().join();
        }

        private void provider(ApplicationKey application, ServiceKey service, int port) {
            ApplicationManifest manifest = ApplicationManifest.create(application, List.of(
                    new ExportedService(service, RpcIds.serviceId(service), Set.of(1), SCHEMA)));
            memory.manifestRepository().publish(manifest).toCompletableFuture().join();
            ApplicationInstance instance = new ApplicationInstance("provider", application, ApplicationRole.PROVIDER,
                    new RpcEndpoint("127.0.0.1", port), 100, manifest.revision(), Map.of());
            memory.register(instance).toCompletableFuture().join();
            ServiceMappingOwner owner = memory.serviceMappingRepository()
                    .claim(application, "provider").toCompletableFuture().join();
            memory.serviceMappingRepository().publish(owner, Set.of(service)).toCompletableFuture().join();
        }

        @Override
        public void close() {
            resolver.close();
            manifests.close();
            mappings.close();
            memory.close();
        }
    }
}
