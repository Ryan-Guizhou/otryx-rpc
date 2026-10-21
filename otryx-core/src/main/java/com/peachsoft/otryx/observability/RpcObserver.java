package com.peachsoft.otryx.observability;

import com.peachsoft.otryx.api.RpcEndpoint;
import com.peachsoft.otryx.api.RpcExecutionMode;
import com.peachsoft.otryx.api.RpcStatus;
import com.peachsoft.otryx.api.ServiceKey;
import java.util.ArrayList;
import java.util.List;

/**
 * OTRYX RPC 低依赖可观测性事件契约。
 *
 * <p>Core 不依赖 Micrometer、OpenTelemetry 或 JFR Adapter。后续观测实现可消费本契约，
 * 将事件映射为指标、Trace 或 JFR Event。默认 NOOP Observer 的热路径不创建事件对象。
 *
 * <p>实现不应阻塞调用线程，也不应把异常传播回 RPC 主链。
 *
 * @Author Ryan
 * @Version 1.0.0
 * @CreateTime 2026/9/28 10:13
 */
public interface RpcObserver {

    /**
     * 返回 Observer 是否启用。
     *
     * @return 是否需要产生观测回调
     */
    default boolean enabled() {
        return true;
    }

    /**
     * 连接握手完成并进入可用状态。
     *
     * @param role 本地连接角色
     * @param endpoint 对端端点
     * @param durationNanos 从 TCP connect/accept 到握手完成的耗时
     */
    default void onConnectionEstablished(
            RpcConnectionRole role,
            RpcEndpoint endpoint,
            long durationNanos) {
    }

    /**
     * Consumer 已安排下一次连接重建。
     *
     * @param endpoint 目标端点
     * @param attempt 连续连接失败后的重连序号，从 1 开始
     * @param delayMillis 计划 full-jitter 退避时间
     */
    default void onConnectionReconnectScheduled(
            RpcEndpoint endpoint,
            int attempt,
            long delayMillis) {
    }

    /**
     * Heartbeat 超时摘除连接。
     *
     * @param role 本地连接角色
     * @param endpoint 对端端点
     * @param idleNanos 自 PING 发出后等待入站活跃的时长
     */
    default void onConnectionHeartbeatTimeout(
            RpcConnectionRole role,
            RpcEndpoint endpoint,
            long idleNanos) {
    }

    /**
     * 连接关闭。
     *
     * @param role 本地连接角色
     * @param endpoint 对端端点
     * @param reason 归一化关闭原因
     * @param error 异常原因；正常关闭时为 null
     */
    default void onConnectionClosed(
            RpcConnectionRole role,
            RpcEndpoint endpoint,
            RpcConnectionCloseReason reason,
            Throwable error) {
    }

    /**
     * Registry 控制面操作完成。
     *
     * @param registryType Registry SPI 类型
     * @param operation 操作类型
     * @param durationNanos 操作耗时
     * @param error 失败原因；成功时为 null
     */
    default void onRegistryOperationCompleted(
            String registryType,
            RpcRegistryOperation operation,
            long durationNanos,
            Throwable error) {
    }

    /**
     * Registry 自动恢复动作完成。
     *
     * @param registryType Registry SPI 类型
     * @param action 恢复动作
     * @param durationNanos 恢复耗时
     * @param error 失败原因；成功时为 null
     */
    default void onRegistryRecoveryCompleted(
            String registryType,
            RpcRegistryRecoveryAction action,
            long durationNanos,
            Throwable error) {
    }

    /**
     * TLS/mTLS 握手完成。
     *
     * @param role 本地连接角色
     * @param endpoint 对端端点
     * @param mode Transport 安全模式
     * @param durationNanos TLS handshake 耗时
     * @param error 握手失败原因；成功时为 null
     */
    default void onTlsHandshakeCompleted(
            RpcConnectionRole role,
            RpcEndpoint endpoint,
            RpcSecurityMode mode,
            long durationNanos,
            Throwable error) {
    }

    /**
     * TLS 证书热更新完成。
     *
     * @param mode Transport 安全模式
     * @param outcome 更新结果
     * @param durationNanos reload 耗时
     * @param error 失败原因；成功时为 null
     */
    default void onCertificateReloadCompleted(
            RpcSecurityMode mode,
            RpcCertificateReloadOutcome outcome,
            long durationNanos,
            Throwable error) {
    }

