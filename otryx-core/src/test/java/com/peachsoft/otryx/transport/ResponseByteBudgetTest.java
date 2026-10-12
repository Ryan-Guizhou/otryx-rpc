package com.peachsoft.otryx.transport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * Provider 已编码响应独立字节预算的并发与资源所有权回归。
 *
 * @Author OpenAI
 * @Version 1.0.0-SNAPSHOT
 * @CreateTime 2026/10/12 18:25
 */
class ResponseByteBudgetTest {

    private static final long TEST_CAPACITY = 4096L;

    @Test
    void largeResponseMustBeRejectedWithoutLeaseLeak() {
        ResponseByteBudget budget = new ResponseByteBudget(TEST_CAPACITY);

        assertNull(budget.tryAcquire(TEST_CAPACITY + 1));
        assertEquals(0L, budget.reservedBytes());
        ResponseByteBudget.Lease admitted =
                budget.tryAcquire(TEST_CAPACITY);
        assertNotNull(admitted);
        assertEquals(TEST_CAPACITY, budget.reservedBytes());

        assertNull(budget.tryAcquire(1L));
        assertEquals(TEST_CAPACITY, budget.reservedBytes());
        admitted.close();

        assertEquals(0L, budget.reservedBytes());
        assertNotNull(budget.tryAcquire(1L));
    }

    @Test
    void closingLeaseMoreThanOnceMustNotReleaseAnotherReservation() {
        ResponseByteBudget budget = new ResponseByteBudget(TEST_CAPACITY);
        ResponseByteBudget.Lease first = budget.tryAcquire(1024L);
        ResponseByteBudget.Lease second = budget.tryAcquire(2048L);
        assertNotNull(first);
        assertNotNull(second);
        assertEquals(3072L, budget.reservedBytes());

        assertTrue(first.release());
        assertFalse(first.release());
        first.close();
        assertEquals(2048L, budget.reservedBytes());

        second.close();
        second.close();
        assertEquals(0L, budget.reservedBytes());
    }

    @Test
    void invalidReservationMustNotAlterUsedBytes() {
        assertThrows(IllegalArgumentException.class,
                () -> new ResponseByteBudget(0L));
        ResponseByteBudget budget = new ResponseByteBudget(TEST_CAPACITY);
        assertThrows(IllegalArgumentException.class,
                () -> budget.tryAcquire(0L));
        assertThrows(IllegalArgumentException.class,
                () -> budget.tryAcquire(-1L));
        assertNull(budget.tryAcquire(Long.MAX_VALUE));
        assertEquals(0L, budget.reservedBytes());
    }

    @Test
    void concurrentAcquisitionsMustRemainWithinExactByteBudget()
            throws Exception {
        ResponseByteBudget budget = new ResponseByteBudget(TEST_CAPACITY);
        int workers = 16;
        CountDownLatch ready = new CountDownLatch(workers);
        CountDownLatch begin = new CountDownLatch(1);
        CountDownLatch attempted = new CountDownLatch(workers);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch completed = new CountDownLatch(workers);
        AtomicInteger accepted = new AtomicInteger();
        List<Thread> threads = new ArrayList<>();
        try {
            for (int i = 0; i < workers; i++) {
                threads.add(Thread.ofVirtual().start(() -> {
                    try {
                        ready.countDown();
                        if (!begin.await(5, TimeUnit.SECONDS)) {
                            attempted.countDown();
                            return;
                        }
                        ResponseByteBudget.Lease lease =
                                budget.tryAcquire(TEST_CAPACITY / 2);
                        attempted.countDown();
                        if (lease != null) {
                            accepted.incrementAndGet();
                            release.await(5, TimeUnit.SECONDS);
                            lease.close();
                        }
                    } catch (InterruptedException error) {
                        Thread.currentThread().interrupt();
                    } finally {
                        completed.countDown();
                    }
                }));
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            begin.countDown();
            assertTrue(attempted.await(5, TimeUnit.SECONDS));
            assertEquals(2, accepted.get());
            assertEquals(TEST_CAPACITY, budget.reservedBytes());
        } finally {
            release.countDown();
            assertTrue(completed.await(5, TimeUnit.SECONDS));
            for (Thread thread : threads) {
                thread.join();
            }
        }
        assertEquals(0L, budget.reservedBytes());
    }
}
