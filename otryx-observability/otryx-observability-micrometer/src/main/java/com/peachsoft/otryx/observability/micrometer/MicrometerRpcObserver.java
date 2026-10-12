package com.peachsoft.otryx.observability.micrometer;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import com.peachsoft.otryx.api.RpcEndpoint;
import com.peachsoft.otryx.api.RpcExecutionMode;
import com.peachsoft.otryx.api.RpcStatus;
import com.peachsoft.otryx.api.ServiceKey;
import com.peachsoft.otryx.observability.RpcCertificateReloadOutcome;
import com.peachsoft.otryx.observability.RpcCircuitState;
import com.peachsoft.otryx.observability.RpcConnectionCloseReason;
import com.peachsoft.otryx.observability.RpcConnectionRole;
import com.peachsoft.otryx.observability.RpcFailureClassifier;
import com.peachsoft.otryx.observability.RpcObserver;
import com.peachsoft.otryx.observability.RpcRegistryOperation;
import com.peachsoft.otryx.observability.RpcRegistryRecoveryAction;
import com.peachsoft.otryx.observability.RpcRetryExhaustionReason;
import com.peachsoft.otryx.observability.RpcSecurityMode;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 将 OTRYX RPC 低依赖 Observer 事件映射为 Micrometer 指标。
 *
 * <p>默认标签不包含 Endpoint、InstanceId、异常消息或 TraceId，避免高基数。
 *
 * @Author Ryan
 * @Version 1.0.0
 * @CreateTime 2026/9/29 14:51
 */
public final class MicrometerRpcObserver implements RpcObserver {

    private static final String METRIC_CONNECTION_ACTIVE = "otryx.rpc.connection.active";
    private static final String METRIC_CLIENT_INFLIGHT = "otryx.rpc.client.inflight";
    private static final String METRIC_SERVER_INFLIGHT = "otryx.rpc.server.inflight";
    private static final String METRIC_SERVER_INFLIGHT_BYTES = "otryx.rpc.server.inflight.bytes";
    private static final String METRIC_CONNECTION_ESTABLISHED = "otryx.rpc.connection.established";
    private static final String METRIC_CONNECTION_RECONNECTS = "otryx.rpc.connection.reconnects";
    private static final String METRIC_CONNECTION_HEARTBEAT_TIMEOUTS = "otryx.rpc.connection.heartbeat.timeouts";
    private static final String METRIC_CONNECTION_CLOSED = "otryx.rpc.connection.closed";
    private static final String METRIC_REGISTRY_OPERATIONS = "otryx.rpc.registry.operations";
    private static final String METRIC_REGISTRY_FAILURES = "otryx.rpc.registry.failures";
    private static final String METRIC_REGISTRY_RECOVERIES = "otryx.rpc.registry.recoveries";
    private static final String METRIC_TLS_HANDSHAKE = "otryx.rpc.tls.handshake";
    private static final String METRIC_TLS_HANDSHAKE_FAILURES = "otryx.rpc.tls.handshake.failures";
    private static final String METRIC_TLS_CERTIFICATE_RELOAD = "otryx.rpc.tls.certificate.reload";
    private static final String METRIC_TLS_CERTIFICATE_EXPIRY_WARNINGS = "otryx.rpc.tls.certificate.expiry.warnings";
    private static final String METRIC_CLIENT_ATTEMPTS = "otryx.rpc.client.attempts";
    private static final String METRIC_CLIENT_FAILURES = "otryx.rpc.client.failures";
    private static final String METRIC_CLIENT_CALLS = "otryx.rpc.client.calls";
    private static final String METRIC_CLIENT_TIMEOUTS = "otryx.rpc.client.timeouts";
    private static final String METRIC_CLIENT_RETRY_EXHAUSTED = "otryx.rpc.client.retry.exhausted";
    private static final String METRIC_CLIENT_CIRCUIT_REJECTED = "otryx.rpc.client.circuit.rejected";
    private static final String METRIC_CLIENT_OUTLIER_EJECTED = "otryx.rpc.client.outlier.ejected";
    private static final String METRIC_CLIENT_RETRIES = "otryx.rpc.client.retries";
    private static final String METRIC_SERVER_ADMISSION_REJECTED = "otryx.rpc.server.admission.rejected";
    private static final String METRIC_SERVER_INVOCATIONS = "otryx.rpc.server.invocations";
    private static final String METRIC_SERVER_FAILURES = "otryx.rpc.server.failures";
    private static final String METRIC_SERVER_OVERLOADED = "otryx.rpc.server.overloaded";
    private static final String METRIC_CLIENT_CIRCUIT_STATE = "otryx.rpc.client.circuit.state";
    private static final String TAG_REGISTRY = "registry";
    private static final String TAG_ROLE = "role";
    private static final String TAG_REASON = "reason";
    private static final String TAG_OPERATION = "operation";
    private static final String TAG_OUTCOME = "outcome";
    private static final String TAG_ACTION = "action";
    private static final String TAG_MODE = "mode";
    private static final String TAG_STATUS = "status";
    private static final String TAG_CATEGORY = "category";
    private static final String TAG_STATE = "state";
    private static final String BASE_UNIT_BYTES = "bytes";
    private static final String OUTCOME_SUCCESS = "SUCCESS";
    private static final String OUTCOME_FAILURE = "FAILURE";

