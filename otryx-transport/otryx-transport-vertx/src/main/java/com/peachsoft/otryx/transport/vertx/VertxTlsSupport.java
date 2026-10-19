package com.peachsoft.otryx.transport.vertx;

import com.peachsoft.otryx.api.RpcAuthenticationException;
import com.peachsoft.otryx.api.RpcCertificateBinding;
import com.peachsoft.otryx.api.RpcPeerIdentity;
import com.peachsoft.otryx.api.RpcTarget;
import com.peachsoft.otryx.api.RpcUnavailableException;
import com.peachsoft.otryx.observability.RpcObserver;
import com.peachsoft.otryx.transport.RpcTransportSecurityDescriptor;
import com.peachsoft.otryx.transport.RpcTransportSecurityOptions;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.ClientAuth;
import io.vertx.core.net.KeyCertOptions;
import io.vertx.core.net.NetClientOptions;
import io.vertx.core.net.NetServerOptions;
import io.vertx.core.net.NetSocket;
import io.vertx.core.net.PemKeyCertOptions;
import io.vertx.core.net.SSLOptions;
import io.vertx.core.net.TrustOptions;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.Principal;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.CertPathValidatorException;
import java.security.cert.X509Certificate;
import java.security.spec.MGF1ParameterSpec;
import java.security.spec.PSSParameterSpec;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import javax.net.ssl.KeyManager;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLSession;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509ExtendedKeyManager;
import javax.net.ssl.X509ExtendedTrustManager;
import javax.net.ssl.X509KeyManager;

/**
 * Vert.x PEM TLS/mTLS 配置与实际连接身份校验工具。
 *
 * <p>材料只在构造或控制面读取；安装及描述使用同一字节快照。会话证明
 * 来自实际 JDK engine 的 key/trust manager，不能用当前配置为旧握手背书。
 *
 * @Author Ryan
 * @Version 1.0.0
 * @CreateTime 2026/9/29 14:55
 */
final class VertxTlsSupport {
    static final int MAX_PEM_BYTES = 1024 * 1024;
    static final long IDENTITY_SWEEP_MILLIS = 250L;
    private static final int MAX_CAUSE_DEPTH = 12;
    private static final String PEER_PROOF = VertxTlsSupport.class.getName() + ".peer";
    private static final String LOCAL_PROOF = VertxTlsSupport.class.getName() + ".local";
    private static final byte[] KEY_CHALLENGE =
            "OTRYX TLS local key pairing v1".getBytes(StandardCharsets.US_ASCII);
    private static final Set<String> CERTIFICATE_ALERTS = Set.of(
            "Received fatal alert: bad_certificate",
            "Received fatal alert: certificate_unknown",
            "Received fatal alert: certificate_required",
            "Received fatal alert: certificate_expired",
            "Received fatal alert: unknown_ca");

    private VertxTlsSupport() {
    }

    static PreparedMaterial prepare(
            RpcTransportSecurityOptions security,
            boolean client,
            RpcObserver observer,
            Set<Byte> codecIds,
            Clock clock,
            LongSupplier nanoTime) {
        return prepare(readMaterial(security), security, client, observer, codecIds, clock, nanoTime);
    }

