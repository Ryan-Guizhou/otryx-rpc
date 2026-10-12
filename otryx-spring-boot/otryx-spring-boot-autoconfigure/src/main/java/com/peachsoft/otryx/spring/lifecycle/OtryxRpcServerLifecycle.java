package com.peachsoft.otryx.spring.lifecycle;

import com.peachsoft.otryx.core.OtryxRpcServer;
import com.peachsoft.otryx.core.RpcShutdownReport;
import com.peachsoft.otryx.spring.runtime.OtryxRpcRuntimeCoordinator;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

/**
 * 将已经创建的 OTRYX RPC Provider 接入 Spring 生命周期。
 *
 * @Author Ryan
 * @Version 1.0.0
 * @CreateTime 2026/9/23 10:51
 */
public final class OtryxRpcServerLifecycle implements SmartLifecycle {
    private static final Logger LOGGER =
            LoggerFactory.getLogger(OtryxRpcServerLifecycle.class);

    private final OtryxRpcRuntimeCoordinator coordinator;
    private final AtomicBoolean running = new AtomicBoolean();

    /**
     * 创建 Provider 生命周期适配器。
     *
     * @param coordinator 运行时协调器
     */
    public OtryxRpcServerLifecycle(OtryxRpcRuntimeCoordinator coordinator) {
        this.coordinator = Objects.requireNonNull(coordinator, "coordinator");
    }

    @Override
    public void start() {
        coordinator.autoStartServerIfCreated().ifPresent(this::startServer);
    }

    private void startServer(OtryxRpcServer server) {
        if (running.compareAndSet(false, true)) {
            try {
                server.start().toCompletableFuture().join();
            } catch (RuntimeException error) {
                running.set(false);
                throw error;
            }
        }
    }

    @Override
    public void stop() {
        if (running.compareAndSet(true, false)) {
            coordinator.serverIfCreated().ifPresent(server ->
                    server.shutdown(coordinator.serverShutdownOptions()));
        }
    }

    /**
     * 使用 Spring SmartLifecycle 异步回调协调 Provider 排空完成。
     *
     * <p>Spring LifecycleProcessor 无需阻塞执行 shutdown() 的线程；
     * 超时由 RpcShutdownOptions 中的统一总预算保护。重复调用时由
     * Provider 共享相同关闭 Stage，且每个回调都必须完成。
     *
     * @param callback Spring 当前停止阶段完成后的回调
     */
    @Override
    public void stop(Runnable callback) {
        Objects.requireNonNull(callback, "callback");
        running.set(false);
        try {
            coordinator.serverIfCreated().ifPresentOrElse(
                    server -> server.shutdown(
                                    coordinator.serverShutdownOptions())
                            .whenComplete((report, error) -> {
                                try {
                                    observeShutdown(report, error);
                                } finally {
                                    callback.run();
                                }
                            }),
                    callback);
        } catch (RuntimeException error) {
            LOGGER.warn(
                    "RPC Spring lifecycle shutdown failed: errorType={}",
                    error.getClass().getName());
            callback.run();
        }
    }

    private void observeShutdown(
            RpcShutdownReport report,
            Throwable error) {
        if (error != null) {
            LOGGER.warn(
                    "RPC provider shutdown stage failed: errorType={}",
                    error.getClass().getName());
            return;
        }
        if (report != null && (report.deadlineExceeded()
                || report.remainingAdmissionLeases() > 0
                || report.cleanupFailures() > 0)) {
            LOGGER.warn(
                    "RPC provider shutdown incomplete: timedOut={}, "
                            + "remainingLeases={}, failures={}",
                    report.deadlineExceeded(),
                    report.remainingAdmissionLeases(),
                    report.cleanupFailures());
        }
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    @Override
    public boolean isAutoStartup() {
        return true;
    }

    @Override
    public int getPhase() {
        return Integer.MAX_VALUE - 100;
    }
}
