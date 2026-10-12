package com.peachsoft.otryx.spring.autoconfigure;

import com.peachsoft.otryx.codec.fory.ForyRpcSecurityOptions;
import com.peachsoft.otryx.observability.RpcSecurityMode;
import java.time.Duration;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Spring Boot 配置入口的集中参数校验与脱敏启动诊断。
 *
 * <p>在 ConfigurationProperties 完成绑定后执行，不创建 Transport/Registry，
 * 不触发网络访问，也不会向日志输出注册中心凭据或证书内容。
 *
 * @Author Ryan
 * @Version 1.0.0
 * @CreateTime 2026/10/9 16:12
 */
final class OtryxRpcStartupDiagnostics {
    private static final Logger LOGGER =
            LoggerFactory.getLogger(OtryxRpcStartupDiagnostics.class);
    private static final String PREFIX = "otryx.rpc.";
    private static final String NACOS_REGISTRY_TYPE = "nacos";

    private OtryxRpcStartupDiagnostics() {
    }

    /**
     * 校验全部已启用的运行时配置，并输出不含凭据的有效配置摘要。
     *
     * @param properties 已完成 Spring 属性绑定的配置对象
     */
    static void validateAndReport(OtryxRpcProperties properties) {
        OtryxRpcProperties.Registry registry = properties.getRegistry();
        requireText("registry.type", registry.getType());
        positive("registry.lease-ttl-seconds", registry.getLeaseTtlSeconds());
        if (NACOS_REGISTRY_TYPE.equals(registry.getType())) {
            requireText("registry.nacos.group", registry.getNacos().getGroup());
            requireText("registry.nacos.cluster", registry.getNacos().getCluster());
        }

        OtryxRpcProperties.Transport transport = properties.getTransport();
        requireText("transport.type", transport.getType());
        positive("transport.max-inflight-per-connection",
                transport.getMaxInflightPerConnection());
        positive("transport.max-frame-bytes", transport.getMaxFrameBytes());
        positive("transport.max-write-queue-bytes",
                transport.getMaxWriteQueueBytes());
        positive("transport.connections-per-endpoint",
                transport.getConnectionsPerEndpoint());
        positive("transport.connect-timeout", transport.getConnectTimeout());
        positive("transport.handshake-timeout", transport.getHandshakeTimeout());
        positive("transport.heartbeat-interval", transport.getHeartbeatInterval());
        positive("transport.heartbeat-timeout", transport.getHeartbeatTimeout());
        positive("transport.reconnect-base-backoff",
                transport.getReconnectBaseBackoff());
        positive("transport.reconnect-max-backoff",
                transport.getReconnectMaxBackoff());
        if (transport.getReconnectBaseBackoff().compareTo(
                transport.getReconnectMaxBackoff()) > 0) {
            throw invalid(
                    "transport.reconnect-max-backoff",
                    "must not be shorter than reconnect-base-backoff");
        }

        OtryxRpcProperties.Security security = transport.getSecurity();
        if (security.getMode() == null) {
            throw invalid("transport.security.mode", "must not be null");
        }
        positive("transport.security.handshake-timeout",
                security.getHandshakeTimeout());
        positive("transport.security.reload-interval",
                security.getReloadInterval());
        positive("transport.security.expiry-warning-threshold",
                security.getExpiryWarningThreshold());

        OtryxRpcProperties.ForySecurity fory =
                properties.getCodec().getFory();
        if (fory.getMode() == null) {
            throw invalid("codec.fory.mode", "must not be null");
        }
        Set<String> allowedPatterns = fory.getAllowedClassPatterns();
        if (allowedPatterns == null) {
            throw invalid("codec.fory.allowed-class-patterns", "must not be null");
        }
        try {
            new ForyRpcSecurityOptions(
                    fory.getMode(),
                    allowedPatterns,
                    fory.getMaxDepth(),
                    fory.getMaxGraphMemoryBytes(),
                    fory.getMaxPayloadBytes());
        } catch (IllegalArgumentException error) {
            throw invalid("codec.fory", error.getMessage());
        }

        OtryxRpcProperties.Client client = properties.getClient();
        if (client.isEnabled()) {
            positive("client.timeout", client.getTimeout());
            requireText("client.proxy", client.getProxy());
            requireText("client.load-balancer", client.getLoadBalancer());
            validateResilience(client.getResilience());
        }
        OtryxRpcProperties.Server server = properties.getServer();
        if (server.isEnabled()) {
            requireText("server.host", server.getHost());
            port("server.port", server.getPort());
            port("server.advertised-port", server.getAdvertisedPort());
            positive("server.max-concurrent", server.getMaxConcurrent());
            positive("server.drain-timeout", server.getDrainTimeout());
            positive("server.control-plane-timeout",
                    server.getControlPlaneTimeout());
            OtryxRpcProperties.Admission admission = server.getAdmission();
            positive("server.admission.max-inflight-bytes",
                    admission.getMaxInflightBytes());
            nonNegative("server.admission.max-concurrent-per-service",
                    admission.getMaxConcurrentPerService());
            nonNegative("server.admission.max-concurrent-per-method",
                    admission.getMaxConcurrentPerMethod());
            nonNegative("server.admission.max-inflight-bytes-per-service",
                    admission.getMaxInflightBytesPerService());
            nonNegative("server.admission.max-inflight-bytes-per-method",
                    admission.getMaxInflightBytesPerMethod());
            OtryxRpcProperties.Execution execution = server.getExecution();
            positive("server.execution.cpu-parallelism",
                    execution.getCpuParallelism());
            positive("server.execution.cpu-queue-capacity",
                    execution.getCpuQueueCapacity());
        }

        LOGGER.info(
                "OTRYX RPC configured: registry={}, transport={}, client={}, provider={}, "
                        + "proxy={}, codecMode={}, securityMode={}, connectionsPerEndpoint={}, "
                        + "providerMaxConcurrent={}",
                registry.getType(),
                transport.getType(),
                client.isEnabled(),
                server.isEnabled(),
                client.getProxy(),
                fory.getMode(),
                security.getMode(),
                transport.getConnectionsPerEndpoint(),
                server.getMaxConcurrent());
        if (security.getMode() == RpcSecurityMode.PLAINTEXT) {
            LOGGER.warn(
                    "OTRYX RPC uses PLAINTEXT transport; configure TLS/mTLS on untrusted networks");
        }
        if (fory.getMode() == ForyRpcSecurityOptions.Mode.TRUSTED_COMPATIBILITY) {
            LOGGER.warn(
                    "OTRYX RPC Fory uses TRUSTED_COMPATIBILITY; enable STRICT_ALLOWLIST "
                            + "when peers are not fully trusted");
        }
    }

