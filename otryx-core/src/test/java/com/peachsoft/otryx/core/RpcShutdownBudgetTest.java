package com.peachsoft.otryx.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * 验证 Provider/Consumer 的关闭预算只在入口初始化一次并逐阶段递减。
 *
 * @Author OpenAI
 * @Version 1.0.0-SNAPSHOT
 * @CreateTime 2026/10/14 17:05
 */
class RpcShutdownBudgetTest {

    @Test
    void remainingBudgetExpiresWithoutRenewing() throws InterruptedException {
        RpcShutdownBudget budget = RpcShutdownBudget.start(
                new RpcShutdownOptions(Duration.ofMillis(5)));
        long first = budget.remainingNanos();
        assertTrue(first >= 0L);
        Thread.sleep(30L);
        assertEquals(0L, budget.remainingNanos());
        assertTrue(budget.remainingNanos() <= first);
    }

    @Test
    void invalidBudgetMustFailEarly() {
        assertThrows(IllegalArgumentException.class,
                () -> new RpcShutdownOptions(Duration.ZERO));
        assertThrows(IllegalArgumentException.class,
                () -> new RpcShutdownOptions(Duration.ofDays(2)));
    }
}
