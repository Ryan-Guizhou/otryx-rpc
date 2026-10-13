package com.peachsoft.otryx.core;

import com.peachsoft.otryx.api.RpcAuthorizationContext;
import com.peachsoft.otryx.transport.ResponseByteBudget;
import java.time.Duration;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
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
 * 授权槽位直到原始 CompletionStage 实际完成才归还；
 * 请求帧字节额度在授权逻辑终态归还；关闭不等待第三方任务。
 *
 * @Author OpenAI
 * @Version 1.0.0-SNAPSHOT
 * @CreateTime 2026/10/13 13:43
 */
final class RpcAuthorizationGate implements AutoCloseable {
    enum Decision {
        ALLOW, DENY, OVERLOADED, DEADLINE_EXCEEDED
    }

    private final RpcAuthorizer authorizer;
    private final int maxPending;
    private final Duration timeout;
    private final ResponseByteBudget pendingRequestBytes;
    private final AtomicInteger active = new AtomicInteger();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Set<CompletableFuture<Decision>> pendingResults =
            ConcurrentHashMap.newKeySet();
    private final ScheduledThreadPoolExecutor timeoutExecutor =
            new ScheduledThreadPoolExecutor(
                    1, Thread.ofPlatform().daemon().name("otryx-auth-timeout-", 0).factory());

    RpcAuthorizationGate(
            RpcAuthorizer authorizer,
            int maxPending,
            long maxBufferedRequestBytes,
            Duration timeout) {
        this.authorizer = Objects.requireNonNull(authorizer, "authorizer");
        if (maxPending <= 0 || timeout == null || timeout.isNegative()
                || timeout.isZero() || timeout.compareTo(Duration.ofDays(1)) > 0) {
            throw new IllegalArgumentException("Invalid RPC authorization capacity or timeout");
        }
        this.maxPending = maxPending;
        this.pendingRequestBytes = new ResponseByteBudget(maxBufferedRequestBytes);
        this.timeout = timeout;
        timeoutExecutor.setRemoveOnCancelPolicy(true);
    }

    /**
     * 非阻塞授权，第三方策略结束之前始终占用授权槽位。
     *
     * @param context 连接身份及服务方法
     * @param requestFrameBytes 等待策略结束期间持有的完整原始请求帧字节数
     * @param remainingRequestNanos 调用方的剩余单调时间预算，Long.MAX_VALUE 表示未指定
     * @return 授权结果
     */
    CompletionStage<Decision> authorize(
            RpcAuthorizationContext context,
            int requestFrameBytes,
            long remainingRequestNanos) {
        Objects.requireNonNull(context, "context");
        if (remainingRequestNanos <= 0L) {
            return CompletableFuture.completedFuture(Decision.DEADLINE_EXCEEDED);
        }
        ResponseByteBudget.Lease frameLease =
                pendingRequestBytes.tryAcquire(requestFrameBytes);
        if (frameLease == null) {
            return CompletableFuture.completedFuture(Decision.OVERLOADED);
        }
        if (!tryAcquire()) {
            frameLease.close();
            return CompletableFuture.completedFuture(Decision.OVERLOADED);
        }
        CompletionStage<Boolean> work;
        try {
            work = Objects.requireNonNull(authorizer.authorize(context), "authorization stage");
        } catch (RuntimeException error) {
            active.decrementAndGet();
            frameLease.close();
            return CompletableFuture.completedFuture(Decision.DENY);
        }

        CompletableFuture<Decision> result = new CompletableFuture<>();
        // 先登记结果，再检查并发关闭：避免 shutdown 丢失刚刚创建的 Future。
        pendingResults.add(result);
        // 授权逻辑终态后不再持有原始 Frame；策略真实结束才归还并发额度。
        result.whenComplete((ignored, error) -> {
            frameLease.close();
            pendingResults.remove(result);
        });
        if (closed.get()) {
            result.complete(Decision.DENY);
        }
        AtomicBoolean settled = new AtomicBoolean();
        try {
            work.whenComplete((allowed, error) -> {
                if (settled.compareAndSet(false, true)) {
                    active.decrementAndGet();
                }
                result.complete(error == null && Boolean.TRUE.equals(allowed)
                        ? Decision.ALLOW : Decision.DENY);
            });
        } catch (RuntimeException error) {
            if (settled.compareAndSet(false, true)) {
                active.decrementAndGet();
            }
            result.complete(Decision.DENY);
        }
        // 完成态 fast path：无需再向定时器队列插入和取消任务。
        if (result.isDone()) {
            return result;
        }
        long configuredTimeoutNanos = timeout.toNanos();
        boolean deadlineFirst = remainingRequestNanos <= configuredTimeoutNanos;
        long waitNanos = Math.min(remainingRequestNanos, configuredTimeoutNanos);
        try {
            ScheduledFuture<?> timer = timeoutExecutor.schedule(
                    () -> result.complete(deadlineFirst
                            ? Decision.DEADLINE_EXCEEDED
                            : Decision.DENY),
                    waitNanos, TimeUnit.NANOSECONDS);
            result.whenComplete((ignored, error) -> timer.cancel(false));
        } catch (RejectedExecutionException error) {
            result.complete(Decision.DENY);
        }
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

    /**
     * 返回正在等待逻辑终态的原始请求帧字节数，供关闭资源回归测试使用。
     *
     * @return 尚未释放的授权请求字节数
     */
    long pendingFrameBytes() {
        return pendingRequestBytes.reservedBytes();
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            // 完成 Future 使调用方解除对 request/raw frame 的继续持有。
            // 未配合取消的第三方策略仍占并发额度，直到其 Stage 真实完成。
            pendingResults.forEach(result -> result.complete(Decision.DENY));
            timeoutExecutor.shutdownNow();
        }
    }
}
