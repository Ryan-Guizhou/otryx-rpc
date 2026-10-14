package com.peachsoft.otryx.core;

import com.peachsoft.otryx.api.RpcOverloadedException;
import com.peachsoft.otryx.api.RpcRemoteException;
import com.peachsoft.otryx.api.RpcStatus;
import com.peachsoft.otryx.api.RpcUnavailableException;
import com.peachsoft.otryx.observability.RpcRetryExhaustionReason;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * Consumer 重试决策所有者，统一判定幂等、错误类型、次数、额度和 Deadline。
 *
 * <p>本类只执行内存策略，不负责下一次 Attempt 的调度、取消或终态通知。
 * 保持原有顺序：重试额度在 Deadline 比较之前扣减，避免抽取时改变行为。
 *
 * @Author OpenAI
 * @Version 1.0.0-SNAPSHOT
 * @CreateTime 2026/10/14 17:05
 */
final class ClientRetryController {
    private static final int MAX_BACKOFF_SHIFT = 20;
    private final RpcClientResilienceOptions options;
    private final RetryBudget budget;

    /**
     * 创建复用 Consumer 原有重试额度的策略控制器。
     *
     * @param options 原有容错配置
     * @param budget Consumer 共享额度
     */
    ClientRetryController(RpcClientResilienceOptions options, RetryBudget budget) {
        this.options = Objects.requireNonNull(options, "options");
        this.budget = Objects.requireNonNull(budget, "budget");
    }

    /**
     * 判定是否应调度下一次 Attempt。
     *
     * @param idempotent 服务方法是否显式幂等
     * @param failure 当前失败
     * @param attempt 当前 Attempt 序号
     * @param deadlineNanos 逻辑调用的单调时钟截止点
     * @return 允许调度时包含延迟，否则返回可选耗尽原因
     */
    Decision decide(boolean idempotent, Throwable failure, int attempt, long deadlineNanos) {
        if (!idempotent || !isRetryable(failure)) {
            return Decision.stop(null);
        }
        if (attempt >= options.maxAttempts()) {
            return Decision.stop(RpcRetryExhaustionReason.MAX_ATTEMPTS);
        }
        if (!budget.tryAcquireRetry()) {
            return Decision.stop(RpcRetryExhaustionReason.BUDGET);
        }
        long remainingNanos = deadlineNanos - System.nanoTime();
        long delayMillis = retryDelayMillis(attempt);
        if (remainingNanos <= TimeUnit.MILLISECONDS.toNanos(delayMillis)) {
            return Decision.stop(RpcRetryExhaustionReason.DEADLINE);
        }
        return Decision.schedule(delayMillis);
    }

    /**
     * 判断某个错误是否允许作为幂等调用自动重试的触发条件。
     *
     * @param error 本次请求错误
     * @return 是否属于可重试基础设施错误
     */
    static boolean isRetryable(Throwable error) {
        if (error instanceof RpcUnavailableException || error instanceof RpcOverloadedException) {
            return true;
        }
        if (error instanceof RpcRemoteException remote) {
            return remote.status() == RpcStatus.UNAVAILABLE
                    || remote.status() == RpcStatus.OVERLOADED
                    || remote.status() == RpcStatus.INTERNAL_ERROR;
        }
        return false;
    }

    private long retryDelayMillis(int attempt) {
        long base = options.retryBaseBackoff().toMillis();
        long max = options.retryMaxBackoff().toMillis();
        if (base == 0L || max == 0L) {
            return 0L;
        }
        int shift = Math.min(Math.max(attempt - 1, 0), MAX_BACKOFF_SHIFT);
        long ceiling = base > (Long.MAX_VALUE >> shift)
                ? max : Math.min(max, base << shift);
        return ceiling <= 1L
                ? ceiling : ThreadLocalRandom.current().nextLong(ceiling + 1L);
    }

    /**
     * 单次重试评估结果，不能据此认定业务请求已终止。
     *
     * @param retry 是否调度下一个 Attempt
     * @param delayMillis 退避延迟毫秒数
     * @param exhaustionReason 耗尽时可选的低基数原因
     */
    record Decision(boolean retry, long delayMillis, RpcRetryExhaustionReason exhaustionReason) {

        /**
         * 返回停止重试的策略结果。
         *
         * @param reason 耗尽原因；非幂等或业务错误可为 null
         * @return 拒绝安排下一次 Attempt
         */
        static Decision stop(RpcRetryExhaustionReason reason) {
            return new Decision(false, 0L, reason);
        }

        /**
         * 返回可调度的重试策略结果。
         *
         * @param millis 下一次重试延迟
         * @return 允许的重试决策
         */
        static Decision schedule(long millis) {
            return new Decision(true, millis, null);
        }
    }
}
