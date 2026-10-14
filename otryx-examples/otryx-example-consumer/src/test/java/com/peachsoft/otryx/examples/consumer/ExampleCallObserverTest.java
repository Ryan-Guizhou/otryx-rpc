package com.peachsoft.otryx.examples.consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.peachsoft.otryx.api.RpcStatus;
import com.peachsoft.otryx.api.ServiceKey;
import org.junit.jupiter.api.Test;

/**
 * 自定义 Observer 示例必须按逻辑请求而非 Attempt 统计结果。
 *
 * @Author OpenAI
 * @Version 1.0.0-SNAPSHOT
 * @CreateTime 2026/10/14 16:00
 */
class ExampleCallObserverTest {

    @Test
    void shouldCountSuccessfulAndFailedLogicalCallsWithoutExportingData() {
        ExampleCallObserver observer = new ExampleCallObserver();
        ServiceKey key = new ServiceKey("demo.Service", "1.0.0", "default");
        observer.onClientCallCompleted(key, 1, 20L, RpcStatus.OK, null);
        observer.onClientCallCompleted(key, 1, 21L, RpcStatus.UNAVAILABLE,
                new IllegalArgumentException("DO_NOT_EXPORT_SECRET"));
        assertEquals(1L, observer.successfulCalls());
        assertEquals(1L, observer.failedCalls());
    }
}
