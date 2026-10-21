package com.peachsoft.otryx.spring.autoconfigure;

import com.peachsoft.otryx.codec.fory.ForyRpcSecurityOptions;
import com.peachsoft.otryx.api.RpcSecurityProfile;
import com.peachsoft.otryx.registry.CredentialKind;
import com.peachsoft.otryx.registry.RegistrySecurityOptions;
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
    private static final String CONSUL_REGISTRY_TYPE = "consul";
    private static final String EUREKA_REGISTRY_TYPE = "eureka";
    private static final String REGISTRY_CONTROL_PLANE_TIMEOUT = "registry.control-plane-timeout";
    private static final String CONSUL_DATACENTER = "registry.consul.datacenter";
    private static final String EUREKA_METADATA_BACKEND = "registry.eureka.metadata-backend";
    private static final String EUREKA_METADATA_ENDPOINTS = "registry.eureka.metadata-endpoints";
    private static final Set<String> EUREKA_METADATA_BACKENDS = Set.of("etcd", "nacos", "consul");
    private static final Duration MAX_MEMBERSHIP_CONTROL_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration MAX_AUTHORIZATION_LIFETIME = Duration.ofDays(1);
    private static final Set<String> REGISTRY_NAMES = Set.of("memory", "etcd", "nacos", "consul", "eureka");
    private static final Set<String> TRANSPORT_NAMES = Set.of("vertx");
    private static final Set<String> PROXY_NAMES = Set.of("jdk", "generated");

    private OtryxRpcStartupDiagnostics() {
    }

    /**
     * 校验全部已启用的运行时配置，并输出不含凭据的默认组件配置摘要。
     *
     * <p>配置属性不证明替换组件的实际能力；最终能力由运行时 Builder 单独核验。
     *
     * @param properties 已完成 Spring 属性绑定的配置对象
     */
    static void validateAndReport(OtryxRpcProperties properties) {
        validate(properties);
        report(properties);
    }

    /**
     * 只校验已绑定配置，不创建资源、不输出输入值。
     *
     * @param properties 当前配置
     */
    static void validate(OtryxRpcProperties properties) {
        if (properties.isEnabled()) {
            validateExplicitProfile(properties);
        }
        OtryxRpcProperties.Interceptor interceptor = properties.getInterceptor();
        positive("interceptor.workers", interceptor.getWorkers());
        positive("interceptor.max-active", interceptor.getMaxActive());
        if (interceptor.getWorkers() > com.peachsoft.otryx.interceptor.RpcInterceptorRuntime.MAX_ALLOWED_WORKERS
                || interceptor.getMaxActive()
                        > com.peachsoft.otryx.interceptor.RpcInterceptorRuntime.MAX_ALLOWED_ACTIVE) {
            throw invalid("interceptor", "worker or session capacity exceeds its hard limit");
        }
        OtryxRpcProperties.Registry registry = properties.getRegistry();
        requireText("registry.type", registry.getType());
        positive("registry.lease-ttl-seconds", registry.getLeaseTtlSeconds());
        positive(REGISTRY_CONTROL_PLANE_TIMEOUT, registry.getControlPlaneTimeout());
        if (registry.getControlPlaneTimeout().compareTo(MAX_MEMBERSHIP_CONTROL_TIMEOUT) > 0) {
            throw invalid(REGISTRY_CONTROL_PLANE_TIMEOUT, "must not exceed 30 seconds");
        }
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
        OtryxRpcProperties.TransportAdmission transportAdmission =
                transport.getAdmission();
        positive("transport.admission.max-connections",
                transportAdmission.getMaxConnections());
        positive("transport.admission.max-handshakes",
                transportAdmission.getMaxHandshakes());
        positive("transport.admission.max-buffered-partial-frame-bytes",
                transportAdmission.getMaxBufferedPartialFrameBytes());
        positive("transport.admission.max-buffered-response-bytes",
                transportAdmission.getMaxBufferedResponseBytes());
        positive("transport.admission.partial-frame-timeout",
                transportAdmission.getPartialFrameTimeout());
        if (transportAdmission.getPartialFrameTimeout()
                .compareTo(Duration.ofDays(1)) > 0) {
            throw invalid("transport.admission.partial-frame-timeout",
                    "must not exceed one day");
        }
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
            throw invalid("codec.fory", "type policy or limits rejected");
        }

        if (properties.isEnabled()) {
            validateSecurityProfile(properties, security);
        }

        registry.getSecurity().toOptions();
        if (registry.getMetadataSecurity() != null) {
            if (!EUREKA_REGISTRY_TYPE.equals(registry.getType())) {
                throw invalid("registry.metadata-security",
                        "is only supported for Eureka's independent metadata chain");
            }
            registry.getMetadataSecurity().toOptions();
        }
        security.getIdentity().validatedBindings();
        OtryxRpcProperties.Client client = properties.getClient();
        if (client.isEnabled()) {
            positive("client.timeout", client.getTimeout());
            nonNegative("client.directory-max-staleness", client.getDirectoryMaxStaleness());
            if (client.getDirectoryMaxStaleness().isZero()) {
                throw invalid("client.directory-max-staleness",
                        "Application discovery requires bounded directory staleness");
            }
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
            positive("server.shutdown-timeout", server.getShutdownTimeout());
            if (server.getShutdownTimeout().compareTo(
                    Duration.ofDays(1)) > 0) {
                throw new IllegalArgumentException(
                        "server.shutdown-timeout must be no more than one day");
            }
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
            OtryxRpcProperties.Authorization authorization = server.getAuthorization();
            positive("server.authorization.max-pending", authorization.getMaxPending());
            positive("server.authorization.max-buffered-request-bytes",
                    authorization.getMaxBufferedRequestBytes());
            positive("server.authorization.max-grant-lifetime", authorization.getMaxGrantLifetime());
            if (authorization.getMaxGrantLifetime().compareTo(MAX_AUTHORIZATION_LIFETIME) > 0) {
                throw invalid("server.authorization.max-grant-lifetime", "must not exceed one day");
            }
            positive("server.authorization.timeout", authorization.getTimeout());
            if (authorization.getTimeout().compareTo(Duration.ofDays(1)) > 0) {
                throw invalid("server.authorization.timeout", "must not exceed one day");
            }
            OtryxRpcProperties.Execution execution = server.getExecution();
            positive("server.execution.cpu-parallelism",
                    execution.getCpuParallelism());
            positive("server.execution.cpu-queue-capacity",
                    execution.getCpuQueueCapacity());
        }

    }

    private static void report(OtryxRpcProperties properties) {
        OtryxRpcProperties.Registry registry = properties.getRegistry();
        OtryxRpcProperties.Transport transport = properties.getTransport();
        OtryxRpcProperties.Security security = transport.getSecurity();
        OtryxRpcProperties.ForySecurity fory = properties.getCodec().getFory();
        OtryxRpcProperties.Client client = properties.getClient();
        OtryxRpcProperties.Server server = properties.getServer();
        LOGGER.info(
                "OTRYX RPC default configuration: registry={}, transport={}, client={}, provider={}, "
                        + "proxy={}, defaultForyMode={}, securityMode={}, connectionsPerEndpoint={}, "
                        + "providerMaxConcurrent={}",
                knownName(REGISTRY_NAMES, registry.getType()),
                knownName(TRANSPORT_NAMES, transport.getType()),
                client.isEnabled(),
                server.isEnabled(),
                knownName(PROXY_NAMES, client.getProxy()),
                fory.getMode(),
                security.getMode(),
                transport.getConnectionsPerEndpoint(),
                server.getMaxConcurrent());
        if (security.getMode() == RpcSecurityMode.PLAINTEXT) {
            LOGGER.warn(
                    "OTRYX RPC default transport configuration is PLAINTEXT; "
                            + "configure TLS/mTLS on untrusted networks");
        }
    }

    /**
     * 仅在默认 Fory 实例创建成功后报告其真实类型策略。
     *
     * @param mode 已创建 Fory 实例使用的安全模式
     */
    static void reportDefaultCodec(ForyRpcSecurityOptions.Mode mode) {
        if (mode == ForyRpcSecurityOptions.Mode.TRUSTED_COMPATIBILITY) {
            LOGGER.warn(
                    "OTRYX RPC Fory uses TRUSTED_COMPATIBILITY; enable STRICT_ALLOWLIST "
                            + "when peers are not fully trusted");
        }
    }

    /**
     * 在默认 Registry 工厂加载之前校验其专属后端，错误仅输出公开配置键。
     *
     * <p>自定义 ApplicationRegistry Bean 不经过默认工厂，因此不受未使用的
     * Eureka/Consul 属性影响；其元数据和注册能力仍由自动装配验证。
     *
     * @param registry 当前由 Starter 创建的注册中心配置
     * @param profile 共享安全意图
     */
    static void validateRegistryBackend(OtryxRpcProperties.Registry registry, RpcSecurityProfile profile) {
        if (profile == RpcSecurityProfile.NETWORK_MTLS && !"memory".equals(registry.getType())) {
            requireProtectedRegistry("registry.security", registry.getSecurity().toOptions());
            if (EUREKA_REGISTRY_TYPE.equals(registry.getType())) {
                if (registry.getMetadataSecurity() == null) {
                    throw invalid("registry.metadata-security", "requires independent TLS and credentials");
                }
                requireProtectedRegistry("registry.metadata-security", registry.getMetadataSecurity().toOptions());
            }
        }
        if (CONSUL_REGISTRY_TYPE.equals(registry.getType())) {
            String datacenter = registry.getConsul().getDatacenter();
            if (datacenter != null && !datacenter.isBlank()) {
                throw invalid(CONSUL_DATACENTER,
                        "remove this legacy setting; Consul V2 uses exactly one local Agent "
                                + "selected by otryx.rpc.registry.endpoints");
            }
        }
        if (EUREKA_REGISTRY_TYPE.equals(registry.getType())) {
            String backend = registry.getEureka().getMetadataBackend();
            if (backend == null || !EUREKA_METADATA_BACKENDS.contains(backend)) {
                throw invalid(EUREKA_METADATA_BACKEND,
                        "must select etcd, nacos or consul and install its matching OTRYX RPC adapter");
            }
            requireText(EUREKA_METADATA_ENDPOINTS, registry.getEureka().getMetadataEndpoints());
        }
    }

    private static void validateSecurityProfile(
            OtryxRpcProperties properties,
            OtryxRpcProperties.Security security) {
        RpcSecurityProfile profile = properties.getSecurityProfile();
        validateExplicitProfile(properties);
        if (profile == RpcSecurityProfile.NETWORK_MTLS) {
            if (security.getMode() != RpcSecurityMode.MTLS) {
                throw invalid("transport.security.mode", "NETWORK_MTLS requires mutual TLS");
            }
            requireText("transport.security.certificate-path", security.getCertificatePath());
            requireText("transport.security.private-key-path", security.getPrivateKeyPath());
            requireText("transport.security.trust-certificate-path", security.getTrustCertificatePath());
            if (!security.isHostnameVerification()) {
                throw invalid("transport.security.hostname-verification",
                        "NETWORK_MTLS requires hostname verification");
            }
            if (security.getIdentity().validatedBindings().isEmpty()) {
                throw invalid("transport.security.identity.certificate-bindings",
                        "NETWORK_MTLS requires trusted bindings");
            }
        }
    }

    /** 只约束自动配置实际创建的默认 Fory；用户 Codec 注册表按最终能力单独校验。 */
    static void validateDefaultCodecProfile(OtryxRpcProperties properties) {
        if (properties.getSecurityProfile() == RpcSecurityProfile.NETWORK_MTLS
                && properties.getCodec().getFory().getMode() != ForyRpcSecurityOptions.Mode.STRICT_ALLOWLIST) {
            throw invalid("codec.fory.mode", "NETWORK_MTLS default Fory requires STRICT_ALLOWLIST");
        }
    }

    /**
     * 在解析授权器或创建任何资源之前要求显式安全意图。
     *
     * @param properties 已绑定配置
     */
    static void validateExplicitProfile(OtryxRpcProperties properties) {
        RpcSecurityProfile profile = properties.getSecurityProfile();
        if (profile == null || profile == RpcSecurityProfile.UNSPECIFIED) {
            throw invalid("security-profile",
                    "choose TRUSTED_DEVELOPMENT for isolated networks or NETWORK_MTLS for verified peers");
        }
    }

    private static void requireProtectedRegistry(String key, RegistrySecurityOptions options) {
        if (options.tls().isEmpty() || options.credentials().kind() == CredentialKind.NONE) {
            throw invalid(key, "NETWORK_MTLS requires TLS and explicit credentials");
        }
    }

    private static String knownName(Set<String> known, String value) {
        return known.contains(value) ? value : "custom";
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
