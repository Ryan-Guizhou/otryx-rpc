package com.peachsoft.otryx.api;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * 可信 Transport 在实际双向 TLS 验证后交付的不可变有限连接身份。
 *
 * <p>该对象不是 Wire DTO。应用身份只来自叶证书绑定，不接收 Subject 或报文自述身份。
 * 同时保存壁钟和单调时间锚点，壁钟回退不能恢复已经消耗的连接有效期。
 *
 * @Author OpenAI
 * @Version 1.0.0-SNAPSHOT
 * @CreateTime 2026/10/13 13:43
 */
public final class RpcPeerIdentity {
    private static final RpcPeerIdentity ANONYMOUS = new RpcPeerIdentity();
    private final RpcCertificateBinding binding;
    private final Instant notBefore;
    private final Instant notAfter;
    private final Instant validUntil;
    private final long verifiedAtNanos;
    private final long initialRemainingNanos;

    private RpcPeerIdentity() {
        binding = null;
        notBefore = null;
        notAfter = null;
        validUntil = null;
        verifiedAtNanos = 0;
        initialRemainingNanos = 0;
    }

    private RpcPeerIdentity(
            RpcCertificateBinding binding,
            Instant notBefore,
            Instant notAfter,
            Instant validUntil,
            long verifiedAtNanos,
            long initialRemainingNanos) {
        this.binding = binding;
        this.notBefore = notBefore;
        this.notAfter = notAfter;
        this.validUntil = validUntil;
        this.verifiedAtNanos = verifiedAtNanos;
        this.initialRemainingNanos = initialRemainingNanos;
    }

    /**
     * 返回没有认证凭据的身份。
     *
     * @return 共享不可变匿名身份
     */
    public static RpcPeerIdentity anonymous() {
        return ANONYMOUS;
    }

    /**
     * 从可信 Adapter 已完成的双向 TLS 与本地指纹绑定验证构造身份。
     *
     * @param binding 实际叶证书命中的本地绑定
     * @param certificateNotBefore 实际叶证书生效时刻
     * @param certificateNotAfter 实际叶证书失效时刻
     * @param verifiedAt 本次实际验证的壁钟时刻
     * @param verifiedAtNanos 同一次验证的单调时间锚点
     * @return 有限的已认证连接身份
     */
    public static RpcPeerIdentity verifiedMutualTls(
            RpcCertificateBinding binding,
            Instant certificateNotBefore,
            Instant certificateNotAfter,
            Instant verifiedAt,
            long verifiedAtNanos) {
        Objects.requireNonNull(binding, "binding");
        RpcIdentityTime.requireFinite(certificateNotBefore, "certificateNotBefore");
        RpcIdentityTime.requireFinite(certificateNotAfter, "certificateNotAfter");
        RpcIdentityTime.requireFinite(verifiedAt, "verifiedAt");
        Instant until = certificateNotAfter.isBefore(binding.validUntil())
                ? certificateNotAfter : binding.validUntil();
        if (!certificateNotBefore.isBefore(certificateNotAfter)
                || verifiedAt.isBefore(certificateNotBefore) || !verifiedAt.isBefore(until)) {
            throw new IllegalArgumentException("Verified peer identity must be currently valid");
        }
        long remaining;
        try {
            remaining = Duration.between(verifiedAt, until).toNanos();
        } catch (ArithmeticException failure) {
            throw new IllegalArgumentException("Verified peer identity must have a finite lifetime");
        }
        if (remaining <= 0) {
            throw new IllegalArgumentException("Verified peer identity must have a positive lifetime");
        }
        return new RpcPeerIdentity(binding, certificateNotBefore, certificateNotAfter,
                until, verifiedAtNanos, remaining);
    }

    /**
     * 返回是否携带已验证结构；执行前仍须调用 validAt。
     *
     * @return 非匿名时为 true
     */
    public boolean authenticated() {
        return binding != null;
    }

    /**
     * 返回实际叶证书绑定。
     *
     * @return 匿名时为空
     */
    public Optional<RpcCertificateBinding> certificateBinding() {
        return Optional.ofNullable(binding);
    }

    /**
     * 返回可信的完整应用身份。
     *
     * @return 匿名时为空
     */
    public Optional<ApplicationKey> application() {
        return binding == null ? Optional.empty() : Optional.of(binding.application());
    }

    /**
     * 返回证书生效时刻。
     *
     * @return 匿名时为空
     */
    public Optional<Instant> certificateNotBefore() {
        return Optional.ofNullable(notBefore);
    }

    /**
     * 返回证书失效时刻。
     *
     * @return 匿名时为空
     */
    public Optional<Instant> certificateNotAfter() {
        return Optional.ofNullable(notAfter);
    }

    /**
     * 返回证书与绑定共同限定的失效时刻。
     *
     * @return 匿名时为空
     */
    public Optional<Instant> validUntil() {
        return Optional.ofNullable(validUntil);
    }

    /**
     * 按同一时间域复核当前壁钟和单调有效期。
     *
     * @param now 当前壁钟时刻
     * @param nowNanos 当前单调时刻
     * @return 两个期限均未到达且非匿名时为 true
     */
    public boolean validAt(Instant now, long nowNanos) {
        return remainingValidityNanos(now, nowNanos) > 0;
    }

    /**
     * 返回两个时钟约束下的剩余有效纳秒，不重置验证锚点。
     *
     * @param now 当前壁钟时刻
     * @param nowNanos 当前单调时刻
     * @return 已失效或匿名时为零
     */
    public long remainingValidityNanos(Instant now, long nowNanos) {
        Objects.requireNonNull(now, "now");
        if (binding == null || now.isBefore(notBefore) || !now.isBefore(validUntil)) {
            return 0;
        }
        long elapsed = nowNanos - verifiedAtNanos;
        if (elapsed < 0 || elapsed >= initialRemainingNanos) {
            return 0;
        }
        long wallRemaining;
        try {
            wallRemaining = Duration.between(now, validUntil).toNanos();
        } catch (ArithmeticException failure) {
            wallRemaining = Long.MAX_VALUE;
        }
        return Math.min(initialRemainingNanos - elapsed, wallRemaining);
    }

    /** 返回不包含证书或任意主体信息的安全诊断文本。 */
    @Override
    public String toString() {
        return "RpcPeerIdentity[authenticated=" + authenticated() + "]";
    }
}