    /**
     * 证书进入过期告警窗口。
     *
     * @param mode Transport 安全模式
     * @param remainingMillis 距证书过期的剩余毫秒数
     */
    default void onCertificateExpiryWarning(
            RpcSecurityMode mode,
            long remainingMillis) {
    }

    /**
     * Consumer 单次网络 attempt 完成。
     *
     * @param serviceKey 服务键
     * @param methodId 方法 ID
     * @param endpoint 目标端点；未选出实例时为 null
     * @param attempt attempt 序号，从 1 开始
     * @param durationNanos attempt 耗时
     * @param status 归一化 RPC 状态
     * @param error 失败原因；成功时为 null
     */
    default void onClientAttemptCompleted(
            ServiceKey serviceKey,
            int methodId,
            RpcEndpoint endpoint,
            int attempt,
            long durationNanos,
            RpcStatus status,
            Throwable error) {
    }

    /**
     * Consumer 逻辑 RPC inflight 数变化。
     *
     * @param delta +1 表示开始，-1 表示结束
     */
    default void onClientInflightChanged(int delta) {
    }

    /**
     * Provider 业务 invocation inflight 数变化。
     *
     * @param delta +1 表示开始，-1 表示结束
     */
    default void onServerInflightChanged(int delta) {
    }

    /**
     * Provider 已准入请求的原始 Frame 总字节数发生变化。
     *
     * <p>不包含 Transport 尚未提交的请求、返回字节及 Fory 反序列化对象。
     *
     * @param delta 正值表示已接纳，负值表示请求完成释放
     */
    default void onServerInflightBytesChanged(long delta) {
    }

    /**
     * Consumer 方法级 Circuit Breaker 状态。
     *
     * @param serviceKey 服务键
     * @param methodId 方法 ID
     * @param state 当前状态
     */
    default void onClientCircuitStateChanged(
            ServiceKey serviceKey,
            int methodId,
            RpcCircuitState state) {
    }

    /**
     * Consumer 一次逻辑 RPC 调用完成。
     *
     * <p>一次逻辑调用可能包含多个网络 Attempt，该事件只回调一次。
     *
     * @param serviceKey 服务键
     * @param methodId 方法 ID
     * @param durationNanos 逻辑调用总耗时
     * @param status 最终状态
     * @param error 最终失败；成功时为 null
     */
    default void onClientCallCompleted(
            ServiceKey serviceKey,
            int methodId,
            long durationNanos,
            RpcStatus status,
            Throwable error) {
    }

    /**
     * Consumer 已决定调度下一次 Retry。
     *
     * @param serviceKey 服务键
     * @param methodId 方法 ID
     * @param nextAttempt 下一次 attempt 序号
     * @param delayMillis 计划退避时间
     * @param cause 触发 Retry 的失败
     */
    default void onClientRetryScheduled(
            ServiceKey serviceKey,
            int methodId,
            int nextAttempt,
            long delayMillis,
            Throwable cause) {
    }

    /**
     * Consumer 已无法继续自动 Retry。
     *
     * <p>只对显式幂等且当前失败可重试的方法产生该事件。
     *
     * @param serviceKey 服务键
     * @param methodId 方法 ID
     * @param reason Retry 停止原因
     * @param cause 最终触发失败
     */
    default void onClientRetryExhausted(
            ServiceKey serviceKey,
            int methodId,
            RpcRetryExhaustionReason reason,
            Throwable cause) {
    }

    /**
     * Consumer 因方法级 Circuit Open 拒绝调用。
     *
     * @param serviceKey 服务键
     * @param methodId 方法 ID
     */
    default void onClientCircuitRejected(
            ServiceKey serviceKey,
            int methodId) {
    }

    /**
     * Consumer 因连续基础设施失败临时剔除端点。
     *
     * @param serviceKey 服务键
     * @param endpoint 被剔除端点
     * @param ejectionMillis 剔除窗口毫秒数
     */
    default void onEndpointEjected(
            ServiceKey serviceKey,
            RpcEndpoint endpoint,
            long ejectionMillis) {
    }

    /**
     * Provider 在进入业务执行前因容量限制拒绝请求。
     *
     * @param serviceId 服务 ID
     * @param methodId 方法 ID
     * @param reason 低基数拒绝原因
     */
    default void onServerAdmissionRejected(
            int serviceId,
            int methodId,
            String reason) {
    }

