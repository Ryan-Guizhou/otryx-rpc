package com.peachsoft.otryx.core;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 单次逻辑 RPC 调用使用的本机单调截止时间与显式线程上下文。
 *
 * <p>ThreadLocal 不可继承；跨自定义线程池必须调用 wrap 或显式 attach。
 * 框架收到的远端时限由剩余预算重建，绝不直接比较异地主机单调时钟。
 * Metadata 不携带可信 Peer 身份，也不存放业务对象。
 *
 * @Author OpenAI
 * @Version 1.0.0-SNAPSHOT
 * @CreateTime 2026/10/13 17:03
 */
public final class RpcCallContext {
    private static final ThreadLocal<RpcCallContext> CURRENT = new ThreadLocal<>();
    private final RpcCallOptions options;
    private final long deadlineNanos;

    private RpcCallContext(RpcCallOptions options, long deadlineNanos) {
        this.options = Objects.requireNonNull(options, "options");
        this.deadlineNanos = deadlineNanos;
    }

    /**
     * 读取本线程上下文；未开启作用域时返回 null。
     *
     * @return 当前线程上下文或 null
     */
    public static RpcCallContext current() {
        return CURRENT.get();
    }

    /**
     * 以覆盖选项开启上下文，嵌套时不扩大父级剩余时限。
     *
     * @param options 超时和 Metadata 覆盖选项
     * @return 必须关闭的线程作用域
     */
    public static RpcCallScope open(RpcCallOptions options) {
        Objects.requireNonNull(options, "options");
        RpcCallContext parent = current();
        Map<String, String> merged = new LinkedHashMap<>();
        if (parent != null) {
            merged.putAll(parent.metadata());
        }
        merged.putAll(options.metadata());
        RpcCallOptions actual = new RpcCallOptions(options.timeout(), merged);
        long deadline = parent == null ? Long.MAX_VALUE : parent.deadlineNanos;
        if (options.timeout() != null) {
            long nowNanos = System.nanoTime();
            long timeoutNanos = options.timeout().toNanos();
            if (deadline == Long.MAX_VALUE || timeoutNanos < deadline - nowNanos) {
                deadline = nowNanos + timeoutNanos;
            }
        }
        return attach(new RpcCallContext(actual, deadline));
    }

    /**
     * 绑定一个捕获的上下文到当前线程，关闭时恢复进入前的线程值。
     *
     * @param context 待绑定上下文；null 表示临时清理线程上下文
     * @return 必须在本线程关闭的作用域
     */
    public static RpcCallScope attach(RpcCallContext context) {
        RpcCallContext previous = current();
        Thread owner = Thread.currentThread();
        if (context == null) {
            CURRENT.remove();
        } else {
            CURRENT.set(context);
        }
        AtomicBoolean closed = new AtomicBoolean();
        return () -> {
            if (Thread.currentThread() != owner) {
                throw new IllegalStateException("RPC call scope must close on its owner thread");
            }
            if (!closed.get() && CURRENT.get() != context) {
                throw new IllegalStateException("RPC call scopes must close in reverse order");
            }
            if (closed.compareAndSet(false, true)) {
                if (previous == null) {
                    CURRENT.remove();
                } else {
                    CURRENT.set(previous);
                }
            }
        };
    }

    /**
     * 为 Provider 创建本地时限上下文，去掉 Wire 的保留 deadline 字段。
     *
     * @param deadlineNanos 接收时间加上剩余预算的本机单调时限；MAX 表示无限制
     * @param metadata 已解析的 Wire Metadata
     * @return Provider 调用上下文
     */
    public static RpcCallContext inbound(long deadlineNanos, Map<String, String> metadata) {
        Map<String, String> safe = new LinkedHashMap<>();
        if (metadata != null) {
            metadata.forEach((key, value) -> {
                if (!"deadlineEpochMillis".equals(key)
                        && !"timeoutBudgetMillis".equals(key)) {
                    safe.put(key, value);
                }
            });
        }
        return new RpcCallContext(new RpcCallOptions(null, safe), deadlineNanos);
    }

    /**
     * 将当前上下文显式传播到 Runnable 的执行期间。
     *
     * @param task 待执行任务
     * @return 包装后的任务；每次执行结束恢复目标线程原上下文
     */
    public static Runnable wrap(Runnable task) {
        return wrap(current(), task);
    }

    /**
     * 在框架内部执行边界明确指定需要传播的调用上下文。
     *
     * <p>任务可能在 EventLoop、重试线程或受限完成线程运行，不隐式
     * 继承调用方的线程变量。
     *
     * @param captured 显式捕获的上下文；可为 null
     * @param task 待执行任务
     * @return 绑定且最终恢复上下文的包装任务
     */
    public static Runnable wrap(RpcCallContext captured, Runnable task) {
        Objects.requireNonNull(task, "task");
        return () -> {
            try (RpcCallScope ignored = attach(captured)) {
                task.run();
            }
        };
    }

    /**
     * 返回当前调用的覆盖选项。
     *
     * @return 不可变覆盖选项
     */
    public RpcCallOptions options() {
        return options;
    }

    /**
     * 返回应用 Metadata，不包含 Wire 截止时间保留字段。
     *
     * @return 不可变 Metadata
     */
    public Map<String, String> metadata() {
        return options.metadata();
    }

    /**
     * 返回当前单调截止时间的剩余纳秒；MAX 表示父级无限制。
     *
     * @return 非负剩余纳秒
     */
    public long remainingNanos() {
        return deadlineNanos == Long.MAX_VALUE
                ? Long.MAX_VALUE : Math.max(0L, deadlineNanos - System.nanoTime());
    }

    /**
     * 返回剩余预算。
     *
     * @return 剩余时间；无父预算返回 Duration.ofNanos(Long.MAX_VALUE)
     */
    public Duration remaining() {
        return Duration.ofNanos(remainingNanos());
    }

    /**
     * 返回本机单调截止时间；只能在同一 JVM 中比较。
     *
     * @return 单调截止时间或 Long.MAX_VALUE
     */
    public long deadlineNanos() {
        return deadlineNanos;
    }
}