    static PreparedMaterial prepare(
            RawMaterial raw,
            RpcTransportSecurityOptions security,
            boolean client,
            RpcObserver observer,
            Set<Byte> codecIds,
            Clock clock,
            LongSupplier nanoTime) {
        Objects.requireNonNull(security, "security");
        if (!security.enabled()) {
            return new PreparedMaterial(raw, security, client, codecIds, clock, nanoTime, null, null, null);
        }
        if ((!client || security.mutualTls()) && (raw.certificate.length == 0 || raw.privateKey.length == 0)) {
            throw invalidMaterial();
        }
        if ((raw.certificate.length == 0) != (raw.privateKey.length == 0)
                || ((client || security.mutualTls()) && raw.trustCertificate.length == 0)) {
            throw invalidMaterial();
        }
        try {
            X509Certificate leaf = null;
            X509KeyManager keys = null;
            if (raw.certificate.length > 0) {
                List<X509Certificate> certificates = certificates(raw.certificate, security, observer);
                leaf = certificates.getFirst();
                PemKeyCertOptions pem = new PemKeyCertOptions()
                        .setCertValue(Buffer.buffer(raw.certificate.clone()))
                        .setKeyValue(Buffer.buffer(raw.privateKey.clone()));
                // 此 API 的 value-only 分支不使用 Vertx，不创建运行时或重新访问路径。
                for (KeyManager manager : pem.getKeyManagerFactory(null).getKeyManagers()) {
                    if (manager instanceof X509KeyManager x509) {
                        keys = x509;
                        break;
                    }
                }
                verifyKeyPair(keys, leaf);
            }
            X509ExtendedTrustManager trust = null;
            if (raw.trustCertificate.length > 0) {
                KeyStore store = KeyStore.getInstance(KeyStore.getDefaultType());
                store.load(null, null);
                int index = 0;
                for (X509Certificate certificate : certificates(raw.trustCertificate, security, observer)) {
                    store.setCertificateEntry(Integer.toString(index++), certificate);
                }
                TrustManagerFactory factory = TrustManagerFactory.getInstance(
                        TrustManagerFactory.getDefaultAlgorithm());
                factory.init(store);
                for (TrustManager manager : factory.getTrustManagers()) {
                    if (manager instanceof X509ExtendedTrustManager x509) {
                        trust = x509;
                        break;
                    }
                }
                if (trust == null) {
                    throw invalidMaterial();
                }
            }
            return new PreparedMaterial(raw, security, client, codecIds, clock, nanoTime, leaf, keys, trust);
        } catch (RpcAuthenticationException error) {
            throw error;
        } catch (Exception error) {
            // 不把文件名、PEM、Subject 或底层异常链传播到公共边界。
            throw invalidMaterial();
        }
    }

    static void configureClient(NetClientOptions target, PreparedMaterial material) {
        if (!material.security.enabled()) {
            return;
        }
        SSLOptions ssl = material.sslOptions();
        target.setSsl(true)
                .setSslEngineOptions(new BoundedJdkSslEngineOptions())
                .setTrustAll(false)
                .setTrustOptions(ssl.getTrustOptions())
                .setKeyCertOptions(ssl.getKeyCertOptions())
                .setHostnameVerificationAlgorithm(material.security.hostnameVerification() ? "HTTPS" : "")
                .setSslHandshakeTimeout(material.security.handshakeTimeout().toMillis())
                .setSslHandshakeTimeoutUnit(TimeUnit.MILLISECONDS);
    }

    static void configureServer(NetServerOptions target, PreparedMaterial material) {
        if (!material.security.enabled()) {
            return;
        }
        SSLOptions ssl = material.sslOptions();
        target.setSsl(true)
                .setSslEngineOptions(new BoundedJdkSslEngineOptions())
                .setKeyCertOptions(ssl.getKeyCertOptions())
                .setTrustOptions(ssl.getTrustOptions())
                .setClientAuth(material.security.mutualTls() ? ClientAuth.REQUIRED : ClientAuth.NONE)
                .setSslHandshakeTimeout(material.security.handshakeTimeout().toMillis())
                .setSslHandshakeTimeoutUnit(TimeUnit.MILLISECONDS);
    }

    static RawMaterial readMaterial(RpcTransportSecurityOptions security) {
        if (!security.enabled()) {
            return new RawMaterial(RawFile.EMPTY, RawFile.EMPTY, RawFile.EMPTY);
        }
        return new RawMaterial(readFile(security.certificatePath()),
                readFile(security.privateKeyPath()), readFile(security.trustCertificatePath()));
    }

    static FileState fileState(RpcTransportSecurityOptions security) {
        return readMaterial(security).state;
    }

    private static RawFile readFile(String filename) {
        if (filename == null || filename.isBlank()) {
            return RawFile.EMPTY;
        }
        try {
            Path path = Path.of(filename);
            byte[] bytes;
            try (InputStream input = Files.newInputStream(path)) {
                bytes = input.readNBytes(MAX_PEM_BYTES + 1);
            }
            if (bytes.length == 0 || bytes.length > MAX_PEM_BYTES) {
                throw invalidMaterial();
            }
            return new RawFile(bytes,
                    new FileStamp(Files.getLastModifiedTime(path).toMillis(), bytes.length, sha256(bytes)));
        } catch (IOException | RuntimeException error) {
            throw invalidMaterial();
        }
    }

