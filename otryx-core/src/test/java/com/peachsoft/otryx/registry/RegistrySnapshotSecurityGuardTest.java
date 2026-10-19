package com.peachsoft.otryx.registry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * 验证候选私有来源、每服务恢复及无需 IO 的安全 epoch 复核。
 *
 * @Author OpenAI
 * @Version 1.0.0-SNAPSHOT
 * @CreateTime 2026/10/19 11:32
 */
class RegistrySnapshotSecurityGuardTest {
    private static final String SCHEMA = "a".repeat(64);
    private static final ApplicationKey APP_A = new ApplicationKey("security-test", "a");
    private static final ApplicationKey APP_B = new ApplicationKey("security-test", "b");
    private static final ServiceMappingKey KEY_A =
            new ServiceMappingKey("security-test", new ServiceKey("demo.A", "1", "default"));
    private static final ServiceMappingKey KEY_B =
            new ServiceMappingKey("security-test", new ServiceKey("demo.B", "1", "default"));
    private static final ServiceMappingKey UNSEEN =
            new ServiceMappingKey("security-test", new ServiceKey("demo.Unseen", "1", "default"));

    @Test
    void valueEquivalentPublicAndForeignCandidatesHaveNoAuthority() {
        try (Fixture fixture = new Fixture(); Fixture foreign = new Fixture()) {
            VerifiedProviderSnapshot trusted = fixture.refresh(KEY_B);
            VerifiedProviderSnapshot copied = new VerifiedProviderSnapshot(trusted.key(), trusted.schemaFingerprint(),
                    trusted.providers(), trusted.mappingRevision());
            assertEquals(trusted, copied);
            assertEquals(trusted.hashCode(), copied.hashCode());
            assertTrue(fixture.resolver.isSnapshotUsable(KEY_B, SCHEMA, trusted));
            assertReason(RegistrySecurityFailure.METADATA_INTEGRITY_REJECTED,
                    () -> fixture.resolver.isSnapshotUsable(KEY_B, SCHEMA, copied));
            assertReason(RegistrySecurityFailure.METADATA_INTEGRITY_REJECTED,
                    () -> fixture.resolver.isSnapshotUsable(KEY_B, SCHEMA, foreign.refresh(KEY_B)));
        }
    }

    @Test
    void ordinaryReplacementIsStaleButPendingRefreshKeepsCurrentCandidateUsable() {
        try (Fixture fixture = new Fixture()) {
            VerifiedProviderSnapshot old = fixture.refresh(KEY_B);
            VerifiedProviderSnapshot current = fixture.refresh(KEY_B);
            assertNotSame(old, current);
            assertFalse(fixture.resolver.isSnapshotUsable(KEY_B, SCHEMA, old));
            fixture.heldB = new CompletableFuture<>();
            CompletableFuture<VerifiedProviderSnapshot> pending =
                    fixture.resolver.refresh(KEY_B, SCHEMA).toCompletableFuture();
            assertTrue(fixture.resolver.isSnapshotUsable(KEY_B, SCHEMA, current));
            assertTrue(pending.cancel(false));
            assertTrue(fixture.resolver.isSnapshotUsable(KEY_B, SCHEMA, current));
        }
    }

    @ParameterizedTest
    @EnumSource(RegistrySecurityFailure.class)
    void everySecurityReasonBlocksOtherCandidateAndNullWithoutIo(RegistrySecurityFailure reason) {
        try (Fixture fixture = new Fixture()) {
            fixture.refresh(KEY_A);
            VerifiedProviderSnapshot oldB = fixture.refresh(KEY_B);
            fixture.failureA = new RegistrySecurityException(reason);
            assertThrows(CompletionException.class, () -> fixture.refresh(KEY_A));
            int reads = fixture.discoveryReads.get();
            assertReason(reason, () -> fixture.resolver.isSnapshotUsable(KEY_B, SCHEMA, oldB));
            assertReason(reason, () -> fixture.resolver.isSnapshotUsable(KEY_B, SCHEMA, null));
            assertReason(reason, () -> fixture.resolver.isSnapshotUsable(UNSEEN, SCHEMA, null));
            assertEquals(ApplicationServiceResolver.Status.SECURITY_REJECTED,
                    fixture.resolver.status(KEY_B, SCHEMA));
            assertTrue(fixture.resolver.current(KEY_B, SCHEMA).isEmpty());
            assertEquals(reads, fixture.discoveryReads.get());
        }
    }

