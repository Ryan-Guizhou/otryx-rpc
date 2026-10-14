package com.peachsoft.otryx.observability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.peachsoft.otryx.api.ServiceKey;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * 验证阻塞下游、队列丢弃、inflight 聚合及关闭终态。
 *
 * @Author OpenAI
 * @Version 1.0.0-SNAPSHOT
 * @CreateTime 2026/10/14 11:00
 */
@Timeout(value = 8, unit = TimeUnit.SECONDS)
class BoundedAsyncRpcObserverTest {

    @Test
    void slowObserverMustNotBlockProducersAndQueueMustBeBounded() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger received = new AtomicInteger();
        RpcObserver slow = new RpcObserver() {
            @Override
            public void onClientCircuitRejected(ServiceKey key, int methodId) {
                entered.countDown();
                try {
                    release.await();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
                received.incrementAndGet();
            }
        };
        ServiceKey key = new ServiceKey("demo.Slow", "1.0.0", "default");
        try (BoundedAsyncRpcObserver observer = new BoundedAsyncRpcObserver(slow, 1)) {
            observer.onClientCircuitRejected(key, 1);
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            observer.onClientCircuitRejected(key, 2);
            observer.onClientCircuitRejected(key, 3);
            assertEquals(1L, observer.droppedEvents());
            assertEquals(1, observer.queuedEvents());
            release.countDown();
            await(() -> received.get() == 2);
        } finally {
            release.countDown();
        }
    }

    @Test
    void inflightDeltasMustRemainBalancedAcrossWorkerFlushes() throws Exception {
        AtomicInteger clients = new AtomicInteger();
        AtomicInteger servers = new AtomicInteger();
        RpcObserver counting = new RpcObserver() {
            @Override
            public void onClientInflightChanged(int delta) {
                clients.addAndGet(delta);
            }

            @Override
            public void onServerInflightChanged(int delta) {
                servers.addAndGet(delta);
            }
        };
        try (BoundedAsyncRpcObserver observer = new BoundedAsyncRpcObserver(counting, 1)) {
            observer.onClientInflightChanged(5);
            observer.onServerInflightChanged(3);
            await(() -> clients.get() == 5 && servers.get() == 3);
            observer.onClientInflightChanged(-5);
            observer.onServerInflightChanged(-3);
            await(() -> clients.get() == 0 && servers.get() == 0);
        }
    }

    @Test
    void delegateFailureMustNotEscapeAndCloseMustRejectMoreCallbacks() throws Exception {
        RpcObserver failing = new RpcObserver() {
            @Override
            public void onClientCircuitRejected(ServiceKey key, int methodId) {
                throw new IllegalStateException("untrusted observer output");
            }
        };
        ServiceKey key = new ServiceKey("demo.Failing", "1.0.0", "default");
        BoundedAsyncRpcObserver observer = new BoundedAsyncRpcObserver(failing, 2);
        try {
            observer.onClientCircuitRejected(key, 1);
            await(() -> observer.failedCallbacks() == 1L);
            observer.close();
            assertFalse(observer.enabled());
            observer.onClientCircuitRejected(key, 2);
            assertTrue(observer.droppedEvents() >= 1L);
            assertEquals(0, observer.queuedEvents());
        } finally {
            observer.close();
        }
    }

    private static void await(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.sleep(5L);
        }
        assertTrue(condition.getAsBoolean(), "Expected asynchronous telemetry progress");
    }
}
