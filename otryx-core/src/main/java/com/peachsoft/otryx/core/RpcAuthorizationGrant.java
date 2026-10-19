package com.peachsoft.otryx.core;

import com.peachsoft.otryx.api.RpcAuthorizationContext;
import com.peachsoft.otryx.api.RpcAuthorizationDecision;
import com.peachsoft.otryx.api.RpcSecurityProfile;
import java.time.Duration;
import java.time.Instant;
import java.util.function.BooleanSupplier;

/**
 * 跨授权 Stage、EventLoop 与业务队列传递同一个接收时间锚定的有限许可。
 *
 * @Author OpenAI
 * @Version 1.0.0-SNAPSHOT
 * @CreateTime 2026/10/19 11:18
 */
final class RpcAuthorizationGrant {
    private final RpcAuthorizationContext context;
    private final RpcSecurityProfile profile;
    private final Instant validUntil;
    private final long receivedAtNanos;
    private final long maximumElapsedNanos;
    private final BooleanSupplier cancelled;

    private RpcAuthorizationGrant(
            RpcAuthorizationContext context, RpcSecurityProfile profile, Instant validUntil,
            long receivedAtNanos, long maximumElapsedNanos, BooleanSupplier cancelled) {
        this.context = context;
        this.profile = profile;
        this.validUntil = validUntil;
        this.receivedAtNanos = receivedAtNanos;
        this.maximumElapsedNanos = maximumElapsedNanos;
        this.cancelled = cancelled;
    }

    static RpcAuthorizationGrant create(
            RpcAuthorizationContext context, RpcAuthorizationDecision decision, RpcSecurityProfile profile,
            long receivedAtNanos, BooleanSupplier cancelled) {
        if (decision == null || !decision.allowed() || decision.validUntil().isEmpty()) {
            return null;
        }
        Instant grantedUntil = decision.validUntil().orElseThrow();
        Instant until = grantedUntil.isBefore(context.maximumValidUntil())
                ? grantedUntil : context.maximumValidUntil();
        long maximumElapsed;
        try {
            maximumElapsed = Duration.between(context.receivedAt(), until).toNanos();
        } catch (ArithmeticException failure) {
            return null;
        }
        if (maximumElapsed <= 0) {
            return null;
        }
        return new RpcAuthorizationGrant(context, profile, until, receivedAtNanos, maximumElapsed, cancelled);
    }

    boolean validAt(Instant now, long nowNanos) {
        long elapsed = nowNanos - receivedAtNanos;
        return !cancelled.getAsBoolean() && elapsed >= 0 && elapsed < maximumElapsedNanos
                && now.isBefore(validUntil) && peerValidAt(now, nowNanos);
    }

    boolean peerValidAt(Instant now, long nowNanos) {
        return !cancelled.getAsBoolean()
                && RpcSecurityChecks.providerPeer(profile, context.peer(), context.localApplication(), now, nowNanos);
    }
}