    @Test
    void recoveryNeverReauthorizesAnyOldEpochCandidate() {
        try (Fixture fixture = new Fixture()) {
            VerifiedProviderSnapshot oldA = fixture.refresh(KEY_A);
            VerifiedProviderSnapshot oldB = fixture.refresh(KEY_B);
            fixture.failureA = new RegistrySecurityException(RegistrySecurityFailure.AUTHORIZATION_REJECTED);
            assertThrows(CompletionException.class, () -> fixture.refresh(KEY_A));
            fixture.failureA = null;
            VerifiedProviderSnapshot newA = fixture.refresh(KEY_A);
            assertTrue(fixture.resolver.isSnapshotUsable(KEY_A, SCHEMA, newA));
            assertReason(RegistrySecurityFailure.AUTHORIZATION_REJECTED,
                    () -> fixture.resolver.isSnapshotUsable(KEY_B, SCHEMA, oldB));
            VerifiedProviderSnapshot newB = fixture.refresh(KEY_B);
            assertTrue(fixture.resolver.isSnapshotUsable(KEY_B, SCHEMA, newB));
            assertReason(RegistrySecurityFailure.AUTHORIZATION_REJECTED,
                    () -> fixture.resolver.isSnapshotUsable(KEY_B, SCHEMA, oldB));
            assertReason(RegistrySecurityFailure.AUTHORIZATION_REJECTED,
                    () -> fixture.resolver.isSnapshotUsable(KEY_A, SCHEMA, oldA));
        }
    }

    @Test
    void ordinaryNetworkFailureRetainsFiniteLkgWithoutInvalidatingAnotherRoute() {
        try (Fixture fixture = new Fixture()) {
            VerifiedProviderSnapshot a = fixture.refresh(KEY_A);
            VerifiedProviderSnapshot b = fixture.refresh(KEY_B);
            fixture.failureA = new IllegalStateException("Temporary unavailable");
            assertThrows(CompletionException.class, () -> fixture.refresh(KEY_A));
            assertTrue(fixture.resolver.isSnapshotUsable(KEY_A, SCHEMA, a));
            assertTrue(fixture.resolver.isSnapshotUsable(KEY_B, SCHEMA, b));
            fixture.clock.addAndGet(Duration.ofSeconds(10).toNanos());
            assertFalse(fixture.resolver.isSnapshotUsable(KEY_A, SCHEMA, a));
            assertTrue(fixture.resolver.isSnapshotUsable(KEY_B, SCHEMA, b));
        }
    }

    @Test
    void nullableNoSnapshotIsNotReadyAndClosedAlwaysReturnsFalse() {
        try (Fixture fixture = new Fixture()) {
            assertFalse(fixture.resolver.isSnapshotUsable(KEY_B, SCHEMA, null));
            VerifiedProviderSnapshot b = fixture.refresh(KEY_B);
            fixture.failureA = new RegistrySecurityException(RegistrySecurityFailure.AUTHORIZATION_REJECTED);
            assertThrows(CompletionException.class, () -> fixture.refresh(KEY_A));
            fixture.resolver.close();
            assertFalse(fixture.resolver.isSnapshotUsable(KEY_B, SCHEMA, b));
            assertFalse(fixture.resolver.isSnapshotUsable(KEY_B, SCHEMA, null));
        }
    }

