package com.peachsoft.otryx.spring.runtime;

import com.peachsoft.otryx.api.RpcEndpoint;
import com.peachsoft.otryx.codec.RpcCodec;
import com.peachsoft.otryx.codec.RpcCodecIds;
import com.peachsoft.otryx.codec.RpcCodecRegistry;
import com.peachsoft.otryx.codec.fory.ForyRpcCodec;
import com.peachsoft.otryx.codec.fory.ForyRpcSecurityOptions;
import com.peachsoft.otryx.core.ApplicationMembershipCoordinator;
import com.peachsoft.otryx.core.OtryxRpcClient;
import com.peachsoft.otryx.core.RpcDirectoryFreshnessOptions;
import com.peachsoft.otryx.core.OtryxRpcServer;
import com.peachsoft.otryx.core.RpcAuthorizer;
import com.peachsoft.otryx.core.RpcShutdownOptions;
import com.peachsoft.otryx.core.RpcProviderAdmissionOptions;
import com.peachsoft.otryx.core.RpcClientResilienceOptions;
import com.peachsoft.otryx.core.RpcProviderExecutionOptions;
import com.peachsoft.otryx.loadbalance.LoadBalancer;
import com.peachsoft.otryx.observability.RpcMetadataPropagator;
import com.peachsoft.otryx.observability.RpcObserver;
import com.peachsoft.otryx.observability.RpcTracingBridge;
import com.peachsoft.otryx.proxy.ProxyFactory;
import com.peachsoft.otryx.registry.ApplicationRegistry;
import com.peachsoft.otryx.registry.ApplicationServiceResolver;
import com.peachsoft.otryx.spring.autoconfigure.OtryxRpcProperties;
import com.peachsoft.otryx.transport.RpcTransportFactory;
import com.peachsoft.otryx.transport.RpcTransportOptions;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;

/**
 * 按需创建并持有 OTRYX RPC Consumer / Provider 运行时。
 *
 * @Author Ryan
 * @Version 1.0.0
 * @CreateTime 2026/9/28 11:30
 */
