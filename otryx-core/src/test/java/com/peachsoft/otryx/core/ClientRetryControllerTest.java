package com.peachsoft.otryx.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.peachsoft.otryx.api.RpcOverloadedException;
import com.peachsoft.otryx.api.RpcUnavailableException;
import com.peachsoft.otryx.observability.RpcRetryExhaustionReason;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * 独立验证重试策略抽取后的幂等、额度、次数与预算顺序。
 *
 * @Author OpenAI
 * @Version 1.0.0-SNAPSHOT
 * @CreateTime 2026/10/14 17:05
 */
class ClientRetryControllerTest {
    private static final long FUTURE_BUDGET = Duration.ofMinutes(1).toNanos();

    @Test
    void nonIdempotentCallMustNotConsumeBudget() {
        RpcClientResilienceOptions options = options(3, 1);
        RetryBudget budget = new RetryBudget(options);
        var decision = new ClientRetryController(options, budget).decide(
                false, new RpcUnavailableException("unavailable"), 1, System.nanoTime() + FUTURE_BUDGET);
        assertFalse(decision.retry());
        assertNull(decision.exhaustionReason());
        assertEquals(1L, budget.availableRetries());
    }

    @Test
    void transientIdempotentFailureCanScheduleNextAttempt() {
        RpcClientResilienceOptions options = options(3, 1);
        var decision = new ClientRetryController(options, new RetryBudget(options)).decide(
                true, new RpcOverloadedException("overloaded"), 1, System.nanoTime() + FUTURE_BUDGET);
        assertTrue(decision.retry());
        assertEquals(0L, decision.delayMillis());
    }

    @Test
    void maximumAttemptsStopsBeforeCreditConsumption() {
        RpcClientResilienceOptions options = options(2, 1);
        RetryBudget budget = new RetryBudget(options);
        var decision = new ClientRetryController(options, budget).decide(
                true, new RpcUnavailableException("unavailable"), 2, System.nanoTime() + FUTURE_BUDGET);
        assertFalse(decision.retry());
        assertEquals(RpcRetryExhaustionReason.MAX_ATTEMPTS, decision.exhaustionReason());
        assertEquals(1L, budget.availableRetries());
    }

    @Test
    void noCreditsIsRecordedAsBudgetExhausted() {
        RpcClientResilienceOptions options = options(3, 0);
        var decision = new ClientRetryController(options, new RetryBudget(options)).decide(
                true, new RpcUnavailableException("unavailable"), 1, System.nanoTime() + FUTURE_BUDGET);
        assertEquals(RpcRetryExhaustionReason.BUDGET, decision.exhaustionReason());
    }

    @Test
    void expiredDeadlineMustPreserveCreditConsumptionOrder() {
        RpcClientResilienceOptions options = options(3, 1);
        RetryBudget budget = new RetryBudget(options);
        var decision = new ClientRetryController(options, budget).decide(
                true, new RpcUnavailableException("unavailable"), 1, System.nanoTime() - 1L);
        assertFalse(decision.retry());
        assertEquals(RpcRetryExhaustionReason.DEADLINE, decision.exhaustionReason());
        assertEquals(0L, budget.availableRetries());
    }

    @Test
    void businessFailureDoesNotBecomeRetryable() {
        assertFalse(ClientRetryController.isRetryable(new IllegalStateException("business")));
        assertTrue(ClientRetryController.isRetryable(new RpcUnavailableException("unavailable")));
    }

    private static RpcClientResilienceOptions options(int maxAttempts, int credit) {
        return new RpcClientResilienceOptions(
                maxAttempts, 0.0d, credit, credit,
                Duration.ZERO, Duration.ZERO, 3, Duration.ofSeconds(30),
                3, Duration.ofSeconds(30));
    }
}
