package com.peachsoft.otryx.transport;

import com.peachsoft.otryx.observability.RpcSecurityMode;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

/**
 * Transport TLS/mTLS 实现无关配置。
 *
 * @param mode 安全模式
 * @param certificatePath PEM 证书路径
 * @param privateKeyPath PEM 私钥路径
 * @param trustCertificatePath PEM CA/信任证书路径
 * @param hostnameVerification Consumer 是否校验 Provider 主机名
 * @param handshakeTimeout TLS 握手超时
 * @param reloadInterval 证书文件变更检查周期
 * @param expiryWarningThreshold 证书过期前告警窗口
 * @param identity 本地应用身份与叶证书绑定，低层 TLS 可显式为空
 *
 * @Author Ryan
 * @Version 1.0.0
 * @CreateTime 2026/9/29 14:54
 */
public record RpcTransportSecurityOptions(
        RpcSecurityMode mode,
        String certificatePath,
        String privateKeyPath,
        String trustCertificatePath,
        boolean hostnameVerification,
        Duration handshakeTimeout,
        Duration reloadInterval,
        Duration expiryWarningThreshold,
        Optional<RpcTransportIdentityOptions> identity) {

    /** 默认明文 Transport 安全配置。 */
    public static final RpcTransportSecurityOptions PLAINTEXT =
            new RpcTransportSecurityOptions(
                    RpcSecurityMode.PLAINTEXT,
                    "",
                    "",
                    "",
                    true,
                    Duration.ofSeconds(3),
                    Duration.ofSeconds(30),
                    Duration.ofDays(7),
                    Optional.empty());

    /** 规范化并校验 Transport Security 参数。 */
    public RpcTransportSecurityOptions {
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(identity, "identity");
        if (mode == RpcSecurityMode.PLAINTEXT && identity.isPresent()) {
            throw new IllegalArgumentException("Plaintext transport cannot declare a TLS identity");
        }
        certificatePath = normalize(certificatePath);
        privateKeyPath = normalize(privateKeyPath);
        trustCertificatePath =
                normalize(trustCertificatePath);
        requirePositive(
                handshakeTimeout,
                "handshakeTimeout");
        requirePositive(
                reloadInterval,
                "reloadInterval");
        requirePositive(
                expiryWarningThreshold,
                "expiryWarningThreshold");
    }

    /**
     * 返回是否启用 TLS。
     *
     * @return TLS/MTLS 时返回 true
     */
    public boolean enabled() {
        return mode != RpcSecurityMode.PLAINTEXT;
    }

    /**
     * 返回是否启用双向 TLS。
     *
     * @return MTLS 时返回 true
     */
    public boolean mutualTls() {
        return mode == RpcSecurityMode.MTLS;
    }

    /**
     * 返回包含明确身份绑定的新配置，不修改当前值。
     *
     * @param value 非 null 身份选项
     * @return 新的不可变安全配置
     */
    public RpcTransportSecurityOptions withIdentity(RpcTransportIdentityOptions value) {
        return new RpcTransportSecurityOptions(mode, certificatePath, privateKeyPath,
                trustCertificatePath, hostnameVerification, handshakeTimeout, reloadInterval,
                expiryWarningThreshold, Optional.of(value));
    }

    /** 返回模式和身份配置状态，不输出文件路径或密钥材料。 */
    @Override
    public String toString() {
        return "RpcTransportSecurityOptions[mode=" + mode + ", identityConfigured=" + identity.isPresent() + "]";
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }

    private static void requirePositive(
            Duration value,
            String name) {
        if (value == null
                || value.isNegative()
                || value.isZero()) {
            throw new IllegalArgumentException(
                    name + " must be positive");
        }
        try {
            if (value.toNanos() <= 0 || value.toMillis() <= 0) {
                throw new IllegalArgumentException(name + " must be at least one millisecond");
            }
        } catch (ArithmeticException failure) {
            throw new IllegalArgumentException(name + " must be finite");
        }
    }
}