    private final MeterRegistry registry;
    private final AtomicInteger activeConnections =
            new AtomicInteger();
    private final AtomicInteger clientInflight =
            new AtomicInteger();
    private final AtomicInteger serverInflight =
            new AtomicInteger();
    private final AtomicLong serverInflightBytes =
            new AtomicLong();
    private final AtomicInteger circuitClosed =
            new AtomicInteger();
    private final AtomicInteger circuitOpen =
            new AtomicInteger();
    private final AtomicInteger circuitHalfOpen =
            new AtomicInteger();
    private final ConcurrentMap<String, RpcCircuitState> circuitStates =
            new ConcurrentHashMap<>();

    /**
     * 创建 Micrometer Observer。
     *
     * @param registry MeterRegistry
     */
    public MicrometerRpcObserver(MeterRegistry registry) {
        this.registry = Objects.requireNonNull(
                registry,
                "registry");
        Gauge.builder(
                        METRIC_CONNECTION_ACTIVE,
                        activeConnections,
                        AtomicInteger::get)
                .description("Active OTRYX RPC transport connections")
                .register(registry);
        Gauge.builder(
                        METRIC_CLIENT_INFLIGHT,
                        clientInflight,
                        AtomicInteger::get)
                .description("Inflight logical OTRYX RPC client calls")
                .register(registry);
        Gauge.builder(
                        METRIC_SERVER_INFLIGHT,
                        serverInflight,
                        AtomicInteger::get)
                .description("Inflight OTRYX RPC provider invocations")
                .register(registry);
        Gauge.builder(
                        METRIC_SERVER_INFLIGHT_BYTES,
                        serverInflightBytes,
                        AtomicLong::get)
                .baseUnit(BASE_UNIT_BYTES)
                .description("Accepted OTRYX RPC provider request Frame bytes")
                .register(registry);
        registerCircuitGauge(
                RpcCircuitState.CLOSED,
                circuitClosed);
        registerCircuitGauge(
                RpcCircuitState.OPEN,
                circuitOpen);
        registerCircuitGauge(
                RpcCircuitState.HALF_OPEN,
                circuitHalfOpen);
    }

    @Override
    public void onConnectionEstablished(
            RpcConnectionRole role,
            RpcEndpoint endpoint,
            long durationNanos) {
        activeConnections.incrementAndGet();
        timer(
                METRIC_CONNECTION_ESTABLISHED,
                TAG_ROLE,
                role.name())
                .record(
                        Duration.ofNanos(
                                Math.max(0L, durationNanos)));
    }

    @Override
    public void onConnectionReconnectScheduled(
            RpcEndpoint endpoint,
            int attempt,
            long delayMillis) {
        registry.counter(
                METRIC_CONNECTION_RECONNECTS)
                .increment();
    }

    @Override
    public void onConnectionHeartbeatTimeout(
            RpcConnectionRole role,
            RpcEndpoint endpoint,
            long idleNanos) {
        registry.counter(
                        METRIC_CONNECTION_HEARTBEAT_TIMEOUTS,
                        TAG_ROLE,
                        role.name())
                .increment();
    }

