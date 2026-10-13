package com.peachsoft.otryx.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * RPC Scope 的父预算约束、单次覆盖、显式跨线程与恢复语义回归。
 *
 * @Author OpenAI
 * @Version 1.0.0-SNAPSHOT
 * @CreateTime 2026/10/13 16:10
 */
class RpcCallContextTest {

    @Test
    void scopeMustRestorePreviousContext() {
        assertNull(RpcCallContext.current());
        try (RpcCallScope outer = RpcCallContext.open(
                new RpcCallOptions(Duration.ofSeconds(1), Map.of("tag", "outer")))) {
            RpcCallContext previous = RpcCallContext.current();
            try (RpcCallScope inner = RpcCallContext.open(
                    new RpcCallOptions(Duration.ofSeconds(3), Map.of("tag", "inner")))) {
                assertEquals("inner", RpcCallContext.current().metadata().get("tag"));
                assertTrue(RpcCallContext.current().remainingNanos()
                        <= previous.remainingNanos() + TimeUnit.MILLISECONDS.toNanos(5L));
            }
            assertEquals(previous, RpcCallContext.current());
            outer.close();
            assertNull(RpcCallContext.current());
        }
        assertNull(RpcCallContext.current());
    }

    @Test
    void downstreamBudgetMustNotExceedRemainingParentBudget() throws Exception {
        try (RpcCallScope ignored = RpcCallContext.open(
                new RpcCallOptions(Duration.ofMillis(180)))) {
            Thread.sleep(35L);
            long before = RpcCallContext.current().remainingNanos();
            try (RpcCallScope nested = RpcCallContext.open(
                    new RpcCallOptions(Duration.ofSeconds(2)))) {
                assertTrue(RpcCallContext.current().remainingNanos()
                        <= before + TimeUnit.MILLISECONDS.toNanos(5L));
            }
        }
    }

    @Test
    void asyncContinuationMustPropagateOnlyExplicitContext() throws Exception {
        try (RpcCallScope ignored = RpcCallContext.open(
                new RpcCallOptions(Duration.ofSeconds(2), Map.of("x-trace", "demo")))) {
            CompletableFuture<String> plain = CompletableFuture.supplyAsync(() ->
                    RpcCallContext.current() == null ? "none" : "leaked");
            CompletableFuture<String> captured = CompletableFuture.supplyAsync(
                    RpcCallContext.wrap(
                            (java.util.function.Supplier<String>) () ->
                                    RpcCallContext.current().metadata().get("x-trace")));
            assertEquals("none", plain.get(2, TimeUnit.SECONDS));
            assertEquals("demo", captured.get(2, TimeUnit.SECONDS));
            assertEquals("demo", RpcCallContext.current().metadata().get("x-trace"));
        }
        assertNull(RpcCallContext.current());
    }

    @Test
    void reservedDeadlineMetadataMustBeRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new RpcCallOptions(null, Map.of("timeoutBudgetMillis", "9999")));
        assertThrows(IllegalArgumentException.class,
                () -> new RpcCallOptions(null, Map.of("deadlineEpochMillis", "9999")));
        assertThrows(IllegalArgumentException.class,
                () -> new RpcCallOptions(Duration.ZERO));
    }

    @Test
    void incomingRequestMustDiscardFrameworkBudgetFields() {
        RpcCallContext context = RpcCallContext.inbound(
                System.nanoTime(), TimeUnit.MILLISECONDS.toNanos(50L),
                Map.of("timeoutBudgetMillis", "50", "x-request", "demo"));
        assertEquals(Map.of("x-request", "demo"), context.metadata());
        assertTrue(context.remainingNanos() <= TimeUnit.MILLISECONDS.toNanos(50L));
    }
}
