package com.peachsoft.otryx.registry.consul;

import com.peachsoft.otryx.registry.RegistryFailureSanitizer;
import java.time.Duration;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Consul 阻塞控制面的固定容量执行与订阅调度器。
 *
 * <p>RPC EventLoop 不承担 Consul 同步网络调用；控制队列满时立即拒绝。
 * 所有异步任务包括注销均以 Future 终态表示，不吞掉后端错误。
 *
 * @Author OpenAI
 * @Version 1.0.0-SNAPSHOT
 * @CreateTime 2026/10/16 15:00
 */
final class ConsulV2Executor implements AutoCloseable {

    private static final int CORE_THREADS = 2;
    private static final int MAX_THREADS = 4;
    private static final int MAX_QUEUE = 256;
    private static final int SCHEDULE_THREADS = 1;
    private static final int KEEPALIVE_SECONDS = 60;

    private final ThreadPoolExecutor workers;
    private final ScheduledThreadPoolExecutor scheduler;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Set<ControlFuture<?>> active = ConcurrentHashMap.newKeySet();

    ConsulV2Executor() {
        AtomicInteger counter = new AtomicInteger();
        ThreadFactory threadFactory = action -> daemon(action,
                "otryx-consul-v2-control-" + counter.incrementAndGet());
        workers = new ThreadPoolExecutor(
                CORE_THREADS, MAX_THREADS, KEEPALIVE_SECONDS, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(MAX_QUEUE), threadFactory, new ThreadPoolExecutor.AbortPolicy());
        scheduler = new ScheduledThreadPoolExecutor(SCHEDULE_THREADS,
                action -> daemon(action, "otryx-consul-v2-scheduler"));
        scheduler.setRemoveOnCancelPolicy(true);
        scheduler.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        scheduler.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);
    }

    <T> CompletableFuture<T> submit(String operation, Callable<T> action) {
        Objects.requireNonNull(action, "action");
        ControlFuture<T> future = new ControlFuture<>();
        if (closed.get()) {
            return CompletableFuture.failedFuture(new IllegalStateException("Consul control executor closed"));
        }
        active.add(future);
        future.whenComplete((value, error) -> active.remove(future));
        try {
            future.attach(workers.submit(() -> {
                if (future.isCancelled()) {
                    return;
                }
                try {
                    future.complete(action.call());
                } catch (Throwable failure) {
                    if (failure instanceof InterruptedException) {
                        Thread.currentThread().interrupt();
                    }
                    future.completeExceptionally(RegistryFailureSanitizer.sanitize(failure));
                }
            }));
        } catch (RejectedExecutionException rejected) {
            future.completeExceptionally(new IllegalStateException(
                    "Consul control-plane capacity exhausted: operation=" + operation));
        }
        return future;
    }

    ScheduledFuture<?> scheduleWithFixedDelay(
            Runnable action, Duration initial, Duration interval) {
        Objects.requireNonNull(action, "action");
        if (closed.get() || interval.isNegative() || interval.isZero() || initial.isNegative()) {
            throw new IllegalStateException("Consul control scheduler unavailable");
        }
        return scheduler.scheduleWithFixedDelay(
                action, initial.toMillis(), interval.toMillis(), TimeUnit.MILLISECONDS);
    }

    private static Thread daemon(Runnable runnable, String name) {
        Thread result = new Thread(runnable, name);
        result.setDaemon(true);
        return result;
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            active.forEach(future -> future.cancel(true));
            scheduler.shutdownNow();
            workers.shutdownNow();
        }
    }
    /** 取消 SPI Future 同时中断其实际有界工作任务，阻塞 HTTP 等待随后撤销网络 exchange。 */
    private final class ControlFuture<T> extends CompletableFuture<T> {
        private Future<?> task;

        synchronized void attach(Future<?> value) {
            task = value;
            if (isCancelled()) {
                value.cancel(true);
                if (value instanceof Runnable queued) {
                    workers.remove(queued);
                }
            }
        }

        @Override
        public synchronized boolean cancel(boolean mayInterruptIfRunning) {
            boolean won = super.cancel(mayInterruptIfRunning);
            if (won && task != null) {
                task.cancel(mayInterruptIfRunning);
                if (task instanceof Runnable queued) {
                    workers.remove(queued);
                }
            }
            return won;
        }
    }

}
