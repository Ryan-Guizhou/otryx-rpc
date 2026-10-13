package com.peachsoft.otryx.core;

import com.peachsoft.otryx.api.RpcAuthorizationContext;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Provider 非阻塞授权的并发预算、超时与拒绝处理。
 *
 * <p>超过逻辑超时仅结束 RPC 响应，不等于真实授权工作已停止。
 * 配额直到原始 CompletionStage 实际完成才归还；关闭不等待第三方任务。
 *
 * @Author OpenAI
 * @Version 1.0.0-SNAPSHOT
 * @CreateTime 2026/10/13 13:43
 */
final class RpcAuthorizationGate implements AutoCloseable {
    enum Decision {
        ALLOW, DENY, OVERLOADED
    }

    private final RpcAuthorizer authorizer;
    private final int maxPending;
    private final Duration timeout;
    private final AtomicInteger active = new AtomicInteger();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final ScheduledThreadPoolExecutor timeoutExecutor =
            new ScheduledThreadPoolExecutor(
                    1, Thread.ofPlatform().daemon().name("otryx-auth-timeout-", 0).factory());

    RpcAuthorizationGate(RpcAuthorizer authorizer, int maxPending, Duration timeout) {
        this.authorizer = Objects.requireNonNull(authorizer, "authorizer");
        if (maxPending <= 0 || timeout == null || timeout.isNegative()
                || timeout.isZero() || timeout.compareTo(Duration.ofDays(1)) > 0) {
            throw new IllegalArgumentException("Invalid RPC authorization capacity or timeout");
        }
        this.maxPending = maxPending;
        this.timeout = timeout;
        timeoutExecutor.setRemoveOnCancelPolicy(true);
    }

    /**
     * 非阻塞授权，第三方策略结束之前始终占用授权槽位。
     *
     * @param context 连接身份及服务方法
     * @return 授权结果
     */
    CompletionStage<Decision> authorize(RpcAuthorizationContext context) {
        Objects.requireNonNull(context, "context");
        if (!tryAcquire()) {
            return CompletableFuture.completedFuture(Decision.OVERLOADED);
        }
        CompletionStage<Boolean> work;
        try {
            work = Objects.requireNonNull(authorizer.authorize(context), "authorization stage");
        } catch (RuntimeException error) {
            active.decrementAndGet();
            return CompletableFuture.completedFuture(Decision.DENY);
        }

        CompletableFuture<Decision> result = new CompletableFuture<>();
        ScheduledFuture<?> timer;
        try {
            timer = timeoutExecutor.schedule(
                    () -> result.complete(Decision.DENY),
                    Math.max(1L, timeout.toMillis()), TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException error) {
            work.whenComplete((ignored, failure) -> active.decrementAndGet());
            return CompletableFuture.completedFuture(Decision.DENY);
        }
        work.whenComplete((allowed, error) -> {
            timer.cancel(false);
            active.decrementAndGet();
            result.complete(error == null && Boolean.TRUE.equals(allowed)
                    ? Decision.ALLOW : Decision.DENY);
        });
        return result;
    }

    private boolean tryAcquire() {
        while (!closed.get()) {
            int current = active.get();
            if (current >= maxPending) {
                return false;
            }
            if (active.compareAndSet(current, current + 1)) {
                if (closed.get()) {
                    active.decrementAndGet();
                    return false;
                }
                return true;
            }
        }
        return false;
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            timeoutExecutor.shutdownNow();
        }
    }
}
