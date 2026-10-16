package com.peachsoft.otryx.registry.consul;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Consul HTTP 轮询、TTL 心跳、KV CAS 共用的有界异步控制面隔离器。
 *
 * <p>私有执行线程不得在 RPC EventLoop 中阻塞；调度线程仅派发请求，
 * 不进行 HTTP IO。每个周期运行的任务单飞，队列拒绝即 fail fast。
 *
 * @Author OpenAI
 * @Version 1.0.0-SNAPSHOT
 * @CreateTime 2026/10/16 14:12
 */
final class ConsulV2Executor implements AutoCloseable {

    private static final Logger LOGGER = LoggerFactory.getLogger(ConsulV2Executor.class);
    private static final int MIN_WORKERS = 2;
    private static final int MAX_WORKERS = 4;
    private static final int MAX_QUEUE = 256;
    private static final long IDLE_SECONDS = 60L;
    private static final int MAX_SCHEDULED = 256;

    private final ThreadPoolExecutor workers;
    private final ScheduledThreadPoolExecutor scheduler;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicInteger scheduled = new AtomicInteger();

    ConsulV2Executor() {
        AtomicInteger ids = new AtomicInteger();
        workers = new ThreadPoolExecutor(MIN_WORKERS, MAX_WORKERS, IDLE_SECONDS, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(MAX_QUEUE),
                task -> daemon(task, "otryx-consul-v2-control-" + ids.incrementAndGet()),
                new ThreadPoolExecutor.AbortPolicy());
        scheduler = new ScheduledThreadPoolExecutor(1,
                task -> daemon(task, "otryx-consul-v2-scheduler"));
        scheduler.setRemoveOnCancelPolicy(true);
        scheduler.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        scheduler.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);
    }

    /** 在有界工作池执行可阻塞的 Agent/KV/Session 操作，拒绝则异常完成。 */
    <T> CompletableFuture<T> submit(String operation, CheckedSupplier<T> work) {
        Objects.requireNonNull(work, "work");
        CompletableFuture<T> result = new CompletableFuture<>();
        if (closed.get()) {
            return CompletableFuture.failedFuture(new IllegalStateException("Consul V2 control is closed"));
        }
        try {
            workers.execute(() -> {
                if (result.isCancelled()) {
                    return;
                }
                try {
                    result.complete(work.get());
                } catch (Throwable failure) {
                    if (failure instanceof InterruptedException) {
                        Thread.currentThread().interrupt();
                    }
                    result.completeExceptionally(failure);
                }
            });
        } catch (RuntimeException rejected) {
            result.completeExceptionally(new IllegalStateException(
                    "Consul V2 control queue is full: operation=" + operation, rejected));
        }
        return result;
    }

    /** 周期任务仅派发 worker，重入周期将合并，避免网络故障时积压无界任务。 */
    ScheduledFuture<?> periodic(String operation, Duration interval, CheckedSupplier<Void> work) {
        Objects.requireNonNull(interval, "interval");
        if (interval.isNegative() || interval.isZero() || interval.toMillis() < 1) {
            throw new IllegalArgumentException("Consul schedule interval must be positive");
        }
        if (scheduled.incrementAndGet() > MAX_SCHEDULED) {
            scheduled.decrementAndGet();
            throw new IllegalStateException("Consul scheduled task capacity exceeded");
        }
        AtomicBoolean inFlight = new AtomicBoolean();
        try {
            return scheduler.scheduleWithFixedDelay(() -> {
                if (!inFlight.compareAndSet(false, true) || closed.get()) {
                    return;
                }
                submit(operation, work).whenComplete((ignored, error) -> {
                    inFlight.set(false);
                    if (error != null && !closed.get()) {
                        LOGGER.warn("Consul V2 scheduled task failed: operation={}, errorType={}",
                                operation, error.getClass().getSimpleName());
                    }
                });
            }, 0L, interval.toMillis(), TimeUnit.MILLISECONDS);
        } catch (RuntimeException failure) {
            scheduled.decrementAndGet();
            throw failure;
        }
    }

    /** 显式取消任务，并且回收调度容量，幂等调用由调用者保证。 */
    void cancel(ScheduledFuture<?> future) {
        if (future != null && future.cancel(false)) {
            scheduled.decrementAndGet();
        }
    }

    private static Thread daemon(Runnable work, String name) {
        Thread thread = new Thread(work, name);
        thread.setDaemon(true);
        return thread;
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        scheduler.shutdownNow();
        workers.shutdownNow();
    }

    @FunctionalInterface
    interface CheckedSupplier<T> {
        T get() throws Exception;
    }
}
