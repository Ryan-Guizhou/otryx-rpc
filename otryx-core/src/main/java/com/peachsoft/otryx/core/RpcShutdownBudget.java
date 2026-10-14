package com.peachsoft.otryx.core;

import java.util.Objects;

/**
 * Consumer 与 Provider 关闭流程共享的单调时钟总预算。
 *
 * <p>只负责预算，不宣称关闭 Stage 到期意味着业务线程已退出或 Lease 已释放。
 * 不同阶段必须共享单个实例，不能逐阶段重新计时。
 *
 * @Author OpenAI
 * @Version 1.0.0-SNAPSHOT
 * @CreateTime 2026/10/14 17:05
 */
final class RpcShutdownBudget {
    private final long startedAtNanos;
    private final long deadlineNanos;

    private RpcShutdownBudget(long startedAtNanos, long timeoutNanos) {
        this.startedAtNanos = startedAtNanos;
        this.deadlineNanos = startedAtNanos + timeoutNanos;
    }

    /**
     * 从一次关闭入口创建不可变预算。
     *
     * @param options 已校验的关闭选项
     * @return 单次关闭共享的预算
     */
    static RpcShutdownBudget start(RpcShutdownOptions options) {
        Objects.requireNonNull(options, "options");
        return new RpcShutdownBudget(System.nanoTime(), options.totalTimeout().toNanos());
    }

    /**
     * 返回原始关闭开始时间。
     *
     * @return 单调时钟纳秒时间戳
     */
    long startedAtNanos() {
        return startedAtNanos;
    }

    /**
     * 计算当前剩余总预算，不返回负数。
     *
     * @return 剩余纳秒
     */
    long remainingNanos() {
        return Math.max(0L, deadlineNanos - System.nanoTime());
    }
}
