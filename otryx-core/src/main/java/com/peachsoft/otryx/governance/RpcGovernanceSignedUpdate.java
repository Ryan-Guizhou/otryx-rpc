package com.peachsoft.otryx.governance;

import java.util.Objects;

/**
 * 一次完整签名治理事件；签名必须涵盖来源、单调版本和全部规则。
 *
 * <p>调用方不得直接信任来源字符串；只有外部注入的 verifier 完成签名验证后
 * 才允许发布。HMAC 采用 32 字节的 SHA-256 认证码。
 *
 * @param issuer 预授权的短来源标识，不等于认证身份
 * @param snapshot 完整的不可变快照
 * @param signature 完整负载的 HMAC-SHA256 认证码
 * @Author OpenAI
 * @Version 1.0.0-SNAPSHOT
 * @CreateTime 2026/10/21 17:30
 */
public record RpcGovernanceSignedUpdate(String issuer, RpcGovernanceSnapshot snapshot, byte[] signature) {
    private static final int MAX_ISSUER_CHARS = 64;
    private static final int SIGNATURE_BYTES = 32;

    /** 复制调用方数组，签名不能在提交后被篡改。 */
    public RpcGovernanceSignedUpdate {
        Objects.requireNonNull(issuer, "issuer");
        Objects.requireNonNull(snapshot, "snapshot");
        if (issuer.isBlank() || issuer.length() > MAX_ISSUER_CHARS
                || !issuer.matches("[A-Za-z0-9._-]+")) {
            throw new IllegalArgumentException("Invalid governance issuer");
        }
        signature = Objects.requireNonNull(signature, "signature").clone();
        if (signature.length != SIGNATURE_BYTES || snapshot.revision() <= 0L) {
            throw new IllegalArgumentException("Invalid signed governance event");
        }
    }

    /** 防止调用方更改已验证的签名字节。 */
    @Override
    public byte[] signature() {
        return signature.clone();
    }
}
