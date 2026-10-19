package com.peachsoft.otryx.quickstart.memory;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.peachsoft.otryx.api.RpcRemoteException;
import com.peachsoft.otryx.api.RpcStatus;
import com.peachsoft.otryx.api.RpcUnavailableException;
import com.peachsoft.otryx.core.RpcCallContext;
import com.peachsoft.otryx.core.RpcCallOptions;
import com.peachsoft.otryx.core.RpcCallScope;
import com.peachsoft.otryx.quickstart.api.GreetingReply;
import com.peachsoft.otryx.quickstart.api.GreetingService;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 验证 Memory 示例在应用目录异步收敛期间有界等待，且不掩盖真实 RPC 故障。
 *
 * @Author OpenAI
 * @Version 1.0.0-SNAPSHOT
 * @CreateTime 2026/10/19 01:59
 */
class MemoryGreetingRunnerAvailabilityTest {

    @Test
    void directUnavailableShouldRetryAndSucceed() {
        assertUnavailableThenSuccess(new RpcUnavailableException("Directory is not ready"));
    }

    @Test
    void wrappedUnavailableShouldRetryAndSucceed() {
        assertUnavailableThenSuccess(new CompletionException(
                new CompletionException(new RpcUnavailableException("Directory is empty"))));
    }

    @Test
    void providerStartingUnavailableShouldRetryAndSucceed() {
        assertUnavailableThenSuccess(new RpcRemoteException(
                RpcStatus.UNAVAILABLE, "RpcException", "Provider is starting"));
    }

    @Test
    void unrelatedFailuresMustNotRetry() {
        for (RuntimeException failure : List.of(
                new IllegalArgumentException("Invalid request"),
                new RpcRemoteException(RpcStatus.BUSINESS_ERROR, "BusinessException", "Business failure"),
                new RpcRemoteException(RpcStatus.INTERNAL_ERROR, "RpcException", "Internal failure"),
                new CompletionException(new IllegalStateException("Invalid protocol response")))) {
            AtomicInteger attempts = new AtomicInteger();
            MemoryGreetingRunner runner = runner(request -> {
                attempts.incrementAndGet();
                throw failure;
            });
            assertSame(failure, assertThrows(RuntimeException.class, () -> runner.run(null)));
            assertEquals(1, attempts.get());
        }
    }

    @Test
    void unexpectedResponseMustFailWithoutRetry() {
        AtomicInteger attempts = new AtomicInteger();
        MemoryGreetingRunner runner = runner(request -> {
            attempts.incrementAndGet();
            return new GreetingReply("Unexpected reply");
        });
        IllegalStateException failure = assertThrows(IllegalStateException.class, () -> runner.run(null));
        assertEquals("Unexpected Memory Quickstart RPC response", failure.getMessage());
        assertEquals(1, attempts.get());
    }

    @Test
    void absenceMustExpireWithinInheritedStartupBudget() {
        AtomicInteger attempts = new AtomicInteger();
        RpcUnavailableException unavailable = new RpcUnavailableException("No provider");
        MemoryGreetingRunner runner = runner(request -> {
            attempts.incrementAndGet();
            throw unavailable;
        });
        assertTimeoutPreemptively(Duration.ofSeconds(2), () -> {
            try (RpcCallScope ignored = RpcCallContext.open(
                    new RpcCallOptions(Duration.ofMillis(250), Map.of()))) {
                IllegalStateException failure = assertThrows(IllegalStateException.class, () -> runner.run(null));
                assertSame(unavailable, failure.getCause());
                assertTrue(failure.getMessage().contains("discovery budget expired"));
            }
        });
        assertTrue(attempts.get() > 0);
    }

    @Test
    void interruptionMustStopWaitingAndPreserveFlag() {
        AtomicInteger attempts = new AtomicInteger();
        MemoryGreetingRunner runner = runner(request -> {
            attempts.incrementAndGet();
            Thread.currentThread().interrupt();
            throw new RpcUnavailableException("Directory is not ready");
        });
        try {
            IllegalStateException failure = assertThrows(IllegalStateException.class, () -> runner.run(null));
            assertInstanceOf(InterruptedException.class, failure.getCause());
            assertTrue(Thread.currentThread().isInterrupted());
            assertEquals(1, attempts.get());
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void attemptsMustUseRemainingDeadlineAndRestoreParentContext() {
        AtomicInteger attempts = new AtomicInteger();
        AtomicLong firstBudget = new AtomicLong();
        AtomicLong lastBudget = new AtomicLong();
        try (RpcCallScope ignored = RpcCallContext.open(
                new RpcCallOptions(Duration.ofSeconds(2), Map.of()))) {
            RpcCallContext parent = RpcCallContext.current();
            MemoryGreetingRunner runner = runner(request -> {
                RpcCallContext call = RpcCallContext.current();
                assertTrue(call.deadlineNanos() - parent.deadlineNanos() <= 0L);
                assertTrue(call.remainingNanos() > 0L);
                if (attempts.incrementAndGet() == 1) {
                    firstBudget.set(call.remainingNanos());
                    throw new RpcUnavailableException("Initial empty directory");
                }
                lastBudget.set(call.remainingNanos());
                return new GreetingReply("Hello, Quickstart!");
            });
            assertDoesNotThrow(() -> runner.run(null));
            assertSame(parent, RpcCallContext.current());
        }
        assertEquals(2, attempts.get());
        assertTrue(lastBudget.get() < firstBudget.get());
    }

    @Test
    void expiredParentDeadlineMustPreventInvocation() {
        AtomicInteger attempts = new AtomicInteger();
        MemoryGreetingRunner runner = runner(request -> {
            attempts.incrementAndGet();
            return new GreetingReply("Hello, Quickstart!");
        });
        try (RpcCallScope ignored = RpcCallContext.attach(
                RpcCallContext.inbound(System.nanoTime() - 1L, Map.of()))) {
            assertThrows(IllegalStateException.class, () -> runner.run(null));
        }
        assertEquals(0, attempts.get());
    }

    private static void assertUnavailableThenSuccess(RuntimeException unavailable) {
        AtomicInteger attempts = new AtomicInteger();
        MemoryGreetingRunner runner = runner(request -> {
            if (attempts.incrementAndGet() == 1) {
                throw unavailable;
            }
            return new GreetingReply("Hello, Quickstart!");
        });
        assertDoesNotThrow(() -> runner.run(null));
        assertEquals(2, attempts.get());
    }

    private static MemoryGreetingRunner runner(GreetingService service) {
        MemoryGreetingRunner runner = new MemoryGreetingRunner();
        ReflectionTestUtils.setField(runner, "greetingService", service);
        return runner;
    }
}
