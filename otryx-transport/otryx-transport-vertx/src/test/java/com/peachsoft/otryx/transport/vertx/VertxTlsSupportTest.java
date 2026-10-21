package com.peachsoft.otryx.transport.vertx;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.peachsoft.otryx.api.ApplicationKey;
import com.peachsoft.otryx.api.RpcAuthenticationException;
import com.peachsoft.otryx.api.RpcUnavailableException;
import com.peachsoft.otryx.codec.RpcCodecIds;
import com.peachsoft.otryx.observability.RpcObserver;
import com.peachsoft.otryx.observability.RpcSecurityMode;
import com.peachsoft.otryx.transport.RpcTransportIdentityOptions;
import com.peachsoft.otryx.transport.RpcTransportSecurityDescriptor;
import com.peachsoft.otryx.transport.RpcTransportSecurityOptions;
import io.vertx.core.net.SSLOptions;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.security.cert.CertificateException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.X509KeyManager;
import javax.net.ssl.X509TrustManager;
import org.junit.jupiter.api.Test;

/**
 * Vert.x TLS 冻结材料、状态与错误边界测试。
 *
 * @Author Ryan
 * @Version 1.0.0
 * @CreateTime 2026/9/29 17:34
 */
class VertxTlsSupportTest {

    @Test
    void fileStateShouldDetectContentChangeWithSameSizeAndMtime() throws Exception {
        Path file = Files.createTempFile("otryx-tls-state", ".pem");
        try {
            Files.writeString(file, "AAAA");
            FileTime originalTime = Files.getLastModifiedTime(file);
            RpcTransportSecurityOptions security = new RpcTransportSecurityOptions(RpcSecurityMode.TLS,
                    file.toString(), "", "", true, Duration.ofSeconds(1), Duration.ofSeconds(1),
                    Duration.ofDays(1), Optional.empty());
            VertxTlsSupport.FileState before = VertxTlsSupport.fileState(security);
            Files.writeString(file, "BBBB");
            Files.setLastModifiedTime(file, originalTime);
            assertNotEquals(before, VertxTlsSupport.fileState(security));
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    void preparedOptionsAndDescriptorMustNotRereadMaterialPaths() throws Exception {
        try (TlsTestCertificates certs = TlsTestCertificates.create()) {
            byte[] leafDer = TlsTestCertificates.readCertificate(certs.serverCertificate).getEncoded();
            byte[] caDer = TlsTestCertificates.readCertificate(certs.caCertificate).getEncoded();
            VertxTlsSupport.PreparedMaterial material = prepare(security(certs)
                    .withIdentity(certs.identities(TlsTestCertificates.PROVIDER)));
            RpcTransportSecurityDescriptor descriptor = material.descriptor();
            Files.delete(certs.serverCertificate);
            Files.delete(certs.serverPrivateKey);
            Files.delete(certs.caCertificate);
            SSLOptions installed = material.sslOptions();
            X509KeyManager keys = (X509KeyManager) installed.getKeyCertOptions()
                    .getKeyManagerFactory(null).getKeyManagers()[0];
            X509TrustManager trust = (X509TrustManager) installed.getTrustOptions()
                    .getTrustManagerFactory(null).getTrustManagers()[0];
            String alias = keys.getServerAliases("RSA", null)[0];
            assertArrayEquals(leafDer, keys.getCertificateChain(alias)[0].getEncoded());
            assertNotNull(keys.getPrivateKey(alias));
            assertArrayEquals(caDer, trust.getAcceptedIssuers()[0].getEncoded());
            assertSame(descriptor, material.descriptor());
            assertEquals(RpcTransportSecurityDescriptor.State.EFFECTIVE, descriptor.state());
            assertEquals(TlsTestCertificates.PROVIDER, descriptor.localIdentity().orElseThrow().application());
            assertEquals(Set.of(RpcCodecIds.FORY_NATIVE), descriptor.enabledCodecIds());
            assertEquals(Set.of(RpcTransportSecurityDescriptor.Capability.PEER_CERTIFICATE_CHAIN_VALIDATION,
                    RpcTransportSecurityDescriptor.Capability.PEER_APPLICATION_BINDING,
                    RpcTransportSecurityDescriptor.Capability.PEER_IDENTITY_DELIVERY,
                    RpcTransportSecurityDescriptor.Capability.IDENTITY_EXPIRY), descriptor.capabilities());
        }
    }

    @Test
    void mismatchedKeyAndCorruptOrOversizedMaterialMustFailWithoutRawCauses() throws Exception {
        try (TlsTestCertificates certs = TlsTestCertificates.create()) {
            Files.copy(certs.clientPrivateKey, certs.serverPrivateKey,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            assertRedacted(assertThrows(IllegalArgumentException.class, () -> prepare(security(certs))));
            Files.writeString(certs.serverPrivateKey, "sensitive invalid key material");
            assertRedacted(assertThrows(IllegalArgumentException.class, () -> prepare(security(certs))));
            Files.write(certs.serverPrivateKey, new byte[VertxTlsSupport.MAX_PEM_BYTES + 1]);
            assertRedacted(assertThrows(IllegalArgumentException.class, () -> prepare(security(certs))));
        }
    }

    @Test
    void unknownWrongApplicationAndExpiredLocalBindingsMustFailBeforeActivation() throws Exception {
        try (TlsTestCertificates certs = TlsTestCertificates.create()) {
            RpcTransportSecurityOptions base = security(certs);
            Instant until = Instant.now().plusSeconds(60);
            RpcTransportIdentityOptions unbound = new RpcTransportIdentityOptions(TlsTestCertificates.PROVIDER,
                    Set.of(TlsTestCertificates.binding(certs.clientCertificate, TlsTestCertificates.CONSUMER, until)));
            assertReason(RpcAuthenticationException.Reason.IDENTITY_UNBOUND, base.withIdentity(unbound));
            RpcTransportIdentityOptions wrong = new RpcTransportIdentityOptions(
                    new ApplicationKey(TlsTestCertificates.PROVIDER.namespace(), "other-provider"),
                    Set.of(TlsTestCertificates.binding(certs.serverCertificate, TlsTestCertificates.PROVIDER, until)));
            assertReason(RpcAuthenticationException.Reason.APPLICATION_MISMATCH, base.withIdentity(wrong));
            RpcTransportIdentityOptions expired = new RpcTransportIdentityOptions(TlsTestCertificates.PROVIDER,
                    Set.of(TlsTestCertificates.binding(certs.serverCertificate, TlsTestCertificates.PROVIDER,
                            Instant.now().minusSeconds(1))));
            assertReason(RpcAuthenticationException.Reason.IDENTITY_EXPIRED, base.withIdentity(expired));
        }
    }

    @Test
    void wrongLocalBindingNamespaceMustFailBeforeActivation() throws Exception {
        try (TlsTestCertificates certs = TlsTestCertificates.create()) {
            RpcTransportIdentityOptions identity = new RpcTransportIdentityOptions(TlsTestCertificates.PROVIDER,
                    Set.of(TlsTestCertificates.binding(certs.serverCertificate,
                            new ApplicationKey("other-namespace", TlsTestCertificates.PROVIDER.applicationName()),
                            Instant.now().plusSeconds(60))));
            assertReason(RpcAuthenticationException.Reason.NAMESPACE_MISMATCH,
                    security(certs).withIdentity(identity));
        }
    }

    @Test
    void onlyActualCertificateFailuresMayBecomeAuthenticationFailures() {
        Throwable rejected = VertxTlsSupport.connectionFailure(new SSLHandshakeException(
                "Received fatal alert: certificate_required"));
        assertEquals(RpcAuthenticationException.Reason.CERTIFICATE_REJECTED,
                assertInstanceOf(RpcAuthenticationException.class, rejected).reason());
        assertRedacted(rejected);
        Throwable wrapped = VertxTlsSupport.connectionFailure(new SSLException("sensitive TLS context",
                new CertificateException("sensitive Subject")));
        assertInstanceOf(RpcAuthenticationException.class, wrapped);
        assertRedacted(wrapped);
        Throwable ordinary = VertxTlsSupport.connectionFailure(new SSLException("peer closed connection"));
        assertInstanceOf(RpcUnavailableException.class, ordinary);
        assertRedacted(ordinary);
        Throwable changed = VertxTlsSupport.connectionFailure(new RpcAuthenticationException(
                RpcAuthenticationException.Reason.SECURITY_CONTEXT_CHANGED));
        assertEquals(RpcAuthenticationException.Reason.SECURITY_CONTEXT_CHANGED,
                assertInstanceOf(RpcAuthenticationException.class, changed).reason());
        assertRedacted(changed);
        assertRedacted(VertxTlsSupport.reloadFailure(new IllegalStateException("sensitive path")));
    }

    private static VertxTlsSupport.PreparedMaterial prepare(RpcTransportSecurityOptions security) {
        return VertxTlsSupport.prepare(security, false, RpcObserver.noop(), Set.of(RpcCodecIds.FORY_NATIVE),
                Clock.systemUTC(), System::nanoTime);
    }

    private static RpcTransportSecurityOptions security(TlsTestCertificates certs) {
        return new RpcTransportSecurityOptions(RpcSecurityMode.MTLS, certs.serverCertificate.toString(),
                certs.serverPrivateKey.toString(), certs.caCertificate.toString(), true, Duration.ofSeconds(2),
                Duration.ofSeconds(1), Duration.ofDays(7), Optional.empty());
    }

    private static void assertReason(RpcAuthenticationException.Reason reason, RpcTransportSecurityOptions security) {
        RpcAuthenticationException error = assertThrows(RpcAuthenticationException.class, () -> prepare(security));
        assertEquals(reason, error.reason());
        assertRedacted(error);
    }

    private static void assertRedacted(Throwable error) {
        assertNull(error.getCause());
        assertEquals(0, error.getSuppressed().length);
        org.junit.jupiter.api.Assertions.assertFalse(error.toString().contains("sensitive"));
    }
}
