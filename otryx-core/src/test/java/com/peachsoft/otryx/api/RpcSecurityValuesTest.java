package com.peachsoft.otryx.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.peachsoft.otryx.observability.RpcFailureCategory;
import com.peachsoft.otryx.observability.RpcFailureClassifier;
import com.peachsoft.otryx.observability.RpcSecurityMode;
import com.peachsoft.otryx.transport.RpcTransportIdentityOptions;
import com.peachsoft.otryx.transport.RpcTransportSecurityOptions;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * 验证有限连接身份、完整路由身份、信任值不可变与凭据脱敏边界。
 *
 * @Author OpenAI
 * @Version 1.0.0-SNAPSHOT
 * @CreateTime 2026/10/19 11:18
 */
class RpcSecurityValuesTest {
    private static final ApplicationKey APP = new ApplicationKey("production", "orders");
    private static final Instant NOW = Instant.parse("2030-01-01T00:00:00Z");
    private static final String FINGERPRINT = "a".repeat(64);

    @Test
    void certificateAndBindingExpiryBothConstrainPeer() {
        RpcPeerIdentity certificateFirst = peer(NOW.plusSeconds(20), NOW.plusSeconds(10), 100L);
        RpcPeerIdentity bindingFirst = peer(NOW.plusSeconds(10), NOW.plusSeconds(20), 100L);
        for (RpcPeerIdentity identity : new RpcPeerIdentity[] {certificateFirst, bindingFirst}) {
            assertEquals(NOW.plusSeconds(10), identity.validUntil().orElseThrow());
            assertTrue(identity.validAt(NOW.plusSeconds(9), 100L + Duration.ofSeconds(9).toNanos()));
            assertFalse(identity.validAt(NOW.plusSeconds(10), 100L + Duration.ofSeconds(9).toNanos()));
            assertEquals(0L, identity.remainingValidityNanos(NOW.plusSeconds(10), 101L));
        }
    }

    @Test
    void wallClockRollbackCannotRestoreElapsedLifetime() {
        RpcPeerIdentity identity = peer(NOW.plusSeconds(10), NOW.plusSeconds(20), 100L);
        assertEquals(Duration.ofSeconds(2).toNanos(),
                identity.remainingValidityNanos(NOW.minusSeconds(1), 100L + Duration.ofSeconds(8).toNanos()));
        assertFalse(identity.validAt(NOW.minusSeconds(1), 100L + Duration.ofSeconds(10).toNanos()));
        assertFalse(identity.validAt(NOW, 99L));
    }

    @Test
    void monotonicWrapPreservesOnlyTheOriginalFiniteInterval() {
        long anchor = Long.MAX_VALUE - 20L;
        RpcPeerIdentity identity = peer(NOW.plusNanos(100), NOW.plusSeconds(1), anchor);
        assertEquals(50L, identity.remainingValidityNanos(NOW, anchor + 50L));
        assertFalse(identity.validAt(NOW, anchor + 100L));
    }

    @Test
    void anonymousIdentityCannotAcquireValidityOrApplication() {
        RpcPeerIdentity identity = RpcPeerIdentity.anonymous();
        assertFalse(identity.authenticated());
        assertTrue(identity.application().isEmpty());
        assertTrue(identity.certificateBinding().isEmpty());
        assertTrue(identity.certificateNotBefore().isEmpty());
        assertTrue(identity.certificateNotAfter().isEmpty());
        assertTrue(identity.validUntil().isEmpty());
        assertEquals(0L, identity.remainingValidityNanos(NOW, 0L));
    }

    @Test
    void invalidFingerprintsAndInfiniteExpiryCannotFormBindings() {
        assertEquals(FINGERPRINT, new RpcCertificateBinding("A".repeat(64), APP, NOW).certificateSha256());
        for (String invalid : new String[] {"", "CN=orders", "a".repeat(63), "g".repeat(64), " " + FINGERPRINT}) {
            assertThrows(IllegalArgumentException.class, () -> new RpcCertificateBinding(invalid, APP, NOW));
        }
        assertThrows(IllegalArgumentException.class, () -> new RpcCertificateBinding(FINGERPRINT, APP, Instant.MAX));
        assertThrows(IllegalArgumentException.class, () -> new RpcCertificateBinding(FINGERPRINT, APP, Instant.MIN));
    }

    @Test
    void factoryRejectsInvalidVerificationTimeAndUnboundedMonotonicLifetime() {
        RpcCertificateBinding binding = new RpcCertificateBinding(FINGERPRINT, APP, NOW.plusSeconds(10));
        assertThrows(IllegalArgumentException.class, () -> RpcPeerIdentity.verifiedMutualTls(
                binding, NOW.plusSeconds(1), NOW.plusSeconds(20), NOW, 0L));
        assertThrows(IllegalArgumentException.class, () -> RpcPeerIdentity.verifiedMutualTls(
                binding, NOW.minusSeconds(1), NOW.plusSeconds(20), NOW.plusSeconds(10), 0L));
        Instant tooFar = NOW.plusSeconds(Duration.ofDays(365_000).toSeconds());
        assertThrows(IllegalArgumentException.class, () -> RpcPeerIdentity.verifiedMutualTls(
                new RpcCertificateBinding(FINGERPRINT, APP, tooFar), NOW.minusSeconds(1), tooFar, NOW, 0L));
    }

