package com.peachsoft.otryx.api;

import java.util.Objects;

/**
 * 由可信 Transport 握手结果构造的 RPC 对端身份，不读取业务报文的自述身份。
 *
 * <p>只有 Transport 在验证了 mTLS 客户端证书之后才能将连接视为可信。
 * 自定义 Transport 必须从可信连接校验结果创建此对象。
 *
 * @param principal 经 TLS 握手验证的证书主体名称，可为空（SAN-only 证书）
 * @param certificateSha256 叶子证书 DER 的 SHA-256 十六进制指纹
 * @Author OpenAI
 * @Version 1.0.0-SNAPSHOT
 * @CreateTime 2026/10/13 13:43
 */
public record RpcPeerIdentity(String principal, String certificateSha256) {

    private static final RpcPeerIdentity ANONYMOUS = new RpcPeerIdentity("", "");

    /** 空 Subject 允许使用已验证的证书指纹进行身份授权，防止 SAN-only 连接被误拒。 */
    public RpcPeerIdentity {
        Objects.requireNonNull(principal, "principal");
        Objects.requireNonNull(certificateSha256, "certificateSha256");
        if (!principal.isBlank() && certificateSha256.isBlank()) {
            throw new IllegalArgumentException(
                    "Certificate principal must include a verified fingerprint");
        }
    }

    /**
     * 未经过客户端证书验证的连接身份。
     *
     * @return 不可信的匿名连接身份
     */
    public static RpcPeerIdentity anonymous() {
        return ANONYMOUS;
    }

    /**
     * 从已被 Transport 验证的 mTLS 证书构造连接身份。
     *
     * @param principal 证书主体，可为空（例如 SAN-only 证书）
     * @param certificateSha256 已验证叶子证书的 DER SHA-256 指纹
     * @return 已认证连接身份
     */
    public static RpcPeerIdentity verifiedClientCertificate(
            String principal, String certificateSha256) {
        if (principal == null
                || certificateSha256 == null || certificateSha256.isBlank()) {
            throw new IllegalArgumentException(
                    "Verified client certificate must have a fingerprint");
        }
        return new RpcPeerIdentity(principal, certificateSha256);
    }

    /**
     * 身份是否来自已验证的客户端证书。
     *
     * @return 已验证时为 true
     */
    public boolean authenticated() {
        return !certificateSha256.isBlank();
    }
}
