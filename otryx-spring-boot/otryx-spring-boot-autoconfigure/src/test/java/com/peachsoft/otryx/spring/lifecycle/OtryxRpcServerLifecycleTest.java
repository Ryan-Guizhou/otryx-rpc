package com.peachsoft.otryx.spring.lifecycle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.peachsoft.otryx.core.OtryxRpcServer;
import com.peachsoft.otryx.core.RpcShutdownOptions;
import com.peachsoft.otryx.core.RpcShutdownReport;
import com.peachsoft.otryx.spring.runtime.OtryxRpcRuntimeCoordinator;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * Spring SmartLifecycle 关闭回调和总预算交接测试。
 *
 * @Author OpenAI
 * @Version 1.0.0-SNAPSHOT
 * @CreateTime 2026/10/12 15:32
 */
class OtryxRpcServerLifecycleTest {

    @Test
    void springStopCallbackMustWaitForBoundedShutdownStage() {
        OtryxRpcRuntimeCoordinator coordinator =
                mock(OtryxRpcRuntimeCoordinator.class);
        OtryxRpcServer server = mock(OtryxRpcServer.class);
        RpcShutdownOptions budget =
                new RpcShutdownOptions(Duration.ofSeconds(1));
        CompletableFuture<RpcShutdownReport> pending =
                new CompletableFuture<>();
        when(coordinator.serverIfCreated())
                .thenReturn(Optional.of(server));
        when(coordinator.serverShutdownOptions()).thenReturn(budget);
        when(server.shutdown(any(RpcShutdownOptions.class)))
                .thenReturn(pending);

        OtryxRpcServerLifecycle lifecycle =
                new OtryxRpcServerLifecycle(coordinator);
        AtomicInteger callbackInvocations = new AtomicInteger();
        lifecycle.stop(callbackInvocations::incrementAndGet);
        assertEquals(0, callbackInvocations.get());

        RpcShutdownReport completed = new RpcShutdownReport(
                3L, false, true, true, true, true, 0, 0);
        pending.complete(completed);
        assertEquals(1, callbackInvocations.get());
        assertSame(budget, coordinator.serverShutdownOptions());
    }

    @Test
    void exceptionalShutdownMustStillCompleteSpringCallback() {
        OtryxRpcRuntimeCoordinator coordinator =
                mock(OtryxRpcRuntimeCoordinator.class);
        OtryxRpcServer server = mock(OtryxRpcServer.class);
        when(coordinator.serverIfCreated())
                .thenReturn(Optional.of(server));
        when(coordinator.serverShutdownOptions())
                .thenReturn(new RpcShutdownOptions(Duration.ofSeconds(1)));
        when(server.shutdown(any(RpcShutdownOptions.class)))
                .thenReturn(CompletableFuture.failedFuture(
                        new IllegalStateException("synthetic shutdown failure")));

        AtomicInteger callbackInvocations = new AtomicInteger();
        new OtryxRpcServerLifecycle(coordinator)
                .stop(callbackInvocations::incrementAndGet);
        assertEquals(1, callbackInvocations.get());
    }

    @Test
    void noProviderMustCallSpringCallbackImmediately() {
        OtryxRpcRuntimeCoordinator coordinator =
                mock(OtryxRpcRuntimeCoordinator.class);
        when(coordinator.serverIfCreated()).thenReturn(Optional.empty());
        AtomicInteger callbackInvocations = new AtomicInteger();
        new OtryxRpcServerLifecycle(coordinator)
                .stop(callbackInvocations::incrementAndGet);
        assertEquals(1, callbackInvocations.get());
    }
}