    /**
     * Provider 业务 invocation 完成。
     *
     * @param serviceId 服务 ID
     * @param methodId 方法 ID
     * @param executionMode 执行资源模式
     * @param durationNanos 业务 invocation 耗时
     * @param status 返回状态
     * @param error 框架执行失败；正常业务错误已编码到 status，通常为 null
     */
    default void onServerInvocationCompleted(
            int serviceId,
            int methodId,
            RpcExecutionMode executionMode,
            long durationNanos,
            RpcStatus status,
            Throwable error) {
    }

    /**
     * 记录一次真实 RPC 调用的固定目录失败原因；只允许有限枚举，不携带动态标签。
     *
     * <p>诊断 API 自身不会产生此事件。
     *
     * @param reason 有界失败原因
     */
    default void onClientDirectoryFailure(RpcDirectoryFailureReason reason) {
    }

    /**
     * 返回全局 NOOP Observer。
     *
     * @return 不产生回调的 Observer
     */
    static RpcObserver noop() {
        return NoopHolder.INSTANCE;
    }

    /**
     * 合并多个 Observer。
     *
     * <p>单个 Observer 抛出的运行时异常会被隔离，避免观测系统影响 RPC 主链。
     *
     * @param observers Observer 集合
     * @return 组合后的 Observer
     */
    static RpcObserver composite(Iterable<? extends RpcObserver> observers) {
        List<RpcObserver> values = new ArrayList<>();
        for (RpcObserver observer : observers) {
            if (observer != null && observer.enabled()) {
                values.add(observer);
            }
        }
        if (values.isEmpty()) {
            return noop();
        }
        List<RpcObserver> immutable = List.copyOf(values);
        return new RpcObserver() {
            @Override
            public void onConnectionEstablished(
                    RpcConnectionRole role,
                    RpcEndpoint endpoint,
                    long durationNanos) {
                immutable.forEach(observer -> safely(() ->
                        observer.onConnectionEstablished(
                                role,
                                endpoint,
                                durationNanos)));
            }

            @Override
            public void onConnectionReconnectScheduled(
                    RpcEndpoint endpoint,
                    int attempt,
                    long delayMillis) {
                immutable.forEach(observer -> safely(() ->
                        observer.onConnectionReconnectScheduled(
                                endpoint,
                                attempt,
                                delayMillis)));
            }

            @Override
            public void onConnectionHeartbeatTimeout(
                    RpcConnectionRole role,
                    RpcEndpoint endpoint,
                    long idleNanos) {
                immutable.forEach(observer -> safely(() ->
                        observer.onConnectionHeartbeatTimeout(
                                role,
                                endpoint,
                                idleNanos)));
            }

            @Override
            public void onConnectionClosed(
                    RpcConnectionRole role,
                    RpcEndpoint endpoint,
                    RpcConnectionCloseReason reason,
                    Throwable error) {
                immutable.forEach(observer -> safely(() ->
                        observer.onConnectionClosed(
                                role,
                                endpoint,
                                reason,
                                error)));
            }

            @Override
            public void onRegistryOperationCompleted(
                    String registryType,
                    RpcRegistryOperation operation,
                    long durationNanos,
                    Throwable error) {
                immutable.forEach(observer -> safely(() ->
                        observer.onRegistryOperationCompleted(
                                registryType,
                                operation,
                                durationNanos,
                                error)));
            }

            @Override
            public void onRegistryRecoveryCompleted(
                    String registryType,
                    RpcRegistryRecoveryAction action,
                    long durationNanos,
                    Throwable error) {
                immutable.forEach(observer -> safely(() ->
                        observer.onRegistryRecoveryCompleted(
                                registryType,
                                action,
                                durationNanos,
                                error)));
            }

            @Override
            public void onTlsHandshakeCompleted(
                    RpcConnectionRole role,
                    RpcEndpoint endpoint,
                    RpcSecurityMode mode,
                    long durationNanos,
                    Throwable error) {
                immutable.forEach(observer -> safely(() ->
                        observer.onTlsHandshakeCompleted(
                                role,
                                endpoint,
                                mode,
                                durationNanos,
                                error)));
            }

            @Override
            public void onCertificateReloadCompleted(
                    RpcSecurityMode mode,
                    RpcCertificateReloadOutcome outcome,
                    long durationNanos,
                    Throwable error) {
                immutable.forEach(observer -> safely(() ->
                        observer.onCertificateReloadCompleted(
                                mode,
                                outcome,
                                durationNanos,
                                error)));
            }

            @Override
            public void onCertificateExpiryWarning(
                    RpcSecurityMode mode,
                    long remainingMillis) {
                immutable.forEach(observer -> safely(() ->
                        observer.onCertificateExpiryWarning(
                                mode,
                                remainingMillis)));
            }

            @Override
            public void onClientAttemptCompleted(
                    ServiceKey serviceKey,
                    int methodId,
                    RpcEndpoint endpoint,
                    int attempt,
                    long durationNanos,
                    RpcStatus status,
                    Throwable error) {
                immutable.forEach(observer -> safely(() ->
                        observer.onClientAttemptCompleted(
                                serviceKey,
                                methodId,
                                endpoint,
                                attempt,
                                durationNanos,
                                status,
                                error)));
            }

            @Override
            public void onClientDirectoryFailure(RpcDirectoryFailureReason reason) {
                immutable.forEach(observer -> safely(() ->
                        observer.onClientDirectoryFailure(reason)));
            }

            @Override
            public void onClientInflightChanged(int delta) {
                immutable.forEach(observer -> safely(() ->
                        observer.onClientInflightChanged(delta)));
            }

            @Override
            public void onServerInflightChanged(int delta) {
                immutable.forEach(observer -> safely(() ->
                        observer.onServerInflightChanged(delta)));
            }

            @Override
            public void onServerInflightBytesChanged(long delta) {
                immutable.forEach(observer -> safely(() ->
                        observer.onServerInflightBytesChanged(delta)));
            }

            @Override
            public void onClientCircuitStateChanged(
                    ServiceKey serviceKey,
                    int methodId,
                    RpcCircuitState state) {
                immutable.forEach(observer -> safely(() ->
                        observer.onClientCircuitStateChanged(
                                serviceKey,
                                methodId,
                                state)));
            }

            @Override
            public void onClientCallCompleted(
                    ServiceKey serviceKey,
                    int methodId,
                    long durationNanos,
                    RpcStatus status,
                    Throwable error) {
                immutable.forEach(observer -> safely(() ->
                        observer.onClientCallCompleted(
                                serviceKey,
                                methodId,
                                durationNanos,
                                status,
                                error)));
            }

            @Override
            public void onClientRetryScheduled(
                    ServiceKey serviceKey,
                    int methodId,
                    int nextAttempt,
                    long delayMillis,
                    Throwable cause) {
                immutable.forEach(observer -> safely(() ->
                        observer.onClientRetryScheduled(
                                serviceKey,
                                methodId,
                                nextAttempt,
                                delayMillis,
                                cause)));
            }

            @Override
            public void onClientRetryExhausted(
                    ServiceKey serviceKey,
                    int methodId,
                    RpcRetryExhaustionReason reason,
                    Throwable cause) {
                immutable.forEach(observer -> safely(() ->
                        observer.onClientRetryExhausted(
                                serviceKey,
                                methodId,
                                reason,
                                cause)));
            }

            @Override
            public void onClientCircuitRejected(
                    ServiceKey serviceKey,
                    int methodId) {
                immutable.forEach(observer -> safely(() ->
                        observer.onClientCircuitRejected(
                                serviceKey,
                                methodId)));
            }

            @Override
            public void onEndpointEjected(
                    ServiceKey serviceKey,
                    RpcEndpoint endpoint,
                    long ejectionMillis) {
                immutable.forEach(observer -> safely(() ->
                        observer.onEndpointEjected(
                                serviceKey,
                                endpoint,
                                ejectionMillis)));
            }

            @Override
            public void onServerAdmissionRejected(
                    int serviceId,
                    int methodId,
                    String reason) {
                immutable.forEach(observer -> safely(() ->
                        observer.onServerAdmissionRejected(
                                serviceId,
                                methodId,
                                reason)));
            }

            @Override
            public void onServerInvocationCompleted(
                    int serviceId,
                    int methodId,
                    RpcExecutionMode executionMode,
                    long durationNanos,
                    RpcStatus status,
                    Throwable error) {
                immutable.forEach(observer -> safely(() ->
                        observer.onServerInvocationCompleted(
                                serviceId,
                                methodId,
                                executionMode,
                                durationNanos,
                                status,
                                error)));
            }
        };
    }

    private static void safely(Runnable callback) {
        try {
            callback.run();
        } catch (RuntimeException ignored) {
            // Observability must never fail the RPC data path.
        }
    }

    /** NOOP Holder。 */
    final class NoopHolder {
        private static final RpcObserver INSTANCE = new RpcObserver() {
            @Override
            public boolean enabled() {
                return false;
            }
        };

        private NoopHolder() {
        }
    }
}
