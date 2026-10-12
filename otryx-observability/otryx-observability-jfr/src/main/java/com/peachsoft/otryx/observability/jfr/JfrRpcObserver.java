package com.peachsoft.otryx.observability.jfr;

import com.peachsoft.otryx.api.RpcEndpoint;
import com.peachsoft.otryx.api.RpcExecutionMode;
import com.peachsoft.otryx.api.RpcStatus;
import com.peachsoft.otryx.api.ServiceKey;
import com.peachsoft.otryx.observability.RpcCertificateReloadOutcome;
import com.peachsoft.otryx.observability.RpcConnectionRole;
import com.peachsoft.otryx.observability.RpcObserver;
import com.peachsoft.otryx.observability.RpcRegistryRecoveryAction;
import com.peachsoft.otryx.observability.RpcSecurityMode;
import java.time.Duration;

/**
 * JFR OTRYX RPC Observer。
 *
 * <p>仅记录失败、恢复以及超过阈值的调用，避免高频正常请求持续产生事件。
 *
 * @Author Ryan
 * @Version 1.0.0
 * @CreateTime 2026/9/29 15:00
 */
public final class JfrRpcObserver implements RpcObserver {
    private static final String TLS_REASON_PREFIX = "tls:";


    private final long slowThresholdNanos;

    /**
     * 创建 JFR Observer。
     *
     * @param slowThreshold 慢调用阈值
     */
    public JfrRpcObserver(Duration slowThreshold) {
        if (slowThreshold == null
                || slowThreshold.isNegative()
                || slowThreshold.isZero()) {
            throw new IllegalArgumentException(
                    "slowThreshold must be positive");
        }
        this.slowThresholdNanos =
                slowThreshold.toNanos();
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
        if (error == null
                && status == RpcStatus.OK
                && durationNanos < slowThresholdNanos) {
            return;
        }
        RpcClientAttemptJfrEvent event =
                new RpcClientAttemptJfrEvent();
        if (!event.isEnabled()) {
            return;
        }
        event.service = serviceKey.canonicalName();
        event.methodId = methodId;
        event.status = status.name();
        event.durationNanos = durationNanos;
        event.commit();
    }

    @Override
    public void onClientRetryScheduled(
            ServiceKey serviceKey,
            int methodId,
            int nextAttempt,
            long delayMillis,
            Throwable cause) {
        RpcRetryJfrEvent event =
                new RpcRetryJfrEvent();
        if (!event.isEnabled()) {
            return;
        }
        event.nextAttempt = nextAttempt;
        event.delayMillis = delayMillis;
        event.causeType = cause == null
                ? "unknown"
                : cause.getClass().getName();
        event.commit();
    }

    @Override
    public void onConnectionReconnectScheduled(
            RpcEndpoint endpoint,
            int attempt,
            long delayMillis) {
        recovery(
                "connection",
                "reconnect",
                "SCHEDULED",
                Duration.ofMillis(delayMillis).toNanos());
    }

    @Override
    public void onConnectionHeartbeatTimeout(
            RpcConnectionRole role,
            RpcEndpoint endpoint,
            long idleNanos) {
        recovery(
                "connection",
                "heartbeat-timeout",
                role.name(),
                idleNanos);
    }

    @Override
    public void onRegistryRecoveryCompleted(
            String registryType,
            RpcRegistryRecoveryAction action,
            long durationNanos,
            Throwable error) {
        recovery(
                "registry:" + registryType,
                action.name(),
                error == null ? "SUCCESS" : "FAILURE",
                durationNanos);
    }

    @Override
    public void onTlsHandshakeCompleted(
            RpcConnectionRole role,
            RpcEndpoint endpoint,
            RpcSecurityMode mode,
            long durationNanos,
            Throwable error) {
        if (error == null
                && durationNanos < slowThresholdNanos) {
            return;
        }
        recovery(
                TLS_REASON_PREFIX + mode.name(),
                "handshake",
                error == null ? "SUCCESS" : "FAILURE",
                durationNanos);
    }

    @Override
    public void onCertificateReloadCompleted(
            RpcSecurityMode mode,
            RpcCertificateReloadOutcome outcome,
            long durationNanos,
            Throwable error) {
        recovery(
                TLS_REASON_PREFIX + mode.name(),
                "certificate-reload",
                outcome.name(),
                durationNanos);
    }

    @Override
    public void onServerInvocationCompleted(
            int serviceId,
            int methodId,
            RpcExecutionMode executionMode,
            long durationNanos,
            RpcStatus status,
            Throwable error) {
        if (error == null
                && status == RpcStatus.OK
                && durationNanos < slowThresholdNanos) {
            return;
        }
        RpcClientAttemptJfrEvent event =
                new RpcClientAttemptJfrEvent();
        if (!event.isEnabled()) {
            return;
        }
        event.service = Integer.toString(serviceId);
        event.methodId = methodId;
        event.status = status.name();
        event.durationNanos = durationNanos;
        event.commit();
    }

    private static void recovery(
            String domain,
            String action,
            String outcome,
            long durationNanos) {
        RpcRecoveryJfrEvent event =
                new RpcRecoveryJfrEvent();
        if (!event.isEnabled()) {
            return;
        }
        event.domain = domain;
        event.action = action;
        event.outcome = outcome;
        event.durationNanos = durationNanos;
        event.commit();
    }
}