    @Test
    void finiteDecisionStructureCannotRepresentUnlimitedAllow() {
        assertFalse(RpcAuthorizationDecision.deny().allowed());
        assertTrue(RpcAuthorizationDecision.allowUntil(NOW.minusSeconds(1)).allowed());
        assertThrows(IllegalArgumentException.class, () -> RpcAuthorizationDecision.allowUntil(Instant.MAX));
        assertThrows(IllegalArgumentException.class, () -> new RpcAuthorizationDecision(
                RpcAuthorizationDecision.Outcome.ALLOW, Optional.empty()));
        assertThrows(IllegalArgumentException.class, () -> new RpcAuthorizationDecision(
                RpcAuthorizationDecision.Outcome.DENY, Optional.of(NOW)));
    }

    @Test
    void sameEndpointRetainsIndependentApplicationAndNamespaceTargets() {
        RpcEndpoint endpoint = new RpcEndpoint("127.0.0.1", 19091);
        Set<RpcTarget> targets = Set.of(new RpcTarget(APP, endpoint),
                new RpcTarget(new ApplicationKey("production", "other"), endpoint),
                new RpcTarget(new ApplicationKey("staging", "orders"), endpoint));
        assertEquals(3, targets.size());
        for (String host : new String[] {"0.0.0.0", "::", "[::]", "0:0:0:0:0:0:0:0", "*"}) {
            assertThrows(IllegalArgumentException.class, () -> new RpcTarget(APP, new RpcEndpoint(host, 19091)));
        }
        assertThrows(IllegalArgumentException.class, () -> new RpcTarget(APP, new RpcEndpoint("127.0.0.1", 0)));
    }

    @Test
    void trustCollectionsAreImmutableAndConflictingFingerprintsRejected() {
        RpcCertificateBinding binding = new RpcCertificateBinding(FINGERPRINT, APP, NOW);
        Set<RpcCertificateBinding> mutable = new HashSet<>(Set.of(binding));
        RpcTransportIdentityOptions identity = new RpcTransportIdentityOptions(APP, mutable);
        mutable.clear();
        assertEquals(Set.of(binding), identity.certificateBindings());
        assertThrows(UnsupportedOperationException.class, () -> identity.certificateBindings().clear());
        assertThrows(IllegalArgumentException.class, () -> new RpcTransportIdentityOptions(APP,
                Set.of(binding, new RpcCertificateBinding(
                        FINGERPRINT, new ApplicationKey("production", "other"), NOW))));
        assertThrows(IllegalArgumentException.class, () -> new RpcTransportIdentityOptions(APP,
                Set.of(binding, new RpcCertificateBinding(FINGERPRINT, APP, NOW.plusSeconds(1)))));
    }

    @Test
    void securityDiagnosticsNeverCarryFingerprintPathsOrExternalCauses() {
        RpcCertificateBinding binding = new RpcCertificateBinding(FINGERPRINT, APP, NOW);
        RpcTransportIdentityOptions identity = new RpcTransportIdentityOptions(APP, Set.of(binding));
        String secret = "secret-material-token";
        RpcTransportSecurityOptions options = new RpcTransportSecurityOptions(RpcSecurityMode.MTLS,
                secret, secret, secret, true, Duration.ofSeconds(1), Duration.ofSeconds(1),
                Duration.ofSeconds(1), Optional.of(identity));
        assertFalse(options.toString().contains(secret));
        assertFalse(identity.toString().contains(FINGERPRINT));
        assertFalse(binding.toString().contains(FINGERPRINT));
        assertFalse(peer(NOW.plusSeconds(1), NOW.plusSeconds(2), 0L).toString().contains(FINGERPRINT));
        for (RpcAuthenticationException.Reason reason : RpcAuthenticationException.Reason.values()) {
            RpcAuthenticationException error = new RpcAuthenticationException(reason);
            error.addSuppressed(new IllegalStateException(secret));
            assertEquals(0, error.getSuppressed().length);
            assertThrows(IllegalStateException.class, () -> error.initCause(new IllegalStateException(secret)));
            assertEquals(RpcFailureCategory.SECURITY, RpcFailureClassifier.classify(RpcStatus.BAD_REQUEST, error));
            assertFalse(error.toString().contains(secret));
        }
    }

    private static RpcPeerIdentity peer(Instant bindingUntil, Instant certificateUntil, long nanos) {
        return RpcPeerIdentity.verifiedMutualTls(new RpcCertificateBinding(FINGERPRINT, APP, bindingUntil),
                NOW.minusSeconds(60), certificateUntil, NOW, nanos);
    }
}
