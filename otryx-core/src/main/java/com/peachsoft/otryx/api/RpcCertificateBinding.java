package com.peachsoft.otryx.api;

import java.time.Instant;
import java.util.Locale;
import java.util.Objects;

/**
 * 本地配置的叶证书 DER 指纹与完整应用身份的有限信任绑定。
 *
 * @param certificateSha256 64 位十六进制 SHA-256 指纹
 * @param application 绑定的完整应用身份
 * @param validUntil 绑定失效时刻，等于该时刻已失效
 * @Author OpenAI
 * @Version 1.0.0-SNAPSHOT
 * @CreateTime 2026/10/19 11:18
 */
public record RpcCertificateBinding(
        String certificateSha256,
        ApplicationKey application,
        Instant validUntil) {

    /** 防止 Subject、公钥哈希或无限期限冒充叶证书绑定。 */
    public RpcCertificateBinding {
        Objects.requireNonNull(certificateSha256, "certificateSha256");
        if (!certificateSha256.matches("[0-9a-fA-F]{64}")) {
            throw new IllegalArgumentException("Certificate binding requires a SHA-256 fingerprint");
        }
        certificateSha256 = certificateSha256.toLowerCase(Locale.ROOT);
        Objects.requireNonNull(application, "application");
        RpcIdentityTime.requireFinite(validUntil, "validUntil");
    }

    /** 返回不含证书、指纹或配置值的诊断文本。 */
    @Override
    public String toString() {
        return "RpcCertificateBinding[configured=true]";
    }
}
