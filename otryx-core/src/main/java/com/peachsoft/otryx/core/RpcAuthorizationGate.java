package com.peachsoft.otryx.core;

import com.peachsoft.otryx.api.RpcAuthorizationContext;
import com.peachsoft.otryx.api.RpcAuthorizationDecision;
import com.peachsoft.otryx.transport.ResponseByteBudget;
import java.time.Duration;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

/**
 * 有界隔离授权策略方法体，先建立超时再提交真实工作。
 *
 * <p>授权槽位同时等待方法体退出与原始 Stage 完成。超时或取消只终结逻辑结果，
 * 不能证明真实第三方工作已停止。只有尚未运行的 owned task 可以明确放弃并归还槽位。
 * 请求帧字节预算仍在逻辑授权终态归还，不声明后续业务的内存租约。
 *
 * @Author OpenAI
 * @Version 1.0.0-SNAPSHOT
 * @CreateTime 2026/10/13 13:43
 */
final class RpcAuthorizationGate implements AutoCloseable {
    enum Decision {
        ALLOW, DENY, OVERLOADED, DEADLINE_EXCEEDED
    }

    record Result(Decision decision, RpcAuthorizationDecision authorization) {
        private static Result denied(Decision decision) {
            return new Result(decision, RpcAuthorizationDecision.deny());
        }
    }

    private final RpcAuthorizer authorizer;
    private final int maxPending;
    private final long timeoutNanos;
    private final ResponseByteBudget pendingRequestBytes;
    private final AtomicInteger active = new AtomicInteger();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Set<CompletableFuture<Result>> pendingResults = ConcurrentHashMap.newKeySet();
    private final ScheduledThreadPoolExecutor timeoutExecutor = new ScheduledThreadPoolExecutor(
            1, Thread.ofPlatform().daemon().name("otryx-auth-timeout-", 0).factory());
    private final ThreadPoolExecutor workExecutor;