    @Test
    void publicFutureCompletionCannotMintAuthorityBeforeSourceValidation() {
        try (Fixture fixture = new Fixture()) {
            fixture.heldB = new CompletableFuture<>();
            CompletableFuture<VerifiedProviderSnapshot> pending =
                    fixture.resolver.refresh(KEY_B, SCHEMA).toCompletableFuture();
            VerifiedProviderSnapshot forged = new VerifiedProviderSnapshot(KEY_B, SCHEMA, List.of(), 999);
            assertFalse(pending.complete(forged));
            assertFalse(pending.isDone());
            assertTrue(fixture.resolver.current(KEY_B, SCHEMA).isEmpty());
            fixture.heldB.complete(fixture.memory.lookup(APP_B).toCompletableFuture().join());
            assertTrue(fixture.resolver.isSnapshotUsable(KEY_B, SCHEMA, pending.join()));
        }
    }

    @Test
    void candidateRouteAndSchemaMustMatchRequestedGuard() {
        try (Fixture fixture = new Fixture()) {
            VerifiedProviderSnapshot b = fixture.refresh(KEY_B);
            assertReason(RegistrySecurityFailure.METADATA_REFERENCE_MISMATCH,
                    () -> fixture.resolver.isSnapshotUsable(KEY_A, SCHEMA, b));
            assertReason(RegistrySecurityFailure.METADATA_REFERENCE_MISMATCH,
                    () -> fixture.resolver.isSnapshotUsable(KEY_B, "b".repeat(64), b));
        }
    }

    private static void assertReason(RegistrySecurityFailure expected, Runnable operation) {
        RegistrySecurityException rejected = assertThrows(RegistrySecurityException.class, operation::run);
        assertEquals(expected, rejected.reason());
        assertEquals(null, rejected.getCause());
        assertEquals(0, rejected.getSuppressed().length);
    }

    private static final class Fixture implements AutoCloseable {
        private final MemoryApplicationRegistry memory = new MemoryApplicationRegistry();
        private final AtomicLong clock = new AtomicLong(100);
        private final AtomicInteger discoveryReads = new AtomicInteger();
        private RuntimeException failureA;
        private CompletableFuture<ApplicationDirectorySnapshot> heldB;
        private final ApplicationDiscovery discovery = new ApplicationDiscovery() {
            @Override
            public CompletionStage<ApplicationDirectorySnapshot> lookup(ApplicationKey application) {
                discoveryReads.incrementAndGet();
                if (APP_A.equals(application) && failureA != null) {
                    return CompletableFuture.failedFuture(failureA);
                }
                return APP_B.equals(application) && heldB != null ? heldB : memory.lookup(application);
            }

            @Override
            public RegistrySubscription subscribe(ApplicationKey application, ApplicationRegistryListener listener) {
                return memory.subscribe(application, listener);
            }
        };
        private final ServiceMappingCache mappings = new ServiceMappingCache(
                memory.serviceMappingRepository(), Duration.ofSeconds(10), Duration.ZERO, clock::get);
        private final ManifestSnapshotCache manifests = new ManifestSnapshotCache(
                memory.manifestRepository(), clock::get);
        private final ApplicationServiceResolver resolver =
                new ApplicationServiceResolver(mappings, discovery, manifests, Duration.ofSeconds(10), clock::get);

        private Fixture() {
            provider(APP_A, KEY_A.serviceKey(), 19090);
            provider(APP_B, KEY_B.serviceKey(), 19091);
        }

        private VerifiedProviderSnapshot refresh(ServiceMappingKey key) {
            return resolver.refresh(key, SCHEMA).toCompletableFuture().join();
        }

        private void provider(ApplicationKey application, ServiceKey service, int port) {
            ApplicationManifest manifest = ApplicationManifest.create(application, List.of(
                    new ExportedService(service, RpcIds.serviceId(service), Set.of(1), SCHEMA)));
            memory.manifestRepository().publish(manifest).toCompletableFuture().join();
            memory.register(new ApplicationInstance("provider", application, ApplicationRole.PROVIDER,
                    new RpcEndpoint("127.0.0.1", port), 100, manifest.revision(), Map.of()))
                    .toCompletableFuture().join();
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
