package com.peachsoft.otryx.observability;

import com.peachsoft.otryx.api.RpcEndpoint;
import com.peachsoft.otryx.api.RpcExecutionMode;
import com.peachsoft.otryx.api.RpcStatus;
import com.peachsoft.otryx.api.ServiceKey;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 为慢速第三方 Observer 提供有界、有损、非阻塞的显式异步出口。
 *
 * <p>常规事件通过固定队列递交到单个 daemon 工作线程；满队列丢弃并计数，
 * 不在 EventLoop 等待。三个 inflight Delta 独立聚合，队列溢出不会丢失净增减，
 * 但峰值可被合并，且关闭时第三方回调可能仍在运行。
 *
 * <p>此类不能用于审计、Exactly-once 业务消息或依赖调用线程 ThreadLocal 的回调。
 * 所有者负责调用 close()，并通过 droppedEvents()/failedCallbacks() 监控出口退化。
 *
 * @Author OpenAI
 * @Version 1.0.0-SNAPSHOT
 * @CreateTime 2026/10/14 11:00
 */
public final class BoundedAsyncRpcObserver implements RpcObserver, AutoCloseable {

    private static final long FLUSH_INTERVAL_MILLIS = 25L;

    private final RpcObserver delegate;
    private final boolean active;
    private final ArrayBlockingQueue<Runnable> queue;
    private final Thread worker;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicLong droppedEvents = new AtomicLong();
    private final AtomicLong failedCallbacks = new AtomicLong();
    private final AtomicInteger pendingClientInflight = new AtomicInteger();
    private final AtomicInteger pendingServerInflight = new AtomicInteger();
    private final AtomicLong pendingServerInflightBytes = new AtomicLong();

    /**
     * 创建由该实例持有的 daemon 工作线程。
     *
     * @param delegate 下游观察者，不应依赖调用方的 ThreadLocal
     * @param queueCapacity 普通事件的有界待处理数量，必须为正数
     */
    public BoundedAsyncRpcObserver(RpcObserver delegate, int queueCapacity) {
        if (queueCapacity <= 0) {
            throw new IllegalArgumentException("queueCapacity must be positive");
        }
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.active = delegate.enabled();
        this.queue = new ArrayBlockingQueue<>(queueCapacity);
        if (active) {
            worker = Thread.ofPlatform()
                    .daemon(true)
                    .name("otryx-observer-bounded")
                    .unstarted(this::dispatch);
            worker.start();
        } else {
            worker = null;
        }
    }

    @Override
    public boolean enabled() {
        return active && !closed.get();
    }

    /**
     * 返回因容量耗尽或关闭而丢弃的普通事件数。
     *
     * @return 累计丢弃总数
     */
    public long droppedEvents() {
        return droppedEvents.get();
    }

    /**
     * 返回下游观察者主动抛出的运行时异常次数。
     *
     * @return 累计失败次数
     */
    public long failedCallbacks() {
        return failedCallbacks.get();
    }

    /**
     * 返回当前积压的普通遥测事件数量。
     *
     * @return 有界队列深度
     */
    public int queuedEvents() {
        return queue.size();
    }

    private void submit(Runnable task) {
        if (closed.get() || !queue.offer(task)) {
            droppedEvents.incrementAndGet();
            return;
        }
        // close() may clear the queue between the first check and offer().
        // Remove an event published after close without counting it twice.
        if (closed.get() && queue.remove(task)) {
            droppedEvents.incrementAndGet();
        }
    }

