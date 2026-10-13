package com.peachsoft.otryx.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * 验证逻辑调用作用域、截止时间继承和可信 Metadata 校验。
 *
 * @Author OpenAI
 * @Version 1.0.0-SNAPSHOT
 * @CreateTime 2026/10/13 17:03
 */
class RpcCallContextTest {

    @Test
    void scopeMustRestorePreviousContext() {
        assertNull(RpcCallContext.current());
        try (RpcCallScope outer = RpcCallContext.open(
                new RpcCallOptions(Duration.ofSeconds(2), Map.of("tenant", "a")))) {
            RpcCallContext previous = RpcCallContext.current();
            try (RpcCallScope inner = RpcCallContext.open(
                    new RpcCallOptions(Duration.ofSeconds(1), Map.of("call", "b")))) {
                assertEquals(Map.of("tenant", "a", "call", "b"),
                        RpcCallContext.current().metadata());
                assertTrue(RpcCallContext.current().remainingNanos()
                        <= previous.remainingNanos());
            }
            assertEquals(previous, RpcCallContext.current());
        }
        assertNull(RpcCallContext.current());
    }

    @Test
    void downstreamBudgetMustNotExceedRemainingParentBudget() {
        try (RpcCallScope outer = RpcCallContext.open(
                new RpcCallOptions(Duration.ofMillis(75), Map.of()))) {
            long parentDeadline = RpcCallContext.current().deadlineNanos();
            try (RpcCallScope inner = RpcCallContext.open(
                    new RpcCallOptions(Duration.ofSeconds(2), Map.of()))) {
                assertEquals(parentDeadline, RpcCallContext.current().deadlineNanos());
            }
        }
    }

    @Test
    void asyncContinuationMustPropagateOnlyExplicitContext() throws Exception {
        Runnable captured;
        try (RpcCallScope ignored = RpcCallContext.open(
                new RpcCallOptions(Duration.ofSeconds(2), Map.of("tenant", "a")))) {
            captured = RpcCallContext.wrap(() -> assertEquals(
                    "a", RpcCallContext.current().metadata().get("tenant")));
        }
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread thread = Thread.ofPlatform().start(() -> {
            try {
                assertNull(RpcCallContext.current());
                captured.run();
                assertNull(RpcCallContext.current());
            } catch (Throwable error) {
                failure.set(error);
            }
        });
        thread.join();
        assertNull(failure.get());
    }

    @Test
    void reservedIdentityAndOversizedMetadataMustBeRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new RpcCallOptions(null, Map.of("rpc-peer-id", "fake")));
        assertThrows(IllegalArgumentException.class,
                () -> new RpcCallOptions(null, Map.of("timeoutBudgetMillis", "9999")));
        assertThrows(IllegalArgumentException.class,
                () -> new RpcCallOptions(null, Map.of("payload", "x".repeat(8192))));
        assertFalse(new RpcCallOptions(null, Map.of()).metadata().containsKey("tenant"));
    }
}
