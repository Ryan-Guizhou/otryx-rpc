package com.peachsoft.otryx.api;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * 有限的方法授权结果，许可不能延长请求或连接原有期限。
 *
 * @param outcome 允许或拒绝
 * @param validUntil ALLOW 的有限失效时刻，DENY 必须为空
 * @Author OpenAI
 * @Version 1.0.0-SNAPSHOT
 * @CreateTime 2026/10/19 11:18
 */
public record RpcAuthorizationDecision(Outcome outcome, Optional<Instant> validUntil) {

    /** 授权结果；不存在无期限许可。 */
    public enum Outcome {
        /** 授予截至 validUntil 的有限许可，实际执行边界仍须复核。 */
        ALLOW,
        /** 拒绝当前服务方法调用，不携带许可期限。 */
        DENY
    }

    /** 校验许可结构，过期结果由执行边界一致拒绝。 */
    public RpcAuthorizationDecision {
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(validUntil, "validUntil");
        if ((outcome == Outcome.ALLOW) != validUntil.isPresent()) {
            throw new IllegalArgumentException("Authorization outcome requires a matching finite expiry");
        }
        validUntil.ifPresent(value -> RpcIdentityTime.requireFinite(value, "validUntil"));
    }

    /**
     * 构造拒绝结果。
     *
     * @return 不携带期限的拒绝
     */
    public static RpcAuthorizationDecision deny() {
        return new RpcAuthorizationDecision(Outcome.DENY, Optional.empty());
    }

    /**
     * 构造受有限时刻约束的许可。
     *
     * @param validUntil 失效时刻，不允许无限哨兵
     * @return 有限许可，仍需在实际执行时复核
     */
    public static RpcAuthorizationDecision allowUntil(Instant validUntil) {
        return new RpcAuthorizationDecision(Outcome.ALLOW, Optional.of(validUntil));
    }

    /**
     * 返回结果是否声明允许；该值不代表当前仍有效。
     *
     * @return ALLOW 时为 true
     */
    public boolean allowed() {
        return outcome == Outcome.ALLOW;
    }
}
