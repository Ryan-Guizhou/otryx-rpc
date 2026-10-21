package com.peachsoft.otryx.observability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.peachsoft.otryx.api.RpcEndpoint;
import com.peachsoft.otryx.api.RpcStatus;
import com.peachsoft.otryx.api.ServiceKey;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/**
 * 验证 RPC Observer 的事件分发、隔离与无操作实现。
 *
 * @Author Ryan
 * @Version 1.0.0
 * @CreateTime 2026/9/28 10:13
 */
class RpcObserverTest {

    @Test
    void directoryFailureCallbacksMustBeIsolated() {
        AtomicInteger called = new AtomicInteger();
        RpcObserver failing = new RpcObserver() {
            @Override
            public void onClientDirectoryFailure(RpcDirectoryFailureReason reason) {
                throw new IllegalStateException("exporter unavailable");
            }
        };
        RpcObserver counting = new RpcObserver() {
            @Override
            public void onClientDirectoryFailure(RpcDirectoryFailureReason reason) {
                called.incrementAndGet();
            }
        };
        RpcObserver.composite(List.of(failing, counting))
                .onClientDirectoryFailure(RpcDirectoryFailureReason.SECURITY_REJECTED);
        assertEquals(1, called.get());
        assertFalse(RpcObserver.noop().enabled());
    }

    @Test
    void noopShouldStayDisabled() {
        assertFalse(RpcObserver.noop().enabled());
    }

    @Test
    void compositeShouldFanOutAndIsolateFailures() {
        AtomicInteger calls = new AtomicInteger();
        RpcObserver failing = new RpcObserver() {
            @Override
            public void onClientRetryScheduled(
                    ServiceKey serviceKey,
                    int methodId,
                    int nextAttempt,
                    long delayMillis,
                    Throwable cause) {
                throw new IllegalStateException("observer failure");
            }
        };
        RpcObserver counting = new RpcObserver() {
            @Override
            public void onClientRetryScheduled(
                    ServiceKey serviceKey,
                    int methodId,
                    int nextAttempt,
                    long delayMillis,
                    Throwable cause) {
                calls.incrementAndGet();
            }
        };

        RpcObserver composite =
                RpcObserver.composite(List.of(failing, counting));
        assertTrue(composite.enabled());

        composite.onClientRetryScheduled(
                new ServiceKey("demo.Service", "1.0.0", "default"),
                1,
                2,
                10L,
                new RuntimeException("retry"));

        assertEquals(1, calls.get());
    }

    @Test
    void compositeShouldForwardConnectionEventsAndIsolateFailures() {
        AtomicInteger established = new AtomicInteger();
        AtomicInteger closed = new AtomicInteger();
        RpcEndpoint endpoint =
                new RpcEndpoint("127.0.0.1", 19090);

        RpcObserver failing = new RpcObserver() {
            @Override
            public void onConnectionEstablished(
                    RpcConnectionRole role,
                    RpcEndpoint remote,
                    long durationNanos) {
                throw new IllegalStateException("observer failure");
            }
        };
        RpcObserver counting = new RpcObserver() {
            @Override
            public void onConnectionEstablished(
                    RpcConnectionRole role,
                    RpcEndpoint remote,
                    long durationNanos) {
                established.incrementAndGet();
            }

            @Override
            public void onConnectionClosed(
                    RpcConnectionRole role,
                    RpcEndpoint remote,
                    RpcConnectionCloseReason reason,
                    Throwable error) {
                closed.incrementAndGet();
            }
        };

        RpcObserver composite =
                RpcObserver.composite(List.of(failing, counting));

        composite.onConnectionEstablished(
                RpcConnectionRole.CLIENT,
                endpoint,
                100L);
        composite.onConnectionClosed(
                RpcConnectionRole.CLIENT,
                endpoint,
                RpcConnectionCloseReason.LOCAL_CLOSE,
                null);

        assertEquals(1, established.get());
        assertEquals(1, closed.get());
    }

    @Test
    void compositeMustForwardProviderInflightBytesAndIsolateFailures() {
        AtomicLong inFlightBytes = new AtomicLong();
        RpcObserver failing = new RpcObserver() {
            @Override
            public void onServerInflightBytesChanged(long delta) {
                throw new IllegalStateException("monitoring unavailable");
            }
        };
        RpcObserver counting = new RpcObserver() {
            @Override
            public void onServerInflightBytesChanged(long delta) {
                inFlightBytes.addAndGet(delta);
            }
        };
        RpcObserver observers = RpcObserver.composite(
                List.of(failing, counting));
        observers.onServerInflightBytesChanged(128L);
        assertEquals(128L, inFlightBytes.get());
        observers.onServerInflightBytesChanged(-128L);
        assertEquals(0L, inFlightBytes.get());
    }

    @Test
    void compositeShouldIgnoreDisabledObservers() {
        RpcObserver composite = RpcObserver.composite(
                List.of(RpcObserver.noop()));

        assertFalse(composite.enabled());
        composite.onClientAttemptCompleted(
                new ServiceKey("demo.Service", "1.0.0", "default"),
                1,
                null,
                1,
                1L,
                RpcStatus.OK,
                null);
    }
}
