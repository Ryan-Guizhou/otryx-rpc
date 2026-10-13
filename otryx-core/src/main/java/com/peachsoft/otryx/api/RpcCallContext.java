package com.peachsoft.otryx.api;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * 一条逻辑 RPC 调用的不可变快照，维护本机单调 Deadline 和可透传 Metadata。
 *
 * <p>无上下文时 `current()` 返回 null。Scope 不使用 InheritableThreadLocal，
 * 用户线程池或 CompletableFuture 不继承该上下文，必须显式使用 wrap/
 * activate。RPC 发送端始终取所选方法预算和父级剩余预算两者较小者。
 *
 * @Author OpenAI
 * @Version 1.0.0-SNAPSHOT
 * @CreateTime 2026/10/13 16:10
 */
public final class RpcCallContext {

    private static final String EPOCH_KEY = "deadlineEpochMillis";
    private static final String BUDGET_KEY = "timeoutBudgetMillis";
    private static final ThreadLocal<RpcCallContext> CURRENT = new ThreadLocal<>();

    private final long deadlineNanos;
    private final Duration timeoutOverride;
    private final Map<String, String> metadata;

    private RpcCallContext(
            long deadlineNanos, Duration timeoutOverride, Map<String, String> metadata) {
        this.deadlineNanos = deadlineNanos;
        this.timeoutOverride = timeoutOverride;
        this.metadata = Map.copyOf(metadata);
    }

    /**
     * 读取当前线程的逻辑调用上下文。
     *
     * @return 当前上下文，未显式创建时为 null
     */
    public static RpcCallContext current() {
        return CURRENT.get();
    }

    /**
     * 以单次选项建立新作用域。父级 Deadline 不允许被覆盖延长。
     *
     * @param options 单次选项；null 等同 DEFAULT
     * @return 关闭时恢复父级的独占 Scope
     */
    public static RpcCallScope open(RpcCallOptions options) {
        RpcCallOptions selected = options == null ? RpcCallOptions.DEFAULT : options;
        RpcCallContext parent = current();
        Duration override = selected.timeout() != null
                ? selected.timeout()
                : parent == null ? null : parent.timeoutOverride;
        long deadline = parent == null ? Long.MAX_VALUE : parent.deadlineNanos;
        if (selected.timeout() != null) {
            deadline = Math.min(deadline,
                    saturatingAdd(System.nanoTime(), selected.timeout().toNanos()));
        }
        Map<String, String> values = new LinkedHashMap<>();
        if (parent != null) {
            values.putAll(parent.metadata);
        }
        values.putAll(selected.metadata());
        return new RpcCallContext(deadline, override, values).activate();
    }

    /**
     * 将远程传来的相对预算转换为本机单调 Deadline，不读取对端系统时钟。
     *
     * <p>应使用在 Provider 首次收到 Frame 时捕获的 nanoTime。保留的
     * Deadline Metadata 不继续注入用户属性，避免编码阶段覆盖框架字段。
     *
     * @param receivedAtNanos Provider 接收帧时的本机单调时间
     * @param remainingBudgetNanos 远程剩余时限；Long.MAX_VALUE 表示无限制
     * @param receivedMetadata 原始请求扩展 Metadata，可包含框架保留字段
     * @return 不依赖现有 ThreadLocal 的入站上下文快照
     */
    public static RpcCallContext inbound(
            long receivedAtNanos,
            long remainingBudgetNanos,
            Map<String, String> receivedMetadata) {
        long deadline = remainingBudgetNanos == Long.MAX_VALUE
                ? Long.MAX_VALUE
                : saturatingAdd(receivedAtNanos, Math.max(0L, remainingBudgetNanos));
        Map<String, String> extras = new LinkedHashMap<>();
        if (receivedMetadata != null) {
            receivedMetadata.forEach((key, value) -> {
                if (!EPOCH_KEY.equals(key) && !BUDGET_KEY.equals(key)) {
                    extras.put(key, value);
                }
            });
        }
        return new RpcCallContext(deadline, null, extras);
    }

    /**
     * 将快照装入当前线程，返回可自动恢复的 Scope。
     *
     * @return 当前线程的上下文恢复令牌
     */
    public RpcCallScope activate() {
        RpcCallContext previous = CURRENT.get();
        CURRENT.set(this);
        return new RpcCallScope(previous, this);
    }

    static void restore(RpcCallContext previous) {
        if (previous == null) {
            CURRENT.remove();
        } else {
            CURRENT.set(previous);
        }
    }

    /**
     * 返回父级当前剩余的单调时间预算。
     *
     * @return 非负纳秒；没有 Deadline 时返回 Long.MAX_VALUE
     */
    public long remainingNanos() {
        return deadlineNanos == Long.MAX_VALUE
                ? Long.MAX_VALUE
                : Math.max(0L, deadlineNanos - System.nanoTime());
    }

    /**
     * 返回当前作用域的单次超时覆盖。
     *
     * @return 单次选项超时；null 表示方法/Client 默认
     */
    public Duration timeoutOverride() {
        return timeoutOverride;
    }

    /**
     * 返回独立于请求 Payload 的只读扩展 Metadata。
     *
     * @return 不可变 Metadata
     */
    public Map<String, String> metadata() {
        return metadata;
    }

    /**
     * 捕获当前 Context，在异步工作线程临时恢复，并在结束时还原其上下文。
     *
     * @param action 用户异步任务
     * @return 携带显式快照的任务
     */
    public static Runnable wrap(Runnable action) {
        Objects.requireNonNull(action, "action");
        RpcCallContext captured = current();
        return () -> {
            RpcCallContext previous = CURRENT.get();
            try {
                restore(captured);
                action.run();
            } finally {
                restore(previous);
            }
        };
    }

    /**
     * 在异步工作线程显式恢复捕获的 Context。
     *
     * @param action 用户异步任务
     * @param <T> 返回类型
     * @return 携带显式快照的 Supplier
     */
    public static <T> Supplier<T> wrap(Supplier<T> action) {
        Objects.requireNonNull(action, "action");
        RpcCallContext captured = current();
        return () -> {
            RpcCallContext previous = CURRENT.get();
            try {
                restore(captured);
                return action.get();
            } finally {
                restore(previous);
            }
        };
    }

    private static long saturatingAdd(long started, long nanoseconds) {
        if (nanoseconds < 0L) {
            throw new IllegalArgumentException("Deadline budget must not be negative");
        }
        long sum = started + nanoseconds;
        return sum < started ? Long.MAX_VALUE : sum;
    }
}