    @Override
    public void onConnectionClosed(
            RpcConnectionRole role,
            RpcEndpoint endpoint,
            RpcConnectionCloseReason reason,
            Throwable error) {
        activeConnections.updateAndGet(
                current -> Math.max(0, current - 1));
        registry.counter(
                        METRIC_CONNECTION_CLOSED,
                        TAG_ROLE,
                        role.name(),
                        TAG_REASON,
                        reason.name())
                .increment();
    }

    @Override
    public void onRegistryOperationCompleted(
            String registryType,
            RpcRegistryOperation operation,
            long durationNanos,
            Throwable error) {
        timer(
                METRIC_REGISTRY_OPERATIONS,
                TAG_REGISTRY,
                safe(registryType),
                TAG_OPERATION,
                operation.name(),
                TAG_OUTCOME,
                outcome(error))
                .record(Duration.ofNanos(
                        Math.max(0L, durationNanos)));
        if (error != null) {
            registry.counter(
                            METRIC_REGISTRY_FAILURES,
                            TAG_REGISTRY,
                            safe(registryType),
                            TAG_OPERATION,
                            operation.name())
                    .increment();
        }
    }

    @Override
    public void onRegistryRecoveryCompleted(
            String registryType,
            RpcRegistryRecoveryAction action,
            long durationNanos,
            Throwable error) {
        timer(
                METRIC_REGISTRY_RECOVERIES,
                TAG_REGISTRY,
                safe(registryType),
                TAG_ACTION,
                action.name(),
                TAG_OUTCOME,
                outcome(error))
                .record(Duration.ofNanos(
                        Math.max(0L, durationNanos)));
    }

    @Override
    public void onTlsHandshakeCompleted(
            RpcConnectionRole role,
            RpcEndpoint endpoint,
            RpcSecurityMode mode,
            long durationNanos,
            Throwable error) {
        timer(
                METRIC_TLS_HANDSHAKE,
                TAG_ROLE,
                role.name(),
                TAG_MODE,
                mode.name(),
                TAG_OUTCOME,
                outcome(error))
                .record(Duration.ofNanos(
                        Math.max(0L, durationNanos)));
        if (error != null) {
            registry.counter(
                            METRIC_TLS_HANDSHAKE_FAILURES,
                            TAG_ROLE,
                            role.name(),
                            TAG_MODE,
                            mode.name())
                    .increment();
        }
    }

    @Override
    public void onCertificateReloadCompleted(
            RpcSecurityMode mode,
            RpcCertificateReloadOutcome outcome,
            long durationNanos,
            Throwable error) {
        registry.counter(
                        METRIC_TLS_CERTIFICATE_RELOAD,
                        TAG_MODE,
                        mode.name(),
                        TAG_OUTCOME,
                        outcome.name())
                .increment();
    }

    @Override
    public void onCertificateExpiryWarning(
            RpcSecurityMode mode,
            long remainingMillis) {
        registry.counter(
                        METRIC_TLS_CERTIFICATE_EXPIRY_WARNINGS,
                        TAG_MODE,
                        mode.name())
                .increment();
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
        timer(
                METRIC_CLIENT_ATTEMPTS,
                TAG_STATUS,
                status.name(),
                TAG_CATEGORY,
                RpcFailureClassifier.classify(
                                status,
                                error)
                        .name())
                .record(Duration.ofNanos(
                        Math.max(0L, durationNanos)));
        if (error != null || status != RpcStatus.OK) {
            registry.counter(
                            METRIC_CLIENT_FAILURES,
                            TAG_STATUS,
                            status.name())
                    .increment();
        }
    }

    @Override
    public void onClientInflightChanged(int delta) {
        clientInflight.updateAndGet(
                current -> Math.max(0, current + delta));
    }

    @Override
    public void onServerInflightChanged(int delta) {
        serverInflight.updateAndGet(
                current -> Math.max(0, current + delta));
    }

    @Override
    public void onServerInflightBytesChanged(long delta) {
        serverInflightBytes.updateAndGet(current ->
                Math.max(0L, current + delta));
    }