    RpcAuthorizationGate(
            RpcAuthorizer authorizer,
            int maxPending,
            long maxBufferedRequestBytes,
            Duration timeout) {
        this(authorizer, maxPending, maxBufferedRequestBytes, timeout,
                Math.min(maxPending, Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors()))));
    }

    RpcAuthorizationGate(
            RpcAuthorizer authorizer, int maxPending, long maxBufferedRequestBytes, Duration timeout, int workers) {
        this.authorizer = Objects.requireNonNull(authorizer, "authorizer");
        RpcSecurityChecks.positiveFinite(timeout, "authorizationTimeout");
        if (maxPending <= 0 || workers <= 0 || workers > maxPending || timeout.compareTo(Duration.ofDays(1)) > 0) {
            throw new IllegalArgumentException("Invalid RPC authorization capacity or timeout");
        }
        this.maxPending = maxPending;
        this.pendingRequestBytes = new ResponseByteBudget(maxBufferedRequestBytes);
        this.timeoutNanos = timeout.toNanos();
        this.workExecutor = new ThreadPoolExecutor(workers, workers, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(maxPending),
                Thread.ofPlatform().daemon().name("otryx-authorization-", 0).factory(),
                new ThreadPoolExecutor.AbortPolicy());
        timeoutExecutor.setRemoveOnCancelPolicy(true);
    }

    /**
     * 在原请求预算内异步调用授权策略；此方法不执行第三方策略体。
     *
     * @param context 已验证来源的有限授权上下文
     * @param requestFrameBytes 逻辑授权等待所保留的请求帧字节数
     * @param remainingRequestNanos 原请求剩余预算，Long.MAX_VALUE 表示未指定
     * @return 逻辑结果，ALLOW 仍须在执行边界复核有效期
     */
    CompletionStage<Result> authorize(
            RpcAuthorizationContext context, int requestFrameBytes, long remainingRequestNanos) {
        return authorize(context, requestFrameBytes, remainingRequestNanos, () -> true);
    }

    CompletionStage<Result> authorize(
            RpcAuthorizationContext context, int requestFrameBytes, long remainingRequestNanos,
            BooleanSupplier mayInvokePolicy) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(mayInvokePolicy, "mayInvokePolicy");
        long startedNanos = System.nanoTime();
        if (remainingRequestNanos <= 0L) {
            return CompletableFuture.completedFuture(Result.denied(Decision.DEADLINE_EXCEEDED));
        }
        ResponseByteBudget.Lease frameLease = pendingRequestBytes.tryAcquire(requestFrameBytes);
        if (frameLease == null) {
            return CompletableFuture.completedFuture(Result.denied(Decision.OVERLOADED));
        }
        if (!tryAcquire()) {
            frameLease.close();
            return CompletableFuture.completedFuture(Result.denied(Decision.OVERLOADED));
        }
        CompletableFuture<Result> result = new CompletableFuture<>();
        AuthorizationWork work = new AuthorizationWork(context, result, mayInvokePolicy);
        CompletableFuture<Result> published = new CompletableFuture<>() {
            @Override
            public boolean cancel(boolean mayInterruptIfRunning) {
                result.cancel(false);
                return isCancelled();
            }
        };
        pendingResults.add(result);
        result.whenComplete((outcome, failure) -> {
            frameLease.close();
            pendingResults.remove(result);
            work.abandon();
            // 对外完成必须晚于 owned 清理；同一 Future 的 dependent 没有先于观察者运行的保证。
            if (failure == null) {
                published.complete(outcome);
            } else {
                published.completeExceptionally(failure);
            }
        });
        if (closed.get()) {
            result.complete(Result.denied(Decision.DENY));
            return published;
        }
        boolean deadlineFirst = remainingRequestNanos <= timeoutNanos;
        long remainingWait = Math.min(remainingRequestNanos, timeoutNanos) - (System.nanoTime() - startedNanos);
        try {
            ScheduledFuture<?> timer = timeoutExecutor.schedule(
                    () -> result.complete(Result.denied(deadlineFirst
                            ? Decision.DEADLINE_EXCEEDED : Decision.DENY)),
                    Math.max(0L, remainingWait), TimeUnit.NANOSECONDS);
            result.whenComplete((ignored, failure) -> timer.cancel(false));
            if (!result.isDone()) {
                workExecutor.execute(work);
            }
        } catch (RejectedExecutionException failure) {
            result.complete(Result.denied(Decision.DENY));
        }
        return published;
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
     * 返回逻辑授权仍持有的帧字节数。
     *
     * @return 尚未释放的授权帧字节数
     */
    long pendingFrameBytes() {
        return pendingRequestBytes.reservedBytes();
    }

    int activeAuthorizations() {
        return active.get();
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            pendingResults.forEach(result -> result.complete(Result.denied(Decision.DENY)));
            workExecutor.shutdownNow().forEach(task -> ((AuthorizationWork) task).abandon());
            timeoutExecutor.shutdownNow();
        }
    }

    private final class AuthorizationWork implements Runnable {
        private static final int QUEUED = 0;
        private static final int RUNNING = 1;
        private static final int BODY_EXITED = 2;
        private static final int ABANDONED = 3;
        private final RpcAuthorizationContext context;
        private final CompletableFuture<Result> result;
        private final BooleanSupplier mayInvokePolicy;
        private final AtomicInteger lifecycle = new AtomicInteger(QUEUED);
        private final AtomicBoolean stageCompleted = new AtomicBoolean();
        private final AtomicBoolean released = new AtomicBoolean();

        private AuthorizationWork(
                RpcAuthorizationContext context, CompletableFuture<Result> result, BooleanSupplier mayInvokePolicy) {
            this.context = context;
            this.result = result;
            this.mayInvokePolicy = mayInvokePolicy;
        }

        @Override
        public void run() {
            if (!lifecycle.compareAndSet(QUEUED, RUNNING)) {
                return;
            }
            CompletionStage<RpcAuthorizationDecision> rawStage = null;
            try {
                if (closed.get() || result.isDone() || !mayInvokePolicy.getAsBoolean()) {
                    stageCompleted.set(true);
                    result.complete(Result.denied(Decision.DENY));
                    return;
                }
                rawStage = Objects.requireNonNull(authorizer.authorize(context), "authorization stage");
                rawStage.whenComplete((authorization, failure) -> {
                    stageCompleted.set(true);
                    releaseIfFinished();
                    result.complete(failure == null && authorization != null && authorization.allowed()
                            ? new Result(Decision.ALLOW, authorization) : Result.denied(Decision.DENY));
                });
            } catch (Throwable failure) {
                if (rawStage == null) {
                    stageCompleted.set(true);
                }
                // 无法观察的异常 Stage 不被伪装成真实结束；它仍受总工作槽位上限约束。
                result.complete(Result.denied(Decision.DENY));
            } finally {
                lifecycle.set(BODY_EXITED);
                releaseIfFinished();
            }
        }

        private void abandon() {
            if (lifecycle.compareAndSet(QUEUED, ABANDONED)) {
                workExecutor.remove(this);
                if (released.compareAndSet(false, true)) {
                    active.decrementAndGet();
                }
            }
        }

        private void releaseIfFinished() {
            if (lifecycle.get() == BODY_EXITED && stageCompleted.get() && released.compareAndSet(false, true)) {
                active.decrementAndGet();
            }
        }
    }
}
