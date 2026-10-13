package com.peachsoft.otryx.api;

import java.util.Objects;

/**
 * 由可信 Transport 握手结果构造的 RPC 对端身份，不读取业务报文的自述身份。
 *
 * <p>只有 Transport 在验证了 mTLS 客户端证书之后才能将连接视为可信。
 * 自定义 Transport 必须从可信连接校验结果创建此对象。
 *
 * @param principal 经 TLS 握手验证的证书主体名称
 * @param certificateSha256 叶子证书 DER 的 SHA-256 十六进制指纹
 * @Author OpenAI
 * @Version 1.0.0-SNAPSHOT
 * @CreateTime 2026/10/13 13:43
 */
public record RpcPeerIdentity(String principal, String certificateSha256) {

    private static final RpcPeerIdentity ANONYMOUS = new RpcPeerIdentity("", "");

    /** 校验身份字段必须成对出现，防止部分可信的错误状态。 */
    public RpcPeerIdentity {
        Objects.requireNonNull(principal, "principal");
        Objects.requireNonNull(certificateSha256, "certificateSha256");
        if (principal.isBlank() != certificateSha256.isBlank()) {
            throw new IllegalArgumentException(
                    "Peer certificate identity requires both subject and fingerprint");
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
     * @param principal 证书主体
     * @param certificateSha256 证书指纹
     * @return 已认证连接身份
     */
    public static RpcPeerIdentity verifiedClientCertificate(
            String principal, String certificateSha256) {
        if (principal == null || principal.isBlank()
                || certificateSha256 == null || certificateSha256.isBlank()) {
            throw new IllegalArgumentException(
                    "Verified client certificate must have subject and fingerprint");
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