    private void dispatch() {
        while (!closed.get()) {
            try {
                Runnable task = queue.poll(FLUSH_INTERVAL_MILLIS, TimeUnit.MILLISECONDS);
                flushInflightDeltas();
                if (task != null) {
                    safely(task);
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                closed.set(true);
                break;
            }
        }
        flushInflightDeltas();
    }

    private void flushInflightDeltas() {
        int client = pendingClientInflight.getAndSet(0);
        if (client != 0) {
            safely(() -> delegate.onClientInflightChanged(client));
        }
        int server = pendingServerInflight.getAndSet(0);
        if (server != 0) {
            safely(() -> delegate.onServerInflightChanged(server));
        }
        long bytes = pendingServerInflightBytes.getAndSet(0L);
        if (bytes != 0L) {
            safely(() -> delegate.onServerInflightBytesChanged(bytes));
        }
    }

    private void safely(Runnable callback) {
        try {
            callback.run();
        } catch (RuntimeException ignored) {
            failedCallbacks.incrementAndGet();
        }
    }

    @Override
    public void onConnectionEstablished(
            RpcConnectionRole role,
            RpcEndpoint endpoint,
            long durationNanos) {
        if (active) {
            submit(() -> delegate.onConnectionEstablished(
                    role,
                    endpoint,
                    durationNanos));
        }
    }

    @Override
    public void onConnectionReconnectScheduled(
            RpcEndpoint endpoint,
            int attempt,
            long delayMillis) {
        if (active) {
            submit(() -> delegate.onConnectionReconnectScheduled(
                    endpoint,
                    attempt,
                    delayMillis));
        }
    }

    @Override
    public void onConnectionHeartbeatTimeout(
            RpcConnectionRole role,
            RpcEndpoint endpoint,
            long idleNanos) {
        if (active) {
            submit(() -> delegate.onConnectionHeartbeatTimeout(
                    role,
                    endpoint,
                    idleNanos));
        }
    }

    @Override
    public void onConnectionClosed(
            RpcConnectionRole role,
            RpcEndpoint endpoint,
            RpcConnectionCloseReason reason,
            Throwable error) {
        if (active) {
            submit(() -> delegate.onConnectionClosed(
                    role,
                    endpoint,
                    reason,
                    error));
        }
    }

    @Override
    public void onRegistryOperationCompleted(
            String registryType,
            RpcRegistryOperation operation,
            long durationNanos,
            Throwable error) {
        if (active) {
            submit(() -> delegate.onRegistryOperationCompleted(
                    registryType,
                    operation,
                    durationNanos,
                    error));
        }
    }

    @Override
    public void onRegistryRecoveryCompleted(
            String registryType,
            RpcRegistryRecoveryAction action,
            long durationNanos,
            Throwable error) {
        if (active) {
            submit(() -> delegate.onRegistryRecoveryCompleted(
                    registryType,
                    action,
                    durationNanos,
                    error));
        }
    }

    @Override
    public void onTlsHandshakeCompleted(
            RpcConnectionRole role,
            RpcEndpoint endpoint,
            RpcSecurityMode mode,
            long durationNanos,
            Throwable error) {
        if (active) {
            submit(() -> delegate.onTlsHandshakeCompleted(
                    role,
                    endpoint,
                    mode,
                    durationNanos,
                    error));
        }
    }

    @Override
    public void onCertificateReloadCompleted(
            RpcSecurityMode mode,
            RpcCertificateReloadOutcome outcome,
            long durationNanos,
            Throwable error) {
        if (active) {
            submit(() -> delegate.onCertificateReloadCompleted(
                    mode,
                    outcome,
                    durationNanos,
                    error));
        }
    }

    @Override
    public void onCertificateExpiryWarning(
            RpcSecurityMode mode,
            long remainingMillis) {
        if (active) {
            submit(() -> delegate.onCertificateExpiryWarning(
                    mode,
                    remainingMillis));
        }
    }

    @Override
    public void onClientAttemptCompleted(
            ServiceKey serviceKey,
            int methodId,
            RpcEndpoint endpoint,
            int attempt,
            long durationNanos,
            RpcStatus status,
            Throwable error) {
        if (active) {
            submit(() -> delegate.onClientAttemptCompleted(
                    serviceKey,
                    methodId,
                    endpoint,
                    attempt,
                    durationNanos,
                    status,
                    error));
        }
    }

    @Override
    public void onClientInflightChanged(int delta) {
        if (enabled()) {
            pendingClientInflight.addAndGet(delta);
        }
    }

    @Override
    public void onServerInflightChanged(int delta) {
        if (enabled()) {
            pendingServerInflight.addAndGet(delta);
        }
    }

    @Override
    public void onServerInflightBytesChanged(long delta) {
        if (enabled()) {
            pendingServerInflightBytes.addAndGet(delta);
        }
    }

    @Override
    public void onClientCircuitStateChanged(
            ServiceKey serviceKey,
            int methodId,
            RpcCircuitState state) {
        if (active) {
            submit(() -> delegate.onClientCircuitStateChanged(
                    serviceKey,
                    methodId,
                    state));
        }
    }

    @Override
    public void onClientCallCompleted(
            ServiceKey serviceKey,
            int methodId,
            long durationNanos,
            RpcStatus status,
            Throwable error) {
        if (active) {
            submit(() -> delegate.onClientCallCompleted(
                    serviceKey,
                    methodId,
                    durationNanos,
                    status,
                    error));
        }
    }

    @Override
    public void onClientRetryScheduled(
            ServiceKey serviceKey,
            int methodId,
            int nextAttempt,
            long delayMillis,
            Throwable cause) {
        if (active) {
            submit(() -> delegate.onClientRetryScheduled(
                    serviceKey,
                    methodId,
                    nextAttempt,
                    delayMillis,
                    cause));
        }
    }

    @Override
    public void onClientRetryExhausted(
            ServiceKey serviceKey,
            int methodId,
            RpcRetryExhaustionReason reason,
            Throwable cause) {
        if (active) {
            submit(() -> delegate.onClientRetryExhausted(
                    serviceKey,
                    methodId,
                    reason,
                    cause));
        }
    }

    @Override
    public void onClientCircuitRejected(
            ServiceKey serviceKey,
            int methodId) {
        if (active) {
            submit(() -> delegate.onClientCircuitRejected(
                    serviceKey,
                    methodId));
        }
    }

    @Override
    public void onEndpointEjected(
            ServiceKey serviceKey,
            RpcEndpoint endpoint,
            long ejectionMillis) {
        if (active) {
            submit(() -> delegate.onEndpointEjected(
                    serviceKey,
                    endpoint,
                    ejectionMillis));
        }
    }

    @Override
    public void onServerAdmissionRejected(
            int serviceId,
            int methodId,
            String reason) {
        if (active) {
            submit(() -> delegate.onServerAdmissionRejected(
                    serviceId,
                    methodId,
                    reason));
        }
    }

    @Override
    public void onServerInvocationCompleted(
            int serviceId,
            int methodId,
            RpcExecutionMode executionMode,
            long durationNanos,
            RpcStatus status,
            Throwable error) {
        if (active) {
            submit(() -> delegate.onServerInvocationCompleted(
                    serviceId,
                    methodId,
                    executionMode,
                    durationNanos,
                    status,
                    error));
        }
    }

    /**
     * 关闭入口非阻塞，丢弃尚未处理的普通事件并中断 daemon 线程。
     *
     * <p>第三方 Observer 若忽略 interrupt，当前正在执行的回调未必已结束；
     * close 返回不能被解释为第三方执行工作已经终止。
     */
    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            droppedEvents.addAndGet(queue.size());
            queue.clear();
            if (worker != null) {
                worker.interrupt();
            }
        }
    }
}