public final class OtryxRpcRuntimeCoordinator implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger(OtryxRpcRuntimeCoordinator.class);
    private static final long CLOSE_BUDGET_MILLIS = 30_000L;

    private final ApplicationRegistry registry;
    private final ApplicationMembershipCoordinator membership;
    private final ApplicationServiceResolver resolver;
    private final OtryxRpcApplicationIdentity identity;
    private final RpcCodecRegistry codecRegistry;
    private final RpcTransportFactory transportFactory;
    private final RpcTransportOptions transportOptions;
    private final LoadBalancer loadBalancer;
    private final ProxyFactory proxyFactory;
    private final RpcClientResilienceOptions resilienceOptions;
    private final RpcProviderExecutionOptions executionOptions;
    private final ObjectProvider<RpcObserver> observerProvider;
    private final ObjectProvider<RpcMetadataPropagator>
            metadataPropagatorProvider;
    private final ObjectProvider<RpcTracingBridge>
            tracingBridgeProvider;
    private final ObjectProvider<RpcAuthorizer> authorizerProvider;
    private final OtryxRpcProperties properties;
    private volatile OtryxRpcClient client;
    private volatile OtryxRpcServer server;
    private volatile boolean providerAutoStart;

    /**
     * 创建运行时协调器。
     *
     * @param registry 唯一 Application Member Registry
     * @param membership Provider/Consumer/BOTH 共享成员协调器
     * @param resolver 可信应用级服务解析器
     * @param identity Spring 进程唯一应用身份
     * @param codecRegistry Codec 注册表
     * @param transportFactory Transport 工厂
     * @param transportOptions Transport 配置
     * @param loadBalancer Consumer 负载均衡器
     * @param proxyFactory Consumer 代理工厂
     * @param resilienceOptions Consumer 容错参数
     * @param executionOptions Provider 执行资源参数
     * @param observerProvider 可观测性 Observer 提供器
     * @param metadataPropagatorProvider Metadata 传播器提供器
     * @param tracingBridgeProvider 分布式 Trace Bridge 提供器
     * @param authorizerProvider 非阻塞安全授权策略提供器
     * @param properties OTRYX RPC 配置
     */
    public OtryxRpcRuntimeCoordinator(
            ApplicationRegistry registry,
            ApplicationMembershipCoordinator membership,
            ApplicationServiceResolver resolver,
            OtryxRpcApplicationIdentity identity,
            RpcCodecRegistry codecRegistry,
            RpcTransportFactory transportFactory,
            RpcTransportOptions transportOptions,
            LoadBalancer loadBalancer,
            ProxyFactory proxyFactory,
            RpcClientResilienceOptions resilienceOptions,
            RpcProviderExecutionOptions executionOptions,
            ObjectProvider<RpcObserver> observerProvider,
            ObjectProvider<RpcMetadataPropagator> metadataPropagatorProvider,
            ObjectProvider<RpcTracingBridge> tracingBridgeProvider,
            ObjectProvider<RpcAuthorizer> authorizerProvider,
            OtryxRpcProperties properties) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.membership = Objects.requireNonNull(membership, "membership");
        this.resolver = Objects.requireNonNull(resolver, "resolver");
        this.identity = Objects.requireNonNull(identity, "identity");
        this.codecRegistry = Objects.requireNonNull(codecRegistry, "codecRegistry");
        this.transportFactory = Objects.requireNonNull(transportFactory, "transportFactory");
        this.transportOptions = Objects.requireNonNull(transportOptions, "transportOptions");
        this.loadBalancer = Objects.requireNonNull(loadBalancer, "loadBalancer");
        this.proxyFactory = Objects.requireNonNull(proxyFactory, "proxyFactory");
        this.resilienceOptions = Objects.requireNonNull(
                resilienceOptions,
                "resilienceOptions");
        this.executionOptions = Objects.requireNonNull(
                executionOptions,
                "executionOptions");
        this.observerProvider = Objects.requireNonNull(
                observerProvider,
                "observerProvider");
        this.metadataPropagatorProvider =
                Objects.requireNonNull(
                        metadataPropagatorProvider,
                        "metadataPropagatorProvider");
        this.tracingBridgeProvider =
                Objects.requireNonNull(
                        tracingBridgeProvider,
                        "tracingBridgeProvider");
        this.authorizerProvider = Objects.requireNonNull(
                authorizerProvider, "authorizerProvider");
        this.properties = Objects.requireNonNull(
                properties,
                "properties");
        validateEffectiveNetworkSecurity();
    }

    /**
     * 检查最终注入的安全运行对象，防止自定义 Spring Bean 绕过 Profile。
     */
    private void validateEffectiveNetworkSecurity() {
        if (!properties.isEnabled()
                || properties.getSecurityProfile()
                != OtryxRpcProperties.SecurityProfile.NETWORK_MTLS) {
            return;
        }
        if (!transportOptions.security().mutualTls()) {
            throw new IllegalStateException(
                    "Invalid otryx.rpc.transport.security.mode: "
                            + "effective transport must use MTLS");
        }
        // 未声明统一安全策略的第三方 Codec 不可在网络 Profile 中协商。
        if (!codecRegistry.supportedCodecIds()
                .equals(java.util.Set.of(RpcCodecIds.FORY_NATIVE))
                || !transportOptions.codecIds()
                        .equals(java.util.Set.of(RpcCodecIds.FORY_NATIVE))) {
            throw new IllegalStateException(
                    "Invalid otryx.rpc.codec.fory.mode: "
                            + "NETWORK_MTLS supports only audited Fory Native codec");
        }
        RpcCodec effective;
        try {
            effective = codecRegistry.require(RpcCodecIds.FORY_NATIVE);
        } catch (IllegalArgumentException error) {
            throw new IllegalStateException(
                    "Invalid otryx.rpc.codec.fory.mode: "
                            + "effective registry must contain Fory codec", error);
        }
        if (!(effective instanceof ForyRpcCodec fory)
                || fory.securityOptions().mode()
                != ForyRpcSecurityOptions.Mode.STRICT_ALLOWLIST) {
            throw new IllegalStateException(
                    "Invalid otryx.rpc.codec.fory.mode: "
                            + "effective Fory codec must use STRICT_ALLOWLIST");
        }
    }

    /**
     * 获取或创建 Consumer 运行时。
     *
     * @return Consumer 运行时
     */
    public OtryxRpcClient client() {
        if (!properties.getClient().isEnabled()) {
            throw new IllegalStateException(
                    "OTRYX RPC Consumer capability is disabled by otryx.rpc.client.enabled=false");
        }
        OtryxRpcClient current = client;
        if (current != null) {
            return current;
        }
        synchronized (this) {
            if (client == null) {
                client = createClient();
            }
            return client;
        }
    }

    /**
     * 获取或创建 Provider 运行时。
     *
     * @return Provider 运行时
     */
    public OtryxRpcServer server() {
        if (!properties.getServer().isEnabled()) {
            throw new IllegalStateException(
                    "OTRYX RPC Provider capability is disabled by otryx.rpc.server.enabled=false");
        }
        OtryxRpcServer current = server;
        if (current != null) {
            return current;
        }
        synchronized (this) {
            if (server == null) {
                server = createServer();
            }
            return server;
        }
    }

    /**
     * 为注解服务获取 Provider，并标记为 Spring 生命周期自动启动。
     *
     * @return Provider 运行时
     */
    public OtryxRpcServer serverForServiceExport() {
        providerAutoStart = true;
        return server();
    }

    /**
     * 返回已经创建的 Consumer。
     *
     * @return Consumer
     */
    public Optional<OtryxRpcClient> clientIfCreated() {
        return Optional.ofNullable(client);
    }

    /**
     * 返回已经创建的 Provider。
     *
     * @return Provider
     */
    public Optional<OtryxRpcServer> serverIfCreated() {
        return Optional.ofNullable(server);
    }

    /**
     * 返回需要由 Spring 生命周期自动启动的 Provider。
     *
     * @return 自动启动 Provider
     */
    public Optional<OtryxRpcServer> autoStartServerIfCreated() {
        return providerAutoStart
                ? Optional.ofNullable(server)
                : Optional.empty();
    }

    private OtryxRpcClient createClient() {
        OtryxRpcProperties.Client clientProperties = properties.getClient();
        return OtryxRpcClient.builder()
                .applicationControlPlane(resolver, membership, identity.application().namespace())
                .codecRegistry(codecRegistry)
                .transportClient(transportFactory.createClient(
                        transportOptions.withObserver(observer())))
                .loadBalancer(loadBalancer)
                .proxyFactory(proxyFactory)
                .timeout(clientProperties.getTimeout())
                .methodTimeouts(clientProperties.getMethodTimeouts())
                .directoryFreshnessOptions(new RpcDirectoryFreshnessOptions(
                        clientProperties.getDirectoryMaxStaleness()))
                .resilienceOptions(resilienceOptions)
                .observer(observer())
                .metadataPropagator(metadataPropagator())
                .tracingBridge(tracingBridge())
                .build();
    }

    private OtryxRpcServer createServer() {
        OtryxRpcProperties.Server serverProperties = properties.getServer();
        return OtryxRpcServer.builder()
                .applicationMembership(membership)
                .codecRegistry(codecRegistry)
                .transportServer(transportFactory.createServer(
                        transportOptions.withObserver(observer())))
                .bindEndpoint(new RpcEndpoint(
                        serverProperties.getHost(),
                        serverProperties.getPort()))
                .advertisedHost(serverProperties.getAdvertisedHost())
                .advertisedPort(serverProperties.getAdvertisedPort())
                .maxConcurrent(serverProperties.getMaxConcurrent())
                .admissionOptions(providerAdmissionOptions(
                        serverProperties.getAdmission()))
                .drainTimeout(serverProperties.getDrainTimeout())
                .controlPlaneTimeout(serverProperties.getControlPlaneTimeout())
                .executionOptions(executionOptions)
                .authorizer(authorizer())
                .maxPendingAuthorizations(serverProperties.getAuthorization().getMaxPending())
                .maxPendingAuthorizationBytes(
                        serverProperties.getAuthorization().getMaxBufferedRequestBytes())
                .authorizationTimeout(serverProperties.getAuthorization().getTimeout())
                .observer(observer())
                .metadataPropagator(metadataPropagator())
                .tracingBridge(tracingBridge())
                .build();
    }

    /**
     * Spring 注入的业务授权不能绕过 NETWORK_MTLS 的认证前置条件。
     *
     * @return 网络身份强制校验后组合的非阻塞策略
     */
    private RpcAuthorizer authorizer() {
        if (properties.getSecurityProfile()
                != OtryxRpcProperties.SecurityProfile.NETWORK_MTLS) {
            return authorizerProvider.getIfAvailable(
                    RpcAuthorizer::allowTrustedDevelopment);
        }
        // 仅验证客户端证书的身份不等于得到 ServiceKey/MethodId 授权。
        // 网络 Profile 未声明业务授权 Bean 时拒绝全部请求，不自动信任 CA 角色。
        RpcAuthorizer policy = authorizerProvider.getIfAvailable();
        if (policy == null) {
            return context -> java.util.concurrent.CompletableFuture
                    .completedFuture(false);
        }
        return context -> context.peer().authenticated()
                ? policy.authorize(context)
                : java.util.concurrent.CompletableFuture.completedFuture(false);
    }

    private static RpcProviderAdmissionOptions providerAdmissionOptions(
            OtryxRpcProperties.Admission policy) {
        return new RpcProviderAdmissionOptions(
                policy.getMaxInflightBytes(),
                policy.getMaxConcurrentPerService(),
                policy.getMaxConcurrentPerMethod(),
                policy.getMaxInflightBytesPerService(),
                policy.getMaxInflightBytesPerMethod());
    }

    private RpcObserver observer() {
        return RpcObserver.composite(
                observerProvider.orderedStream().toList());
    }

    private RpcMetadataPropagator metadataPropagator() {
        return RpcMetadataPropagator.composite(
                metadataPropagatorProvider
                        .orderedStream()
                        .toList());
    }

    private RpcTracingBridge tracingBridge() {
        return tracingBridgeProvider
                .orderedStream()
                .findFirst()
                .orElseGet(RpcTracingBridge::noop);
    }

    /**
     * 获取 Spring Provider 的总关闭预算，供 SmartLifecycle 和销毁阶段复用。
     *
     * @return 验证后的关闭预算
     */
    public RpcShutdownOptions serverShutdownOptions() {
        return new RpcShutdownOptions(
                properties.getServer().getShutdownTimeout());
    }

    /**
     * Spring Bean 销毁必须先完成 Provider 排空，再注销共享 Consumer/BOTH 成员。
     *
     * <p>本方法只应在应用生命周期销毁线程运行，不允许在 RPC EventLoop 调用。
     * 阻塞等待受统一有限预算保护；异常以安全摘要记录，禁止泄漏凭据。
     */
    @Override
    public void close() {
        OtryxRpcClient currentClient = client;
        if (currentClient != null) {
            currentClient.close();
        }
        OtryxRpcServer currentServer = server;
        try {
            if (currentServer != null) {
                currentServer.shutdown(serverShutdownOptions()).toCompletableFuture()
                        .get(CLOSE_BUDGET_MILLIS, TimeUnit.MILLISECONDS);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            LOGGER.warn("Spring RPC Provider shutdown interrupted");
        } catch (Exception failure) {
            LOGGER.warn("Spring RPC Provider shutdown incomplete. errorType={}",
                    failure.getClass().getSimpleName());
        } finally {
            try {
                membership.shutdown().toCompletableFuture()
                        .get(CLOSE_BUDGET_MILLIS, TimeUnit.MILLISECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                LOGGER.warn("Spring RPC membership shutdown interrupted");
            } catch (Exception failure) {
                LOGGER.warn("Spring RPC membership shutdown incomplete. errorType={}",
                        failure.getClass().getSimpleName());
            }
        }
    }
}
