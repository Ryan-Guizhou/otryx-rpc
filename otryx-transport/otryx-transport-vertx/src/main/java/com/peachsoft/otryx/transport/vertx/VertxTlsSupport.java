package com.peachsoft.otryx.transport.vertx;

import com.peachsoft.otryx.observability.RpcCertificateReloadOutcome;
import com.peachsoft.otryx.observability.RpcObserver;
import com.peachsoft.otryx.observability.RpcSecurityMode;
import com.peachsoft.otryx.transport.RpcTransportSecurityOptions;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.ClientAuth;
import io.vertx.core.net.NetClientOptions;
import io.vertx.core.net.NetServerOptions;
import io.vertx.core.net.PemKeyCertOptions;
import io.vertx.core.net.PemTrustOptions;
import io.vertx.core.net.SSLOptions;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.HexFormat;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * Vert.x PEM TLS/mTLS 配置与证书校验工具。
 *
 * @Author Ryan
 * @Version 1.0.0
 * @CreateTime 2026/9/29 14:55
 */
final class VertxTlsSupport {

    private VertxTlsSupport() {
    }

    /**
     * 将安全配置应用到 Client。
     *
     * @param target NetClient 参数
     * @param security 安全配置
     * @param observer Observer
     */
    static void configureClient(
            NetClientOptions target,
            RpcTransportSecurityOptions security,
            RpcObserver observer) {
        Objects.requireNonNull(target, "target");
        if (!security.enabled()) {
            return;
        }
        validateClientMaterial(security, observer);
        target.setSsl(true)
                .setTrustOptions(trustOptions(security))
                .setHostnameVerificationAlgorithm(
                        security.hostnameVerification()
                                ? "HTTPS"
                                : "")
                .setSslHandshakeTimeout(
                        security.handshakeTimeout().toMillis())
                .setSslHandshakeTimeoutUnit(
                        TimeUnit.MILLISECONDS);
        if (!security.certificatePath().isBlank()
                || !security.privateKeyPath().isBlank()) {
            target.setKeyCertOptions(keyCertOptions(security));
        }
    }

    /**
     * 将安全配置应用到 Server。
     *
     * @param target NetServer 参数
     * @param security 安全配置
     * @param observer Observer
     */
    static void configureServer(
            NetServerOptions target,
            RpcTransportSecurityOptions security,
            RpcObserver observer) {
        Objects.requireNonNull(target, "target");
        if (!security.enabled()) {
            return;
        }
        validateServerMaterial(security, observer);
        target.setSsl(true)
                .setKeyCertOptions(keyCertOptions(security))
                .setSslHandshakeTimeout(
                        security.handshakeTimeout().toMillis())
                .setSslHandshakeTimeoutUnit(
                        TimeUnit.MILLISECONDS);
        if (security.mutualTls()) {
            target.setTrustOptions(trustOptions(security))
                    .setClientAuth(ClientAuth.REQUIRED);
        } else {
            target.setClientAuth(ClientAuth.NONE);
        }
    }

    /**
     * 构建热更新 SSL material。
     *
     * @param security 安全配置
     * @param client 是否 Client 侧
     * @param observer Observer
     * @return Vert.x SSL Options
     */
    static SSLOptions reloadOptions(
            RpcTransportSecurityOptions security,
            boolean client,
            RpcObserver observer) {
        if (client) {
            validateClientMaterial(security, observer);
        } else {
            validateServerMaterial(security, observer);
        }
        SSLOptions options = new SSLOptions();
        if (!security.certificatePath().isBlank()
                || !security.privateKeyPath().isBlank()) {
            options.setKeyCertOptions(
                    new PemKeyCertOptions()
                            .setCertValue(readPemValue(security.certificatePath()))
                            .setKeyValue(readPemValue(security.privateKeyPath())));
        }
        if (!security.trustCertificatePath().isBlank()) {
            options.setTrustOptions(
                    new PemTrustOptions()
                            .addCertValue(readPemValue(
                                    security.trustCertificatePath())));
        }
        return options;
    }

    /**
     * 将 PEM 材料读取为独立快照，防止 Vert.x 发布新 SSL 配置时再访问路径。
     *
     * <p>此方法仅在 TLS 控制面执行器中调用，禁止在 EventLoop 执行文件 IO。
     *
     * @param filename 已验证的 PEM 材料路径
     * @return 文件内容快照
     */
    private static Buffer readPemValue(String filename) {
        try {
            return Buffer.buffer(Files.readAllBytes(Path.of(filename)));
        } catch (IOException error) {
            throw new IllegalArgumentException(
                    "Failed to read configured TLS material", error);
        }
    }

    /**
     * 返回当前 TLS 文件状态。
     *
     * @param security 安全配置
     * @return 文件状态
     */
    static FileState fileState(
            RpcTransportSecurityOptions security) {
        return new FileState(
                stamp(security.certificatePath()),
                stamp(security.privateKeyPath()),
                stamp(security.trustCertificatePath()));
    }

