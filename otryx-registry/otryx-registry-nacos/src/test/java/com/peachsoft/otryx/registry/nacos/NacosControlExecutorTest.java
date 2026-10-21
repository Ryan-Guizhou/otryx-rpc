package com.peachsoft.otryx.registry.nacos;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.peachsoft.otryx.api.ServiceKey;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * Nacos 控制面执行器背压测试。
 *
 * @Author Ryan
 * @Version 1.0.0
 * @CreateTime 2026/9/28 11:54
 */
class NacosControlExecutorTest {

    @Test
    void queueSaturationShouldFailFast() {
        NacosControlExecutor executor =
                new NacosControlExecutor();
        ServiceKey key = new ServiceKey(
                "demo.QueueSaturation",
                "1.0.0",
                "default");
        CountDownLatch release = new CountDownLatch(1);
        List<CompletableFuture<Void>> accepted =
                new ArrayList<>();

        try {
            for (int index = 0; index < 260; index++) {
                accepted.add(executor.submit(
                        "blocking-test",
                        key,
                        () -> release.await()));
            }

            CompletableFuture<Void> rejected =
                    executor.submit(
                            "rejected-test",
                            key,
                            () -> release.await());

            assertThrows(
                    CompletionException.class,
                    rejected::join);
        } finally {
            release.countDown();
            executor.close();
        }
    }

    @Test
    void checkedControlFailureMustCompleteFutureAndPreserveWorker()
            throws Exception {
        NacosControlExecutor executor =
                new NacosControlExecutor();

        try {
            CompletableFuture<String> failed =
                    executor.submit(
                            "error-test",
                            "test",
                            () -> {
                                AssertionError error = new AssertionError(
                                        "Unrecoverable callback failure");
                                error.initCause(new IllegalStateException("private upstream detail"));
                                error.addSuppressed(new IllegalStateException("private suppressed detail"));
                                throw error;
                            });
            ExecutionException failure =
                    assertThrows(
                            ExecutionException.class,
                            () -> failed.get(2, TimeUnit.SECONDS));
            IllegalStateException sanitized = assertInstanceOf(
                    IllegalStateException.class,
                    failure.getCause());
            assertEquals("Nacos control-plane operation failed", sanitized.getMessage());
            assertNull(sanitized.getCause());
            assertEquals(0, sanitized.getSuppressed().length);

            assertEquals(
                    "recovered",
                    executor.submit(
                                    "after-error",
                                    "test",
                                    () -> "recovered")
                            .get(2, TimeUnit.SECONDS));
        } finally {
            executor.close();
        }
    }

    @Test
    void periodicControlFailureMustAllowNextReconcileAttempt()
            throws Exception {
        NacosControlExecutor executor =
                new NacosControlExecutor();
        AtomicInteger attempts = new AtomicInteger();
        CountDownLatch recovered = new CountDownLatch(1);

        try {
            ScheduledFuture<?> schedule =
                    executor.scheduleWithFixedDelay(
                            "reconcile-test",
                            "test",
                            Duration.ZERO,
                            Duration.ofMillis(20),
                            () -> {
                                if (attempts.incrementAndGet() == 1) {
                                    throw new IllegalStateException(
                                            "Temporary registry error");
                                }
                                recovered.countDown();
                            });

            assertTrue(recovered.await(2, TimeUnit.SECONDS));
            assertTrue(attempts.get() >= 2);
            schedule.cancel(true);
        } finally {
            executor.close();
        }
    }

    @Test
    void scheduledTaskShouldUseControlPlaneExecutor()
            throws Exception {
        NacosControlExecutor executor =
                new NacosControlExecutor();
        CountDownLatch invoked = new CountDownLatch(1);

        try {
            ScheduledFuture<?> task =
                    executor.scheduleWithFixedDelay(
                            "scheduled-test",
                            "test",
                            Duration.ZERO,
                            Duration.ofMillis(20),
                            invoked::countDown);

            assertTrue(
                    invoked.await(
                            2,
                            TimeUnit.SECONDS));
            task.cancel(false);
        } finally {
            executor.close();
        }
    }
}
