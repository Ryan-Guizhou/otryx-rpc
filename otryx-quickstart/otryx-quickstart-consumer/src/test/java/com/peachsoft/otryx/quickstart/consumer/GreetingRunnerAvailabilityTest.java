package com.peachsoft.otryx.quickstart.consumer;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.peachsoft.otryx.api.RpcRemoteException;
import com.peachsoft.otryx.api.RpcStatus;
import com.peachsoft.otryx.api.RpcUnavailableException;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.Test;

/**
 * 示例 Consumer 仅重试明确暂时不可用状态的规则测试。
 *
 * @since 1.0.1
 *
 * @Author Ryan
 * @Version 1.0.0
 * @CreateTime 2026/10/8 17:07
 */
class GreetingRunnerAvailabilityTest {

    @Test
    void repeatingConsumerMustWaitBeyondInitialDiscoveryDeadline() {
        assertTrue(GreetingRunner.shouldContinueDiscovery(true, 200L, 100L));
        assertTrue(GreetingRunner.shouldContinueDiscovery(false, 50L, 100L));
        assertFalse(GreetingRunner.shouldContinueDiscovery(false, 100L, 100L));
    }

    @Test
    void shouldRetryTransportUnavailable() {
        assertTrue(GreetingRunner.isTransientUnavailable(
                new RpcUnavailableException("Endpoint is unavailable")));
    }

    @Test
    void shouldRetryProviderStartingUnavailable() {
        assertTrue(GreetingRunner.isTransientUnavailable(
                new RpcRemoteException(
                        RpcStatus.UNAVAILABLE,
                        "RpcException",
                        "Server is not ready")));
    }

    @Test
    void shouldRetryNestedCompletionUnavailable() {
        assertTrue(GreetingRunner.isTransientUnavailable(
                new CompletionException(
                        new RpcRemoteException(
                                RpcStatus.UNAVAILABLE,
                                "RpcException",
                                "Server is not ready"))));
    }

    @Test
    void mustNotRetryBusinessErrorOrInternalError() {
        assertFalse(GreetingRunner.isTransientUnavailable(
                new RpcRemoteException(
                        RpcStatus.BUSINESS_ERROR,
                        "BusinessException",
                        "Business error")));
        assertFalse(GreetingRunner.isTransientUnavailable(
                new RpcRemoteException(
                        RpcStatus.INTERNAL_ERROR,
                        "RpcException",
                        "Internal error")));
    }

    @Test
    void mustNotRetryUnknownExceptions() {
        assertFalse(GreetingRunner.isTransientUnavailable(
                new IllegalArgumentException("Invalid request")));
    }
}
