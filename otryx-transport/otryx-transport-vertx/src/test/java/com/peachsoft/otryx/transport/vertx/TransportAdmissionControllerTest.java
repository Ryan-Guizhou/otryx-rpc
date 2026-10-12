package com.peachsoft.otryx.transport.vertx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.peachsoft.otryx.transport.RpcTransportAdmissionOptions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * 校验 EventLoop 跨线程准入 CAS 和 Lease 严格所有权。
 *
 * @Author OpenAI
 * @Version 1.0.0-SNAPSHOT
 * @CreateTime 2026/10/12 16:54
 */
class TransportAdmissionControllerTest {

    @Test
    void rejectedConnectionMustRecoverAfterRelease() {
        TransportAdmissionController controller =
                new TransportAdmissionController(admission(1, 1));
        TransportAdmissionController.Lease first =
                controller.tryAcquireConnection();
        assertNotNull(first);
        assertNull(controller.tryAcquireConnection());
        assertEquals(1, controller.activeConnections());

        first.close();
        first.close();
        assertEquals(0, controller.activeConnections());
        TransportAdmissionController.Lease recovered =
                controller.tryAcquireConnection();
        assertNotNull(recovered);
        recovered.close();
        assertEquals(0, controller.activeConnections());
    }

    @Test
    void handshakeLimitMustReleaseOnCloseOrSuccess() {
        TransportAdmissionController controller =
                new TransportAdmissionController(admission(3, 1));
        TransportAdmissionController.Lease first =
                controller.tryAcquireHandshake();
        assertNotNull(first);
        assertNull(controller.tryAcquireHandshake());
        first.close();
        first.close();
        assertEquals(0, controller.activeHandshakes());
        TransportAdmissionController.Lease next =
                controller.tryAcquireHandshake();
        assertNotNull(next);
        next.close();
        assertEquals(0, controller.activeHandshakes());
    }

    @Test
    void partialFrameBudgetMustRejectOverflowAndRecover() {
        TransportAdmissionController controller =
                new TransportAdmissionController(
                        new RpcTransportAdmissionOptions(
                                2, 1, 32L, Duration.ofSeconds(1)));
        assertTrue(controller.tryReservePartialBytes(20));
        assertEquals(20L, controller.bufferedPartialBytes());
        assertEquals(false, controller.tryReservePartialBytes(13));
        assertTrue(controller.tryReservePartialBytes(12));
        assertEquals(32L, controller.bufferedPartialBytes());

        controller.releasePartialBytes(12);
        controller.releasePartialBytes(20);
        assertEquals(0L, controller.bufferedPartialBytes());
        assertTrue(controller.tryReservePartialBytes(32));
        controller.releasePartialBytes(32);
        assertEquals(0L, controller.bufferedPartialBytes());
    }

    @Test
    void concurrentAcquisitionsMustNotExceedConfiguredCapacity()
            throws Exception {
        TransportAdmissionController controller =
                new TransportAdmissionController(admission(2, 1));
        int workers = 16;
        CountDownLatch ready = new CountDownLatch(workers);
        CountDownLatch go = new CountDownLatch(1);
        CountDownLatch attempted = new CountDownLatch(workers);
        CountDownLatch done = new CountDownLatch(workers);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger accepted = new AtomicInteger();
        List<Thread> threads = new ArrayList<>();
        try {
            for (int i = 0; i < workers; i++) {
                threads.add(Thread.ofVirtual().start(() -> {
                    try {
                        ready.countDown();
                        if (!go.await(2, TimeUnit.SECONDS)) {
                            return;
                        }
                        TransportAdmissionController.Lease lease =
                                controller.tryAcquireConnection();
                        attempted.countDown();
                        if (lease != null) {
                            accepted.incrementAndGet();
                            release.await(5, TimeUnit.SECONDS);
                            lease.close();
                        }
                    } catch (InterruptedException error) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                }));
            }
            assertTrue(ready.await(2, TimeUnit.SECONDS));
            go.countDown();
            // 所有竞争者完成一次获取尝试之前不得释放最初的租约。
            assertTrue(attempted.await(2, TimeUnit.SECONDS));
            assertTrue(controller.activeConnections() <= 2);
            assertTrue(accepted.get() <= 2);
        } finally {
            release.countDown();
            assertTrue(done.await(3, TimeUnit.SECONDS));
            for (Thread t : threads) {
                t.join();
            }
        }
        assertTrue(accepted.get() <= 2);
        assertEquals(0, controller.activeConnections());
    }

    private static RpcTransportAdmissionOptions admission(
            int connections,
            int handshakes) {
        return new RpcTransportAdmissionOptions(
                connections,
                handshakes,
                65536L,
                Duration.ofSeconds(2));
    }
}
