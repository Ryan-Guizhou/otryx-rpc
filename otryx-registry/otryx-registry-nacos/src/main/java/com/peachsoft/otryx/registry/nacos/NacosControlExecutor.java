package com.peachsoft.otryx.registry.nacos;

import com.peachsoft.otryx.api.ServiceKey;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Nacos 阻塞控制面调用的有界线程隔离器。
 *
 * @Author Ryan
 * @Version 1.0.0
 * @CreateTime 2026/9/28 11:35
 */
final class NacosControlExecutor implements AutoCloseable {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(NacosControlExecutor.class);

    private final ThreadPoolExecutor executor;
    private final ScheduledThreadPoolExecutor scheduler;

    NacosControlExecutor() {
        AtomicInteger sequence = new AtomicInteger();
        ThreadFactory factory = task -> daemonThread(
                task,
                "otryx-nacos-control-"
                        + sequence.incrementAndGet());
        executor = new ThreadPoolExecutor(
                2,
                4,
                60L,
                TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(256),
                factory,
                new ThreadPoolExecutor.AbortPolicy());

        AtomicInteger schedulerSequence =
                new AtomicInteger();
        scheduler = new ScheduledThreadPoolExecutor(
                1,
                task -> daemonThread(
                        task,
                        "otryx-nacos-scheduler-"
                                + schedulerSequence
                                        .incrementAndGet()));
        scheduler.setRemoveOnCancelPolicy(true);
        scheduler.setExecuteExistingDelayedTasksAfterShutdownPolicy(
                false);
        scheduler.setContinueExistingPeriodicTasksAfterShutdownPolicy(
                false);
    }

    <T> CompletableFuture<T> submit(
            String operation,
            ServiceKey serviceKey,
            CheckedSupplier<T> action) {
        return submit(
                operation,
                serviceKey.canonicalName(),
                action);
    }

    CompletableFuture<Void> submit(
            String operation,
            ServiceKey serviceKey,
            CheckedRunnable action) {
        return submit(
                operation,
                serviceKey,
                () -> {
                    action.run();
                    return null;
                });
    }

    <T> CompletableFuture<T> submit(
            String operation,
            String subject,
            CheckedSupplier<T> action) {
        CompletableFuture<T> result =
                new CompletableFuture<>();
        try {
            executor.execute(() -> {
                try {
                    result.complete(action.get());
                } catch (Throwable error) {
                    // Always settle the submitted Future, including Error
                    // from user-provided control callbacks.
                    if (error instanceof InterruptedException) {
                        Thread.currentThread().interrupt();
                    }
                    result.completeExceptionally(NacosFailures.normalize(error));
                }
            });
        } catch (RuntimeException error) {
            result.completeExceptionally(
                    new IllegalStateException(
                            "Nacos control executor queue is full: operation="
                                    + operation
                                    + ", subject="
                                    + subject,
                            null));
        }
        return result;
    }

    void execute(
            String operation,
            ServiceKey serviceKey,
            Runnable action) {
        submit(
                operation,
                serviceKey,
                () -> {
                    action.run();
                    return null;
                });
    }

    ScheduledFuture<?> scheduleWithFixedDelay(
            String operation,
            String subject,
            Duration initialDelay,
            Duration delay,
            Runnable action) {
        Objects.requireNonNull(
                initialDelay,
                "initialDelay");
        Objects.requireNonNull(delay, "delay");
        Objects.requireNonNull(action, "action");
        if (initialDelay.isNegative()
                || delay.isZero()
                || delay.isNegative()) {
            throw new IllegalArgumentException(
                    "Nacos control schedule requires "
                            + "non-negative initialDelay "
                            + "and positive delay");
        }
        AtomicBoolean consecutiveFailure = new AtomicBoolean();
        return scheduler.scheduleWithFixedDelay(
                () -> {
                    CompletableFuture<Void> pending =
                            submit(
                                    operation,
                                    subject,
                                    () -> {
                                        action.run();
                                        return null;
                                    });
                    try {
                        // get() can be interrupted during scheduler shutdown;
                        // join() would conceal that signal.
                        pending.get();
                        if (consecutiveFailure.getAndSet(false)) {
                            LOGGER.info(
                                    "Nacos scheduled control operation recovered. operation={}, subject={}",
                                    operation,
                                    subject);
                        }
                    } catch (InterruptedException interrupted) {
                        pending.cancel(true);
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(
                                "Nacos scheduled control operation interrupted",
                                interrupted);
                    } catch (ExecutionException failed) {
                        // A failed iteration must not disable all subsequent
                        // fixed-delay reconciliation attempts.
                        Throwable cause = failed.getCause();
                        String errorType = cause == null
                                ? failed.getClass().getName()
                                : cause.getClass().getName();
                        if (consecutiveFailure.compareAndSet(false, true)) {
                            LOGGER.warn(
                                    "Nacos scheduled control operation failed; retrying. "
                                            + "operation={}, subject={}, errorType={}",
                                    operation,
                                    subject,
                                    errorType);
                        } else {
                            LOGGER.debug(
                                    "Nacos scheduled control operation still failing. "
                                            + "operation={}, subject={}, errorType={}",
                                    operation,
                                    subject,
                                    errorType);
                        }
                    } catch (CancellationException cancelled) {
                        if (!scheduler.isShutdown()) {
                            LOGGER.debug(
                                    "Nacos scheduled control operation cancelled. "
                                            + "operation={}, subject={}, errorType={}",
                                    operation,
                                    subject,
                                    cancelled.getClass().getName());
                        }
                    }
                },
                initialDelay.toMillis(),
                delay.toMillis(),
                TimeUnit.MILLISECONDS);
    }

    private static Thread daemonThread(
            Runnable task,
            String name) {
        Thread thread = new Thread(task, name);
        thread.setDaemon(true);
        return thread;
    }

    @Override
    public void close() {
        scheduler.shutdownNow();
        executor.shutdownNow();
    }

    @FunctionalInterface
    interface CheckedSupplier<T> {
        T get() throws Exception;
    }

    @FunctionalInterface
    interface CheckedRunnable {
        void run() throws Exception;
    }
}