    private static List<X509Certificate> certificates(
            byte[] bytes, RpcTransportSecurityOptions security, RpcObserver observer) throws CertificateException {
        List<X509Certificate> result = new ArrayList<>();
        Instant now = Instant.now();
        long remainingMillis = Long.MAX_VALUE;
        for (Certificate value : CertificateFactory.getInstance("X.509")
                .generateCertificates(new ByteArrayInputStream(bytes))) {
            if (!(value instanceof X509Certificate certificate)) {
                throw invalidMaterial();
            }
            certificate.checkValidity(Date.from(now));
            remainingMillis = Math.min(remainingMillis,
                    Duration.between(now, certificate.getNotAfter().toInstant()).toMillis());
            result.add(certificate);
        }
        if (result.isEmpty()) {
            throw invalidMaterial();
        }
        if (observer.enabled() && remainingMillis <= security.expiryWarningThreshold().toMillis()) {
            observer.onCertificateExpiryWarning(security.mode(), Math.max(0L, remainingMillis));
        }
        return List.copyOf(result);
    }

    private static void verifyKeyPair(X509KeyManager manager, X509Certificate certificate) throws Exception {
        if (manager == null) {
            throw invalidMaterial();
        }
        String algorithm = certificate.getPublicKey().getAlgorithm();
        String[] aliases = manager.getServerAliases(algorithm, null);
        if (aliases == null || aliases.length == 0) {
            aliases = manager.getClientAliases(algorithm, null);
        }
        if (aliases == null || aliases.length == 0) {
            throw invalidMaterial();
        }
        PrivateKey key = null;
        for (String alias : aliases) {
            X509Certificate[] chain = manager.getCertificateChain(alias);
            if (chain != null && chain.length > 0 && Arrays.equals(chain[0].getEncoded(), certificate.getEncoded())) {
                key = manager.getPrivateKey(alias);
                break;
            }
        }
        if (key == null) {
            throw invalidMaterial();
        }
        String signatureAlgorithm = switch (algorithm) {
            case "RSA" -> "SHA256withRSA";
            case "RSASSA-PSS" -> "RSASSA-PSS";
            case "EC" -> "SHA256withECDSA";
            case "DSA" -> "SHA256withDSA";
            case "EdDSA", "Ed25519" -> "Ed25519";
            case "Ed448" -> "Ed448";
            default -> throw invalidMaterial();
        };
        Signature signature = Signature.getInstance(signatureAlgorithm);
        if ("RSASSA-PSS".equals(signatureAlgorithm)) {
            signature.setParameter(new PSSParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, 32, 1));
        }
        signature.initSign(key);
        signature.update(KEY_CHALLENGE);
        byte[] signed = signature.sign();
        signature.initVerify(certificate.getPublicKey());
        signature.update(KEY_CHALLENGE);
        if (!signature.verify(signed)) {
            throw invalidMaterial();
        }
    }

    static RuntimeException connectionFailure(Throwable error) {
        Throwable current = error;
        for (int depth = 0; current != null && depth < MAX_CAUSE_DEPTH; depth++) {
            if (current instanceof RpcAuthenticationException authentication) {
                return new RpcAuthenticationException(authentication.reason());
            }
            if (current instanceof CertificateException
                    || current instanceof CertPathValidatorException
                    || current instanceof SSLPeerUnverifiedException
                    || (current instanceof SSLHandshakeException
                            && CERTIFICATE_ALERTS.contains(current.getMessage()))) {
                return authentication(RpcAuthenticationException.Reason.CERTIFICATE_REJECTED);
            }
            current = current.getCause();
        }
        return new RpcUnavailableException("RPC connection failed");
    }

    static Throwable reloadFailure(Throwable error) {
        return error == null ? null : new IllegalStateException("TLS material reload failed");
    }

    private static IllegalArgumentException invalidMaterial() {
        return new IllegalArgumentException("Invalid TLS material");
    }

    private static RpcAuthenticationException authentication(RpcAuthenticationException.Reason reason) {
        return new RpcAuthenticationException(reason);
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is unavailable");
        }
    }

    private static String fingerprint(X509Certificate certificate) {
        try {
            return sha256(certificate.getEncoded());
        } catch (CertificateException error) {
            throw authentication(RpcAuthenticationException.Reason.CERTIFICATE_REJECTED);
        }
    }

    private static RpcPeerIdentity verifiedIdentity(RpcCertificateBinding binding, X509Certificate certificate,
            Instant verifiedAt, long verifiedAtNanos) {
        Instant notBefore = certificate.getNotBefore().toInstant();
        Instant notAfter = certificate.getNotAfter().toInstant();
        if (verifiedAt.isBefore(notBefore) || !verifiedAt.isBefore(notAfter)
                || !verifiedAt.isBefore(binding.validUntil())) {
            throw authentication(RpcAuthenticationException.Reason.IDENTITY_EXPIRED);
        }
        return RpcPeerIdentity.verifiedMutualTls(binding, notBefore, notAfter, verifiedAt, verifiedAtNanos);
    }

    /** 同一组有界读取得到的字节与状态；比较只使用状态，不泄露材料。 */
    static final class RawMaterial {
        private final byte[] certificate;
        private final byte[] privateKey;
        private final byte[] trustCertificate;
        private final FileState state;

        private RawMaterial(RawFile certificate, RawFile privateKey, RawFile trustCertificate) {
            this.certificate = certificate.bytes;
            this.privateKey = privateKey.bytes;
            this.trustCertificate = trustCertificate.bytes;
            this.state = new FileState(certificate.stamp, privateKey.stamp, trustCertificate.stamp);
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof RawMaterial material && state.equals(material.state);
        }

        @Override
        public int hashCode() {
            return state.hashCode();
        }
    }

    /** 已验证且尚未启动任何运行时的 TLS 材料。 */
    static final class PreparedMaterial {
        private final RawMaterial raw;
        private final RpcTransportSecurityOptions security;
        private final boolean client;
        private final Clock clock;
        private final LongSupplier nanoTime;
        private final Object token = new Object();
        private final KeyCertOptions keys;
        private final TrustOptions trust;
        private final Map<String, RpcCertificateBinding> bindings;
        private final CertificateValidity localValidity;
        private final String localFingerprint;
        private final RpcPeerIdentity localIdentity;
        private final RpcTransportSecurityDescriptor descriptor;

        private PreparedMaterial(
                RawMaterial raw,
                RpcTransportSecurityOptions security,
                boolean client,
                Set<Byte> codecIds,
                Clock clock,
                LongSupplier nanoTime,
                X509Certificate leaf,
                X509KeyManager keyManager,
                X509ExtendedTrustManager trustManager) {
            this.raw = raw;
            this.security = security;
            this.client = client;
            this.clock = Objects.requireNonNull(clock, "clock");
            this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
            this.bindings = security.identity().map(identity -> {
                Map<String, RpcCertificateBinding> values = new HashMap<>();
                identity.certificateBindings().forEach(binding -> values.put(binding.certificateSha256(), binding));
                return Map.copyOf(values);
            }).orElseGet(Map::of);
            Instant now = clock.instant();
            long nowNanos = nanoTime.getAsLong();
            localValidity = leaf == null ? null : new CertificateValidity(leaf, now, nowNanos);
            localFingerprint = leaf == null ? null : fingerprint(leaf);
            RpcCertificateBinding localBinding = null;
            if (security.identity().isPresent()) {
                if (leaf == null) {
                    throw authentication(RpcAuthenticationException.Reason.IDENTITY_UNBOUND);
                }
                localBinding = bindings.get(fingerprint(leaf));
                if (localBinding == null) {
                    throw authentication(RpcAuthenticationException.Reason.IDENTITY_UNBOUND);
                }
                if (!localBinding.application().namespace().equals(
                        security.identity().orElseThrow().localApplication().namespace())) {
                    throw authentication(RpcAuthenticationException.Reason.NAMESPACE_MISMATCH);
                }
                if (!localBinding.application().equals(security.identity().orElseThrow().localApplication())) {
                    throw authentication(RpcAuthenticationException.Reason.APPLICATION_MISMATCH);
                }
            }
            localIdentity = localBinding == null ? RpcPeerIdentity.anonymous()
                    : verifiedIdentity(localBinding, leaf, now, nowNanos);
            keys = keyManager == null ? null
                    : KeyCertOptions.wrap(new MarkingKeyManager(keyManager, token, clock, nanoTime));
            trust = trustManager == null ? null
                    : TrustOptions.wrap(new MarkingTrustManager(trustManager, token, clock, nanoTime));
            checkLocal(now, nowNanos);
            EnumSet<RpcTransportSecurityDescriptor.Capability> capabilities =
                    EnumSet.noneOf(RpcTransportSecurityDescriptor.Capability.class);
            if (security.enabled() && (client || security.mutualTls())) {
                capabilities.add(RpcTransportSecurityDescriptor.Capability.PEER_CERTIFICATE_CHAIN_VALIDATION);
            }
            if (security.enabled() && client && security.hostnameVerification()) {
                capabilities.add(RpcTransportSecurityDescriptor.Capability.HOSTNAME_VERIFICATION);
            }
            if (security.mutualTls() && localBinding != null) {
                capabilities.add(RpcTransportSecurityDescriptor.Capability.PEER_APPLICATION_BINDING);
                capabilities.add(RpcTransportSecurityDescriptor.Capability.PEER_IDENTITY_DELIVERY);
                capabilities.add(RpcTransportSecurityDescriptor.Capability.IDENTITY_EXPIRY);
                if (client) {
                    capabilities.add(RpcTransportSecurityDescriptor.Capability.EXPECTED_TARGET_APPLICATION);
                    capabilities.add(RpcTransportSecurityDescriptor.Capability.TARGET_BOUND_CONNECTION_POOL);
                }
            }
            Optional<RpcCertificateBinding> effectiveLocal = localBinding == null ? Optional.empty()
                    : Optional.of(new RpcCertificateBinding(localBinding.certificateSha256(),
                            localBinding.application(), localIdentity.validUntil().orElseThrow()));
            descriptor = new RpcTransportSecurityDescriptor(RpcTransportSecurityDescriptor.State.EFFECTIVE,
                    security.mode(), effectiveLocal, capabilities, codecIds);
        }

        RawMaterial raw() {
            return raw;
        }

        RpcTransportSecurityDescriptor descriptor() {
            return descriptor;
        }

        SSLOptions sslOptions() {
            return new SSLOptions().setKeyCertOptions(keys == null ? null : keys.copy())
                    .setTrustOptions(trust == null ? null : trust.copy())
                    .setSslHandshakeTimeout(security.handshakeTimeout().toMillis())
                    .setSslHandshakeTimeoutUnit(TimeUnit.MILLISECONDS);
        }

        void checkLocal() {
            checkLocal(clock.instant(), nanoTime.getAsLong());
        }

        private void checkLocal(Instant now, long nowNanos) {
            if ((localValidity != null && !localValidity.validAt(now, nowNanos))
                    || (localIdentity.authenticated() && !localIdentity.validAt(now, nowNanos))) {
                throw authentication(RpcAuthenticationException.Reason.IDENTITY_EXPIRED);
            }
        }

        ConnectionSecurity verifyConnection(NetSocket socket, RpcTarget target) {
            if (!security.enabled()) {
                return new ConnectionSecurity(token, RpcPeerIdentity.anonymous(), null);
            }
            SSLSession session = socket.sslSession();
            if (session == null) {
                throw authentication(RpcAuthenticationException.Reason.CERTIFICATE_REJECTED);
            }
            if (!client || security.mutualTls()) {
                X509Certificate local = leaf(session.getLocalCertificates());
                requireEvidence(session, LOCAL_PROOF, local);
                if (!localFingerprint.equals(fingerprint(local))) {
                    throw authentication(RpcAuthenticationException.Reason.CERTIFICATE_REJECTED);
                }
            }
            if (!client && !security.mutualTls()) {
                ConnectionSecurity result = new ConnectionSecurity(token, RpcPeerIdentity.anonymous(), null);
                result.check(this);
                return result;
            }
            try {
                X509Certificate certificate = leaf(session.getPeerCertificates());
                HandshakeEvidence evidence = requireEvidence(session, PEER_PROOF, certificate);
                CertificateValidity validity = new CertificateValidity(
                        certificate, evidence.verifiedAt, evidence.verifiedAtNanos);
                RpcPeerIdentity peer = RpcPeerIdentity.anonymous();
                if (security.mutualTls() && security.identity().isPresent()) {
                    RpcCertificateBinding binding = bindings.get(fingerprint(certificate));
                    if (binding == null) {
                        throw authentication(RpcAuthenticationException.Reason.IDENTITY_UNBOUND);
                    }
                    peer = verifiedIdentity(binding, certificate, evidence.verifiedAt, evidence.verifiedAtNanos);
                    if (client && !binding.application().namespace().equals(target.application().namespace())) {
                        throw authentication(RpcAuthenticationException.Reason.NAMESPACE_MISMATCH);
                    }
                    if (client && !binding.application().equals(target.application())) {
                        throw authentication(RpcAuthenticationException.Reason.APPLICATION_MISMATCH);
                    }
                    if (!client && !binding.application().namespace().equals(
                            security.identity().orElseThrow().localApplication().namespace())) {
                        throw authentication(RpcAuthenticationException.Reason.NAMESPACE_MISMATCH);
                    }
                }
                ConnectionSecurity result = new ConnectionSecurity(token, peer, validity);
                result.check(this);
                return result;
            } catch (SSLPeerUnverifiedException error) {
                throw authentication(RpcAuthenticationException.Reason.CERTIFICATE_REJECTED);
            }
        }

        private HandshakeEvidence requireEvidence(SSLSession session, String name, X509Certificate certificate) {
            Object value = session.getValue(name);
            if (!(value instanceof HandshakeEvidence evidence)) {
                throw authentication(RpcAuthenticationException.Reason.CERTIFICATE_REJECTED);
            }
            if (evidence.token != token) {
                throw authentication(RpcAuthenticationException.Reason.SECURITY_CONTEXT_CHANGED);
            }
            if (!evidence.fingerprint.equals(fingerprint(certificate))) {
                throw authentication(RpcAuthenticationException.Reason.CERTIFICATE_REJECTED);
            }
            return evidence;
        }
    }

    /** 只保留连接身份与小型代次 token，不持有密钥材料。 */
    static final class ConnectionSecurity {
        private final Object token;
        private final RpcPeerIdentity peer;
        private final CertificateValidity peerValidity;

        private ConnectionSecurity(Object token, RpcPeerIdentity peer, CertificateValidity peerValidity) {
            this.token = token;
            this.peer = peer;
            this.peerValidity = peerValidity;
        }

        RpcPeerIdentity peer() {
            return peer;
        }

        void check(PreparedMaterial active) {
            if (token != active.token) {
                throw authentication(RpcAuthenticationException.Reason.SECURITY_CONTEXT_CHANGED);
            }
            Instant now = active.clock.instant();
            long nowNanos = active.nanoTime.getAsLong();
            active.checkLocal(now, nowNanos);
            if ((peerValidity != null && !peerValidity.validAt(now, nowNanos))
                    || (peer.authenticated() && !peer.validAt(now, nowNanos))) {
                throw authentication(RpcAuthenticationException.Reason.IDENTITY_EXPIRED);
            }
        }
    }

    private static X509Certificate leaf(Certificate[] certificates) {
        if (certificates == null || certificates.length == 0
                || !(certificates[0] instanceof X509Certificate certificate)) {
            throw authentication(RpcAuthenticationException.Reason.CERTIFICATE_REJECTED);
        }
        return certificate;
    }

    private static void mark(SSLEngine engine, String name, Object token, X509Certificate certificate,
            Clock clock, LongSupplier nanoTime) throws CertificateException {
        SSLSession session = engine.getHandshakeSession();
        if (session == null) {
            throw new CertificateException("Actual JDK handshake session is required");
        }
        session.putValue(name, new HandshakeEvidence(
                token, clock.instant(), nanoTime.getAsLong(), fingerprint(certificate)));
    }

    /** 私有证明只由真实 manager 回调创建；JSSE 恢复会话保留原始时间锚。 */
    private record HandshakeEvidence(
            Object token, Instant verifiedAt, long verifiedAtNanos, String fingerprint) {
    }

    private static final class CertificateValidity {
        private final Instant notBefore;
        private final Instant notAfter;
        private final long verifiedAtNanos;
        private final long permittedNanos;

        private CertificateValidity(X509Certificate certificate, Instant verifiedAt, long verifiedAtNanos) {
            this.notBefore = certificate.getNotBefore().toInstant();
            this.notAfter = certificate.getNotAfter().toInstant();
            this.verifiedAtNanos = verifiedAtNanos;
            long nanos;
            try {
                nanos = Duration.between(verifiedAt, notAfter).toNanos();
            } catch (ArithmeticException error) {
                nanos = notAfter.isAfter(verifiedAt) ? Long.MAX_VALUE : 0L;
            }
            permittedNanos = Math.max(0L, nanos);
        }

        private boolean validAt(Instant now, long nowNanos) {
            long elapsed = nowNanos - verifiedAtNanos;
            return !now.isBefore(notBefore) && now.isBefore(notAfter)
                    && elapsed >= 0L && elapsed < permittedNanos;
        }
    }

    private static final class MarkingTrustManager extends X509ExtendedTrustManager {
        private final X509ExtendedTrustManager delegate;
        private final Object token;
        private final Clock clock;
        private final LongSupplier nanoTime;

        private MarkingTrustManager(X509ExtendedTrustManager delegate, Object token, Clock clock,
                LongSupplier nanoTime) {
            this.delegate = delegate;
            this.token = token;
            this.clock = clock;
            this.nanoTime = nanoTime;
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType, SSLEngine engine)
                throws CertificateException {
            delegate.checkClientTrusted(chain, authType, engine);
            mark(engine, PEER_PROOF, token, chain[0], clock, nanoTime);
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType, SSLEngine engine)
                throws CertificateException {
            delegate.checkServerTrusted(chain, authType, engine);
            mark(engine, PEER_PROOF, token, chain[0], clock, nanoTime);
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType, Socket socket)
                throws CertificateException {
            delegate.checkClientTrusted(chain, authType, socket);
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType, Socket socket)
                throws CertificateException {
            delegate.checkServerTrusted(chain, authType, socket);
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            delegate.checkClientTrusted(chain, authType);
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            delegate.checkServerTrusted(chain, authType);
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return delegate.getAcceptedIssuers().clone();
        }
    }

    private static final class MarkingKeyManager extends X509ExtendedKeyManager {
        private final X509KeyManager delegate;
        private final Object token;
        private final Clock clock;
        private final LongSupplier nanoTime;

        private MarkingKeyManager(X509KeyManager delegate, Object token, Clock clock, LongSupplier nanoTime) {
            this.delegate = delegate;
            this.token = token;
            this.clock = clock;
            this.nanoTime = nanoTime;
        }

        private String markAlias(String alias, SSLEngine engine) {
            if (alias != null) {
                try {
                    mark(engine, LOCAL_PROOF, token, leaf(delegate.getCertificateChain(alias)), clock, nanoTime);
                } catch (CertificateException error) {
                    return null;
                }
            }
            return alias;
        }

        @Override
        public String chooseEngineClientAlias(String[] keyTypes, Principal[] issuers, SSLEngine engine) {
            String alias = delegate instanceof X509ExtendedKeyManager extended
                    ? extended.chooseEngineClientAlias(keyTypes, issuers, engine)
                    : delegate.chooseClientAlias(keyTypes, issuers, null);
            return markAlias(alias, engine);
        }

        @Override
        public String chooseEngineServerAlias(String keyType, Principal[] issuers, SSLEngine engine) {
            String alias = delegate instanceof X509ExtendedKeyManager extended
                    ? extended.chooseEngineServerAlias(keyType, issuers, engine)
                    : delegate.chooseServerAlias(keyType, issuers, null);
            return markAlias(alias, engine);
        }

        @Override
        public String[] getClientAliases(String keyType, Principal[] issuers) {
            return delegate.getClientAliases(keyType, issuers);
        }

        @Override
        public String chooseClientAlias(String[] keyTypes, Principal[] issuers, Socket socket) {
            return delegate.chooseClientAlias(keyTypes, issuers, socket);
        }

        @Override
        public String[] getServerAliases(String keyType, Principal[] issuers) {
            return delegate.getServerAliases(keyType, issuers);
        }

        @Override
        public String chooseServerAlias(String keyType, Principal[] issuers, Socket socket) {
            return delegate.chooseServerAlias(keyType, issuers, socket);
        }

        @Override
        public X509Certificate[] getCertificateChain(String alias) {
            return delegate.getCertificateChain(alias);
        }

        @Override
        public PrivateKey getPrivateKey(String alias) {
            return delegate.getPrivateKey(alias);
        }
    }

    private record RawFile(byte[] bytes, FileStamp stamp) {
        private static final RawFile EMPTY = new RawFile(new byte[0], FileStamp.EMPTY);
    }

    /** TLS material 文件状态。 */
    record FileState(FileStamp certificate, FileStamp privateKey, FileStamp trustCertificate) {
    }

    /** 单文件状态；指纹与安装时的同一份字节对应。 */
    record FileStamp(long modifiedMillis, long size, String fingerprint) {
        private static final FileStamp EMPTY = new FileStamp(0L, 0L, "");
    }
}