    private static void validateResilience(
            OtryxRpcProperties.Resilience resilience) {
        positive("client.resilience.max-attempts", resilience.getMaxAttempts());
        double ratio = resilience.getRetryBudgetRatio();
        if (!Double.isFinite(ratio) || ratio < 0.0d || ratio > 1.0d) {
            throw invalid("client.resilience.retry-budget-ratio",
                    "must be between 0 and 1");
        }
        nonNegative("client.resilience.retry-budget-min-retries",
                resilience.getRetryBudgetMinRetries());
        if (resilience.getRetryBudgetMaxRetries()
                < resilience.getRetryBudgetMinRetries()) {
            throw invalid("client.resilience.retry-budget-max-retries",
                    "must be >= retry-budget-min-retries");
        }
        nonNegative("client.resilience.retry-base-backoff",
                resilience.getRetryBaseBackoff());
        nonNegative("client.resilience.retry-max-backoff",
                resilience.getRetryMaxBackoff());
        if (resilience.getRetryBaseBackoff().compareTo(
                resilience.getRetryMaxBackoff()) > 0) {
            throw invalid("client.resilience.retry-max-backoff",
                    "must be >= retry-base-backoff");
        }
        positive("client.resilience.outlier-consecutive-failure-threshold",
                resilience.getOutlierConsecutiveFailureThreshold());
        positive("client.resilience.outlier-ejection-duration",
                resilience.getOutlierEjectionDuration());
        positive("client.resilience.circuit-consecutive-failure-threshold",
                resilience.getCircuitConsecutiveFailureThreshold());
        positive("client.resilience.circuit-open-duration",
                resilience.getCircuitOpenDuration());
    }

    private static void requireText(String name, String value) {
        if (value == null || value.isBlank()) {
            throw invalid(name, "must not be blank");
        }
    }

    private static void positive(String name, int value) {
        if (value <= 0) {
            throw invalid(name, "must be positive");
        }
    }

    private static void positive(String name, long value) {
        if (value <= 0L) {
            throw invalid(name, "must be positive");
        }
    }

    private static void positive(String name, Duration value) {
        if (value == null || value.isNegative() || value.isZero()) {
            throw invalid(name, "must be positive");
        }
    }

    private static void nonNegative(String name, int value) {
        if (value < 0) {
            throw invalid(name, "must be non-negative");
        }
    }

    private static void nonNegative(String name, long value) {
        if (value < 0L) {
            throw invalid(name, "must be non-negative");
        }
    }

    private static void nonNegative(String name, Duration value) {
        if (value == null || value.isNegative()) {
            throw invalid(name, "must be non-negative");
        }
    }

    private static void port(String name, int value) {
        if (value < 0 || value > 65535) {
            throw invalid(name, "must be between 0 and 65535");
        }
    }

    private static IllegalArgumentException invalid(String name, String cause) {
        return new IllegalArgumentException(
                "Invalid " + PREFIX + name + ": " + cause);
    }
}
