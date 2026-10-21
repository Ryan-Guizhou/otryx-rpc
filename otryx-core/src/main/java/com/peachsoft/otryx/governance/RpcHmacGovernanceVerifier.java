package com.peachsoft.otryx.governance;

import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Objects;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * 不读取本地密钥文件的 HMAC-SHA256 治理签名验证器。
 *
 * <p>部署方必须从受保护秘密来源构造实例，不得将 HMAC 密钥写进
 * Starter YAML/Registry 元数据。发行人固定白名单，签名固定时比较。
 *
 * @Author OpenAI
 * @Version 1.0.0-SNAPSHOT
 * @CreateTime 2026/10/21 17:30
 */
public final class RpcHmacGovernanceVerifier implements RpcGovernanceVerifier {
    private static final String ALGORITHM = "HmacSHA256";
    private static final int MIN_KEY_BYTES = 32;
    private final String trustedIssuer;
    private final SecretKeySpec key;

    /**
     * 仅复制密钥并保存白名单身份，不持有调用方可更改的密钥数组。
     *
     * @param trustedIssuer 部署方授权的发布来源
     * @param secret 由安全配置通道提供的至少 32 字节随机密钥
     */
    public RpcHmacGovernanceVerifier(String trustedIssuer, byte[] secret) {
        this.trustedIssuer = Objects.requireNonNull(trustedIssuer, "trustedIssuer");
        Objects.requireNonNull(secret, "secret");
        if (trustedIssuer.isBlank() || secret.length < MIN_KEY_BYTES) {
            throw new IllegalArgumentException("Invalid governance HMAC verifier");
        }
        key = new SecretKeySpec(secret.clone(), ALGORITHM);
    }

    /** 验证发行人、完整快照的正文与固定时间认证码。 */
    @Override
    public boolean verify(RpcGovernanceSignedUpdate update) {
        Objects.requireNonNull(update, "update");
        if (!trustedIssuer.equals(update.issuer())) {
            return false;
        }
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(key);
            byte[] expected = mac.doFinal(RpcGovernanceCanonical.encode(update.issuer(), update.snapshot()));
            return MessageDigest.isEqual(expected, update.signature());
        } catch (GeneralSecurityException | IllegalArgumentException failure) {
            return false;
        }
    }
}
