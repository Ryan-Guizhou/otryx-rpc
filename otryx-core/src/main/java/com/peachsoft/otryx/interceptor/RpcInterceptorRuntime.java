package com.peachsoft.otryx.interceptor;

import com.peachsoft.otryx.api.RpcOverloadedException;
import com.peachsoft.otryx.api.RpcStatus;
import com.peachsoft.otryx.api.RpcUnavailableException;
import com.peachsoft.otryx.core.RpcCallContext;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Consumer/Provider 的有界拦截器调度器；按逻辑调用串行执行同一条拦截器链。
 *
 * <p>一次调用只取得一个 Admission 槽，直到其逆序终态回调真实结束才释放。
 * 每个会话最多同时排队一项 Worker 任务，所以全局有界任务队列不会被
 * 同一调用的 Retry/Cancel/Deadline 事件无限放大。关闭时拒绝新会话，
 * 已获准会话仍持有自己的 Worker/配额直到真正终结。
 *
 * @Author OpenAI
 * @Version 1.0.0-SNAPSHOT
 * @CreateTime 2026/10/21 16:00
 */
public final class RpcInterceptorRuntime implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger(RpcInterceptorRuntime.class);
    /** 默认同时持有拦截器生命周期的调用数量。 */
    public static final int DEFAULT_MAX_ACTIVE = 512;
    /** 默认隔离 Worker 数量。 */
    public static final int DEFAULT_WORKERS = 4;
    /** Hook 会话硬上限，避免错误配置造成无界队列或过多工作资源。 */
    public static final int MAX_ALLOWED_ACTIVE = 32768;
    /** Hook 工作线程硬上限，单端实例独立生效。 */
    public static final int MAX_ALLOWED_WORKERS = 64;

    private final List<RpcInterceptor> interceptors;
    private final ThreadPoolExecutor executor;
    private final Semaphore active;
    private final AtomicInteger activeCalls = new AtomicInteger();
    private final AtomicBoolean closing = new AtomicBoolean();

    /**
     * 启动时一次性排序扩展并准备有界调度资源，空列表不能创建运行时。
     *
     * @param configured 按 order 排序的拦截器源
     * @param workers 固定 Worker 数，1 至 64
     * @param maxActive 允许的并发逻辑会话数，1 至 32768
     * @param role 用于非敏感线程名称
     */
    public RpcInterceptorRuntime(List<RpcInterceptor> configured, int workers, int maxActive, String role) {
        Objects.requireNonNull(configured, "configured");
        if (configured.isEmpty() || configured.stream().anyMatch(Objects::isNull)
                || workers < 1 || workers > MAX_ALLOWED_WORKERS
                || maxActive < 1 || maxActive > MAX_ALLOWED_ACTIVE) {
            throw new IllegalArgumentException("Invalid RPC interceptor configuration");
        }
        ArrayList<RpcInterceptor> ordered = new ArrayList<>(configured);
        ordered.sort(Comparator.comparingInt(RpcInterceptor::order));
        interceptors = List.copyOf(ordered);
        active = new Semaphore(maxActive);
        AtomicInteger workersCreated = new AtomicInteger();
        executor = new ThreadPoolExecutor(
                workers, workers, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(maxActive),
                task -> {
                    Thread thread = new Thread(task,
                            "otryx-interceptor-" + role + "-" + workersCreated.incrementAndGet());
                    thread.setDaemon(true);
                    return thread;
                },
                new ThreadPoolExecutor.AbortPolicy());
    }

    /**
     * 原子保留整个拦截器调用生命周期的槽位，失败时不创建 Future 或队列节点。
     *
     * @param invocation 不含敏感信息的实际调用身份
     * @param scope 调用方捕获的上下文；允许为 null
     * @return 当前逻辑调用独占的 Session
     * @throws RpcOverloadedException 已关闭或容量耗尽
     */
    public Session open(RpcInvocation invocation, RpcCallContext scope) {
        Objects.requireNonNull(invocation, "invocation");
        if (closing.get() || !active.tryAcquire()) {
            throw new RpcOverloadedException("RPC interceptor admission capacity exhausted");
        }
        if (closing.get()) {
            active.release();
            throw new RpcOverloadedException("RPC interceptor runtime is closing");
        }
        activeCalls.incrementAndGet();
        return new Session(invocation, scope);
    }

    /** 读取真实存活的 Hook Session 数；正在执行的慢 Hook 仍计入。 */
    public int activeSessions() {
        return activeCalls.get();
    }

    /** 禁止新调用；真正执行完毕的终态回调会触发 Worker 关闭。 */
    @Override
    public void close() {
        if (closing.compareAndSet(false, true) && activeCalls.get() == 0) {
            executor.shutdown();
        }
    }

    /**
     * 一次逻辑调用的严格串行事件序列，不在 EventLoop 上调用自定义 Hook。
     *
     * <p>Session 由 Core 单个调用独占，Worker 依照捕获的 RpcCallContext
     * 绑定和恢复 ThreadLocal。即使 Deadline/Cancel 提前逻辑结束，
     * 钩子仍实际退出后才释放该 Session 的资源额度。
     */
    public final class Session {
        private final RpcInvocation invocation;
        private final RpcCallContext scope;
        private final ArrayDeque<Runnable> events = new ArrayDeque<>();
        private final Set<Integer> attemptsStarted = new LinkedHashSet<>();
        private final Set<Integer> attemptsFinished = new HashSet<>();
        private final AtomicBoolean terminal = new AtomicBoolean();
        private boolean draining;

        private Session(RpcInvocation invocation, RpcCallContext scope) {
            this.invocation = invocation;
            this.scope = scope;
        }

        /**
         * 正序运行 before，然后触发框架剩余步骤。before 失败时不运行远端发送或业务。
         *
         * @param next 原始 RPC 调度动作，不阻塞等待异步业务终态
         * @param reject 固定类型失败完成器，不得把 Hook 原始异常消息发给对端
         */
        public void before(Runnable next, Consumer<RuntimeException> reject) {
            Objects.requireNonNull(next, "next");
            Objects.requireNonNull(reject, "reject");
            enqueue(() -> {
                if (terminal.get()) {
                    // Provider 在取消时仍需进入其 onReady 清理分支并归还真实业务 Lease。
                    next.run();
                    return;
                }
                try {
                    for (RpcInterceptor interceptor : interceptors) {
                        interceptor.before(invocation);
                    }
                    // 已取消时调用方只执行幂等清理；不允许它重新进入业务。
                    next.run();
                } catch (Throwable failed) {
                    logFailure(failed);
                    reject.accept(new RpcUnavailableException("RPC interceptor before hook rejected call"));
                }
            });
        }

        /**
         * 在同一 Worker 上串行执行每次 Retry 的前置 Hook；本地重选不调用此方法。
         *
         * @param attempt 真实 Retry 序号，从 1 开始
         * @param next 继续 RPC 选点/发送的动作
         * @param reject Hook 拒绝时完成逻辑调用
         */
        public void beforeAttempt(int attempt, Runnable next, Consumer<RuntimeException> reject) {
            Objects.requireNonNull(next, "next");
            Objects.requireNonNull(reject, "reject");
            enqueue(() -> {
                if (terminal.get()) {
                    return;
                }
                attemptsStarted.add(attempt);
                try {
                    for (RpcInterceptor interceptor : interceptors) {
                        interceptor.beforeAttempt(invocation, attempt);
                    }
                    if (!terminal.get()) {
                        next.run();
                    }
                } catch (Throwable failed) {
                    logFailure(failed);
                    reject.accept(new RpcUnavailableException("RPC interceptor attempt hook rejected call"));
                }
            });
        }

        /**
         * 记录 Attempt 的已分类终态并逆序回调。重复观察被忽略。
         *
         * @param attempt Retry 序号
         * @param status 只读状态
         */
        public void afterAttempt(int attempt, RpcStatus status) {
            Objects.requireNonNull(status, "status");
            enqueue(() -> {
                if (attemptsStarted.contains(attempt) && attemptsFinished.add(attempt)) {
                    notifyAttempt(attempt, status);
                }
            });
        }

        /**
         * 只安排一次逆序逻辑终态；在全部已排队事件后执行并归还槽位。
         *
         * @param status 原始 RPC 终态
         * @param cancelled 是否是显式 Future 取消
         */
        public void finish(RpcStatus status, boolean cancelled) {
            Objects.requireNonNull(status, "status");
            if (!terminal.compareAndSet(false, true)) {
                return;
            }
            enqueue(() -> {
                try {
                    // 取消、限时或尚未发送导致的 Attempt 也必须得到一次实际终态。
                    for (int attempt : attemptsStarted) {
                        if (attemptsFinished.add(attempt)) {
                            notifyAttempt(attempt, status);
                        }
                    }
                    for (int index = interceptors.size() - 1; index >= 0; index--) {
                        RpcInterceptor interceptor = interceptors.get(index);
                        try {
                            if (cancelled) {
                                interceptor.onCancel(invocation);
                            } else if (status == RpcStatus.OK) {
                                interceptor.after(invocation);
                            } else {
                                interceptor.onFailure(invocation, status);
                            }
                        } catch (Throwable failure) {
                            logFailure(failure);
                        }
                    }
                } finally {
                    active.release();
                    if (activeCalls.decrementAndGet() == 0 && closing.get()) {
                        executor.shutdown();
                    }
                }
            });
        }

        private void notifyAttempt(int attempt, RpcStatus status) {
            for (int index = interceptors.size() - 1; index >= 0; index--) {
                try {
                    interceptors.get(index).afterAttempt(invocation, attempt, status);
                } catch (Throwable failure) {
                    logFailure(failure);
                }
            }
        }

        /** 每个 Session 始终只运行一个 drain 任务，避免同一会话并发重入。 */
        private void enqueue(Runnable task) {
            boolean shouldSchedule;
            synchronized (this) {
                events.addLast(task);
                shouldSchedule = !draining;
                if (shouldSchedule) {
                    draining = true;
                }
            }
            if (shouldSchedule) {
                try {
                    executor.execute(this::drain);
                } catch (RejectedExecutionException rejection) {
                    // 配额已包含唯一 drain 队列槽；只可能来自关闭竞争或异常拒绝。
                    // 由调用方通过入场校验阻止新会话；已入场 Session 不提前归还资源。
                    LOGGER.warn("RPC interceptor dispatcher rejected accepted session");
                    Thread.ofVirtual().name("otryx-interceptor-fallback").start(this::drain);
                }
            }
        }

        private void drain() {
            for (;;) {
                Runnable task;
                synchronized (this) {
                    task = events.pollFirst();
                    if (task == null) {
                        draining = false;
                        return;
                    }
                }
                try {
                    RpcCallContext.wrap(scope, task).run();
                } catch (Throwable failure) {
                    logFailure(failure);
                }
            }
        }
    }

    private static void logFailure(Throwable failure) {
        LOGGER.warn("RPC interceptor callback failed: errorType={}", failure.getClass().getName());
    }
}