    private static PemKeyCertOptions keyCertOptions(
            RpcTransportSecurityOptions security) {
        requirePath(
                security.certificatePath(),
                "certificate-path");
        requirePath(
                security.privateKeyPath(),
                "private-key-path");
        return new PemKeyCertOptions()
                .setCertPath(security.certificatePath())
                .setKeyPath(security.privateKeyPath());
    }

    private static PemTrustOptions trustOptions(
            RpcTransportSecurityOptions security) {
        requirePath(
                security.trustCertificatePath(),
                "trust-certificate-path");
        return new PemTrustOptions()
                .addCertPath(
                        security.trustCertificatePath());
    }

    private static void validateClientMaterial(
            RpcTransportSecurityOptions security,
            RpcObserver observer) {
        requirePath(
                security.trustCertificatePath(),
                "trust-certificate-path");
        validateCertificates(
                security.trustCertificatePath(),
                security,
                observer);
        boolean hasCertificate =
                !security.certificatePath().isBlank();
        boolean hasPrivateKey =
                !security.privateKeyPath().isBlank();
        if (security.mutualTls()
                && (!hasCertificate || !hasPrivateKey)) {
            throw new IllegalArgumentException(
                    "mTLS client requires certificate-path "
                            + "and private-key-path");
        }
        if (hasCertificate != hasPrivateKey) {
            throw new IllegalArgumentException(
                    "certificate-path and private-key-path "
                            + "must be configured together");
        }
        if (hasCertificate) {
            validateCertificates(
                    security.certificatePath(),
                    security,
                    observer);
            requirePath(
                    security.privateKeyPath(),
                    "private-key-path");
        }
    }

    private static void validateServerMaterial(
            RpcTransportSecurityOptions security,
            RpcObserver observer) {
        requirePath(
                security.certificatePath(),
                "certificate-path");
        requirePath(
                security.privateKeyPath(),
                "private-key-path");
        validateCertificates(
                security.certificatePath(),
                security,
                observer);
        if (security.mutualTls()) {
            requirePath(
                    security.trustCertificatePath(),
                    "trust-certificate-path");
            validateCertificates(
                    security.trustCertificatePath(),
                    security,
                    observer);
        }
    }

    private static void validateCertificates(
            String path,
            RpcTransportSecurityOptions security,
            RpcObserver observer) {
        try (InputStream input =
                     Files.newInputStream(Path.of(path))) {
            CertificateFactory factory =
                    CertificateFactory.getInstance("X.509");
            Collection<? extends Certificate> certificates =
                    factory.generateCertificates(input);
            if (certificates.isEmpty()) {
                throw new IllegalArgumentException(
                        "No X.509 certificate found at " + path);
            }
            long smallestRemainingMillis = Long.MAX_VALUE;
            Instant now = Instant.now();
            for (Certificate value : certificates) {
                X509Certificate certificate =
                        (X509Certificate) value;
                certificate.checkValidity();
                long remainingMillis =
                        Duration.between(
                                        now,
                                        certificate.getNotAfter()
                                                .toInstant())
                                .toMillis();
                smallestRemainingMillis = Math.min(
                        smallestRemainingMillis,
                        remainingMillis);
            }
            if (observer.enabled()
                    && smallestRemainingMillis
                            <= security
                                    .expiryWarningThreshold()
                                    .toMillis()) {
                observer.onCertificateExpiryWarning(
                        security.mode(),
                        Math.max(0L, smallestRemainingMillis));
            }
        } catch (Exception error) {
            throw new IllegalArgumentException(
                    "Invalid TLS certificate material at "
                            + path,
                    error);
        }
    }

    private static void requirePath(
            String value,
            String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(
                    "TLS " + name + " is required");
        }
        Path path = Path.of(value);
        if (!Files.isRegularFile(path)
                || !Files.isReadable(path)) {
            throw new IllegalArgumentException(
                    "TLS " + name
                            + " is not a readable file: "
                            + path);
        }
    }

    private static FileStamp stamp(String value) {
        if (value == null || value.isBlank()) {
            return FileStamp.EMPTY;
        }
        Path path = Path.of(value);
        try {
            byte[] content = Files.readAllBytes(path);
            return new FileStamp(
                    Files.getLastModifiedTime(path)
                            .toMillis(),
                    content.length,
                    sha256(content));
        } catch (IOException error) {
            return new FileStamp(
                    -1L,
                    -1L,
                    "unreadable");
        }
    }

    /** TLS material 文件状态。 */
    record FileState(
            FileStamp certificate,
            FileStamp privateKey,
            FileStamp trustCertificate) {
    }

    private static String sha256(byte[] content) {
        try {
            MessageDigest digest =
                    MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(
                    digest.digest(content));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException(
                    "SHA-256 is not available",
                    error);
        }
    }

    /** 单文件状态。 */
    record FileStamp(
            long modifiedMillis,
            long size,
            String fingerprint) {
        private static final FileStamp EMPTY =
                new FileStamp(
                        0L,
                        0L,
                        "");
    }
}