    @Override
    public void onClientCircuitStateChanged(
            ServiceKey serviceKey,
            int methodId,
            RpcCircuitState state) {
        String key = serviceKey.canonicalName()
                + '#'
                + methodId;
        circuitStates.compute(key, (ignored, previous) -> {
            if (previous == state) {
                return state;
            }
            if (previous != null) {
                circuitCounter(previous)
                        .updateAndGet(value ->
                                Math.max(0, value - 1));
            }
            circuitCounter(state).incrementAndGet();
            return state;
        });
    }

    @Override
    public void onClientCallCompleted(
            ServiceKey serviceKey,
            int methodId,
            long durationNanos,
            RpcStatus status,
            Throwable error) {
        timer(
                METRIC_CLIENT_CALLS,
                TAG_STATUS,
                status.name(),
                TAG_CATEGORY,
                RpcFailureClassifier.classify(
                                status,
                                error)
                        .name())
                .record(Duration.ofNanos(
                        Math.max(0L, durationNanos)));
        if (status == RpcStatus.DEADLINE_EXCEEDED) {
            registry.counter(
                    METRIC_CLIENT_TIMEOUTS)
                    .increment();
        }
    }

    @Override
    public void onClientRetryExhausted(
            ServiceKey serviceKey,
            int methodId,
            RpcRetryExhaustionReason reason,
            Throwable cause) {
        registry.counter(
                        METRIC_CLIENT_RETRY_EXHAUSTED,
                        TAG_REASON,
                        reason.name())
                .increment();
    }

    @Override
    public void onClientCircuitRejected(
            ServiceKey serviceKey,
            int methodId) {
        registry.counter(
                METRIC_CLIENT_CIRCUIT_REJECTED)
                .increment();
    }

    @Override
    public void onEndpointEjected(
            ServiceKey serviceKey,
            RpcEndpoint endpoint,
            long ejectionMillis) {
        registry.counter(
                METRIC_CLIENT_OUTLIER_EJECTED)
                .increment();
    }

    @Override
    public void onClientRetryScheduled(
            ServiceKey serviceKey,
            int methodId,
            int nextAttempt,
            long delayMillis,
            Throwable cause) {
        registry.counter(
                        METRIC_CLIENT_RETRIES)
                .increment();
    }

    @Override
    public void onServerAdmissionRejected(
            int serviceId,
            int methodId,
            String reason) {
        registry.counter(
                        METRIC_SERVER_ADMISSION_REJECTED,
                        TAG_REASON,
                        safe(reason))
                .increment();
    }

    @Override
    public void onServerInvocationCompleted(
            int serviceId,
            int methodId,
            RpcExecutionMode executionMode,
            long durationNanos,
            RpcStatus status,
            Throwable error) {
        timer(
                METRIC_SERVER_INVOCATIONS,
                "execution",
                executionMode.name(),
                TAG_STATUS,
                status.name(),
                TAG_CATEGORY,
                RpcFailureClassifier.classify(
                                status,
                                error)
                        .name())
                .record(Duration.ofNanos(
                        Math.max(0L, durationNanos)));
        if (error != null || status != RpcStatus.OK) {
            registry.counter(
                            METRIC_SERVER_FAILURES,
                            TAG_STATUS,
                            status.name())
                    .increment();
        }
        if (status == RpcStatus.OVERLOADED) {
            registry.counter(
                            METRIC_SERVER_OVERLOADED,
                            "execution",
                            executionMode.name())
                    .increment();
        }
    }

    private void registerCircuitGauge(
            RpcCircuitState state,
            AtomicInteger value) {
        Gauge.builder(
                        METRIC_CLIENT_CIRCUIT_STATE,
                        value,
                        AtomicInteger::get)
                .tag(TAG_STATE, state.name())
                .description(
                        "OTRYX RPC client method circuit breakers by state")
                .register(registry);
    }

    private AtomicInteger circuitCounter(
            RpcCircuitState state) {
        return switch (state) {
            case CLOSED -> circuitClosed;
            case OPEN -> circuitOpen;
            case HALF_OPEN -> circuitHalfOpen;
        };
    }

    private Timer timer(
            String name,
            String... tags) {
        return registry.timer(name, tags);
    }

    private static String outcome(Throwable error) {
        return error == null ? OUTCOME_SUCCESS : OUTCOME_FAILURE;
    }

    private static String safe(String value) {
        return value == null || value.isBlank()
                ? "unknown"
                : value;
    }
}
