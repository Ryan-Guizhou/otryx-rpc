package com.peachsoft.otryx.spring.runtime;

import com.peachsoft.otryx.api.RpcEndpoint;
import com.peachsoft.otryx.api.RpcSecurityProfile;
import com.peachsoft.otryx.api.RpcAuthorizationDecision;
import com.peachsoft.otryx.codec.RpcCodecRegistry;
import com.peachsoft.otryx.core.ApplicationMembershipCoordinator;
import com.peachsoft.otryx.core.OtryxRpcClient;
import com.peachsoft.otryx.core.RpcDirectoryFreshnessOptions;
import com.peachsoft.otryx.core.OtryxRpcServer;
import com.peachsoft.otryx.core.RpcAuthorizer;
import com.peachsoft.otryx.core.RpcShutdownOptions;
import com.peachsoft.otryx.core.RpcClientShutdownReport;
import com.peachsoft.otryx.core.RpcShutdownReport;
import com.peachsoft.otryx.core.RpcProviderAdmissionOptions;
import com.peachsoft.otryx.core.RpcClientResilienceOptions;
import com.peachsoft.otryx.core.RpcProviderExecutionOptions;
import com.peachsoft.otryx.interceptor.RpcInterceptor;
import com.peachsoft.otryx.interceptor.RpcInterceptorRuntime;
import com.peachsoft.otryx.loadbalance.LoadBalancer;
import com.peachsoft.otryx.routing.RpcRouter;
import com.peachsoft.otryx.observability.RpcMetadataPropagator;
import com.peachsoft.otryx.observability.RpcObserver;
import com.peachsoft.otryx.observability.RpcTracingBridge;
import com.peachsoft.otryx.proxy.ProxyFactory;
import com.peachsoft.otryx.registry.ApplicationRegistry;
import com.peachsoft.otryx.registry.ApplicationServiceResolver;
import com.peachsoft.otryx.registry.RegistrySecurityException;
import com.peachsoft.otryx.spring.autoconfigure.OtryxRpcProperties;
import com.peachsoft.otryx.transport.RpcTransportFactory;
import com.peachsoft.otryx.transport.RpcTransportClient;
import com.peachsoft.otryx.transport.RpcTransportServer;
import com.peachsoft.otryx.transport.RpcTransportOptions;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
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
    private static final int MAX_COMPLETION_FAILURE_DEPTH = 32;

    private final ApplicationRegistry registry;
    private final ApplicationMembershipCoordinator membership;
    private final ApplicationServiceResolver resolver;
    private final OtryxRpcApplicationIdentity identity;
    private final RpcCodecRegistry codecRegistry;
    private final RpcTransportFactory transportFactory;
    private final RpcTransportOptions transportOptions;
    private final LoadBalancer loadBalancer;
    private final RpcRouter router;
    private List<RpcInterceptor> interceptors = List.of();
    private int interceptorWorkers = RpcInterceptorRuntime.DEFAULT_WORKERS;
    private int interceptorMaxActive = RpcInterceptorRuntime.DEFAULT_MAX_ACTIVE;
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
    private final RpcSecurityProfile securityProfile;
    private OtryxRpcClient.Builder preparedClientBuilder;
    private OtryxRpcServer.Builder preparedServerBuilder;
    private RpcTransportClient preparedClientTransport;
    private RpcTransportServer preparedServerTransport;
    private volatile OtryxRpcClient client;
    private volatile OtryxRpcServer server;
    private volatile boolean providerAutoStart;
    private boolean closed;
    private boolean startupFailed;
    private final CompletableFuture<Void> closeCompletion = new CompletableFuture<>();
    private Thread closeOwner;
    private long closeDeadlineNanos;

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
     * @param router Consumer 本地 Router SPI
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
            RpcRouter router,
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
        this.router = Objects.requireNonNull(router, "router");
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
        securityProfile = Objects.requireNonNull(properties.getSecurityProfile(), "securityProfile");
        if (securityProfile == RpcSecurityProfile.UNSPECIFIED) {
            throw new IllegalStateException("An explicit otryx.rpc.security-profile is required");
        }
        if (securityProfile == RpcSecurityProfile.NETWORK_MTLS) {
            prepareNetworkBuilders();
        }
    }

    /** 准备实际 Transport 后验证两个最终 Builder，任何一端都不能提前激活成员。 */
    private void prepareNetworkBuilders() {
        RpcAuthorizer policy = properties.getServer().isEnabled() ? authorizer() : null;
        try {
            if (properties.getClient().isEnabled()) {
                preparedClientTransport = transportFactory.createClient(transportOptions.withObserver(observer()));
                preparedClientBuilder = clientBuilder(preparedClientTransport);
            }
            if (properties.getServer().isEnabled()) {
                preparedServerTransport = transportFactory.createServer(transportOptions.withObserver(observer()));
                preparedServerBuilder = serverBuilder(preparedServerTransport, policy);
            }
            validatePreparedBuilders();
        } catch (RuntimeException | Error failure) {
            closePreparedTransports();
            throw failure;
        }
    }

    private void validatePreparedBuilders() {
        if (preparedServerBuilder != null) {
            preparedServerBuilder.validate();
        }
        if (preparedClientBuilder != null) {
            preparedClientBuilder.validate();
        }
    }

    /** 只关闭尚未交给 Core 的准备对象；默认 Adapter 此时没有监听、连接或工作线程。 */
    private void closePreparedTransports() {
        RpcTransportClient pendingClient = preparedClientTransport;
        RpcTransportServer pendingServer = preparedServerTransport;
        preparedClientTransport = null;
        preparedServerTransport = null;
        preparedClientBuilder = null;
        preparedServerBuilder = null;
        closePrepared(pendingClient);
        closePrepared(pendingServer);
    }

    private static void closePrepared(AutoCloseable resource) {
        if (resource == null) {
            return;
        }
        try {
            resource.close();
        } catch (Exception failure) {
            LOGGER.warn("Spring RPC prepared transport cleanup failed. errorType={}",
                    failure.getClass().getSimpleName());
        }
    }

    /**
     * 获取或创建 Consumer 运行时。
     *
     * <p>首次创建在 Spring 初始化或业务控制线程内等待应用成员注册确认，
     * 等待上限由 registry.control-plane-timeout 控制；不得从 EventLoop 调用。
     * 目录发现仍为异步过程，成员入册不意味着已有可调用的 Provider。
     *
     * @return 已确认应用成员注册的 Consumer 运行时
     * @throws IllegalStateException 已关闭、Consumer 被禁用或成员注册失败时抛出
     * @throws RegistrySecurityException 成员注册遭到明确安全拒绝时保留固定原因，不携带外部异常链
     */
    public synchronized OtryxRpcClient client() {
        requireOpen();
        if (!properties.getClient().isEnabled()) {
            throw new IllegalStateException(
                    "OTRYX RPC Consumer capability is disabled by otryx.rpc.client.enabled=false");
        }
        if (client == null) {
            client = createClient();
        }
        return client;
    }

    /**
     * 获取或创建 Provider 运行时。
     *
     * @return Provider 运行时
     * @throws IllegalStateException 已关闭或 Provider 被禁用时抛出
     */
    public synchronized OtryxRpcServer server() {
        requireOpen();
        if (!properties.getServer().isEnabled()) {
            throw new IllegalStateException(
                    "OTRYX RPC Provider capability is disabled by otryx.rpc.server.enabled=false");
        }
        if (server == null) {
            server = createServer();
        }
        return server;
    }

    private void requireOpen() {
        if (closed) {
            throw new IllegalStateException("Spring RPC runtime is closed");
        }
        if (startupFailed) {
            throw new IllegalStateException("Spring RPC runtime startup failed; create a new application context");
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

    /**
     * 由唯一 Starter 在 Bean 返回前一次性提供有序 Hook，支持预构建的 NETWORK_MTLS Builder。
     *
     * <p>任何一侧已创建运行时时不得热替换；动态治理归独立 Issue。
     *
     * @param configured 由 Spring 按 Order 排序的 Hook Bean
     * @param workers 有界 Worker 数
     * @param maxActive 每侧独立逻辑会话配额
     */
    public synchronized void configureInterceptors(List<RpcInterceptor> configured, int workers, int maxActive) {
        if (client != null || server != null || closed) {
            throw new IllegalStateException("RPC interceptor configuration must precede runtime construction");
        }
        if (workers < 1 || workers > RpcInterceptorRuntime.MAX_ALLOWED_WORKERS
                    || maxActive < 1 || maxActive > RpcInterceptorRuntime.MAX_ALLOWED_ACTIVE) {
            throw new IllegalArgumentException("Invalid RPC interceptor worker or session capacity");
        }
        interceptors = List.copyOf(Objects.requireNonNull(configured, "interceptors"));
        interceptorWorkers = workers;
        interceptorMaxActive = maxActive;
        if (preparedClientBuilder != null) {
            preparedClientBuilder.interceptors(interceptors).interceptorExecutionLimits(workers, maxActive);
        }
        if (preparedServerBuilder != null) {
            preparedServerBuilder.interceptors(interceptors).interceptorExecutionLimits(workers, maxActive);
        }
    }

    private OtryxRpcClient.Builder clientBuilder(RpcTransportClient transport) {
        OtryxRpcProperties.Client clientProperties = properties.getClient();
        return OtryxRpcClient.builder()
                .securityProfile(securityProfile)
                .applicationControlPlane(resolver, membership, identity.application().namespace())
                .codecRegistry(codecRegistry)
                .transportClient(transport)
                .loadBalancer(loadBalancer)
                .router(router)
                .interceptors(interceptors)
                .interceptorExecutionLimits(interceptorWorkers, interceptorMaxActive)
                .proxyFactory(proxyFactory)
                .timeout(clientProperties.getTimeout())
                .methodTimeouts(clientProperties.getMethodTimeouts())
                .directoryFreshnessOptions(new RpcDirectoryFreshnessOptions(
                        clientProperties.getDirectoryMaxStaleness()))
                .resilienceOptions(resilienceOptions)
                .observer(observer())
                .metadataPropagator(metadataPropagator())
                .tracingBridge(tracingBridge());
    }

    private OtryxRpcClient createClient() {
        RpcTransportClient transport = preparedClientTransport;
        OtryxRpcClient created;
        try {
            if (preparedClientBuilder != null) {
                validatePreparedBuilders();
                OtryxRpcClient.Builder builder = preparedClientBuilder;
                preparedClientBuilder = null;
                preparedClientTransport = null;
                created = builder.build();
            } else {
                transport = transportFactory.createClient(transportOptions.withObserver(observer()));
                created = clientBuilder(transport).build();
            }
        } catch (RuntimeException | Error failure) {
            failStartup(transport);
            throw failure;
        }
        Duration controlTimeout = properties.getRegistry().getControlPlaneTimeout();
        boolean interrupted = false;
        RegistrySecurityException securityFailure = null;
        try {
            created.membershipReady().toCompletableFuture()
                    .get(controlTimeout.toNanos(), TimeUnit.NANOSECONDS);
            return created;
        } catch (InterruptedException failure) {
            interrupted = true;
        } catch (ExecutionException | TimeoutException failure) {
            securityFailure = membershipSecurityFailure(failure);
            LOGGER.warn("Spring RPC Consumer membership failed. errorType={}",
                    failure.getClass().getSimpleName());
        }
        // 先禁止同步重入或后续懒创建，再清理尚未使用的另一侧准备对象。
        failStartup(null);
        // 尚未发布的 Client 同样拥有 Transport/Executor，不能依赖 Bean 销毁回调回收。
        try {
            created.shutdown(new RpcShutdownOptions(controlTimeout)).toCompletableFuture()
                    .get(controlTimeout.toNanos(), TimeUnit.NANOSECONDS);
        } catch (InterruptedException failure) {
            interrupted = true;
        } catch (ExecutionException | TimeoutException failure) {
            LOGGER.warn("Spring RPC Consumer startup rollback incomplete. errorType={}",
                    failure.getClass().getSimpleName());
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
        if (securityFailure != null) {
            throw securityFailure;
        }
        throw new IllegalStateException("RPC Consumer application membership was not acknowledged within "
                + "otryx.rpc.registry.control-plane-timeout; verify registry access and application identity");
    }

    /** 只解包标准异步包装；保留稳定分类，不复制 SDK、业务异常或包装层的敏感信息。 */
    private static RegistrySecurityException membershipSecurityFailure(Throwable failure) {
        Throwable current = failure;
        for (int depth = 0; depth < MAX_COMPLETION_FAILURE_DEPTH
                && (current instanceof CompletionException || current instanceof ExecutionException)
                && current.getCause() != null && current.getCause() != current; depth++) {
            current = current.getCause();
        }
        return current instanceof RegistrySecurityException rejected
                ? new RegistrySecurityException(rejected.reason()) : null;
    }

    private OtryxRpcServer.Builder serverBuilder(RpcTransportServer transport, RpcAuthorizer policy) {
        OtryxRpcProperties.Server serverProperties = properties.getServer();
        return OtryxRpcServer.builder()
                .securityProfile(securityProfile)
                .applicationMembership(membership)
                .codecRegistry(codecRegistry)
                .transportServer(transport)
                .bindEndpoint(new RpcEndpoint(
                        serverProperties.getHost(),
                        serverProperties.getPort()))
                .advertisedHost(serverProperties.getAdvertisedHost())
                .advertisedPort(serverProperties.getAdvertisedPort())
                .maxConcurrent(serverProperties.getMaxConcurrent())
                .admissionOptions(providerAdmissionOptions(
                        serverProperties.getAdmission()))
                .responseEncodingLimits(
                        serverProperties.getEncoding().getMaxBufferedBytes(),
                        serverProperties.getEncoding().getMaxResponseBytes())
                .drainTimeout(serverProperties.getDrainTimeout())
                .controlPlaneTimeout(serverProperties.getControlPlaneTimeout())
                .executionOptions(executionOptions)
                .interceptors(interceptors)
                .interceptorExecutionLimits(interceptorWorkers, interceptorMaxActive)
                .authorizer(policy)
                .maxAuthorizationGrantLifetime(serverProperties.getAuthorization().getMaxGrantLifetime())
                .maxPendingAuthorizations(serverProperties.getAuthorization().getMaxPending())
                .maxPendingAuthorizationBytes(
                        serverProperties.getAuthorization().getMaxBufferedRequestBytes())
                .authorizationTimeout(serverProperties.getAuthorization().getTimeout())
                .observer(observer())
                .metadataPropagator(metadataPropagator())
                .tracingBridge(tracingBridge());
    }

    private OtryxRpcServer createServer() {
        RpcTransportServer transport = preparedServerTransport;
        try {
            if (preparedServerBuilder != null) {
                validatePreparedBuilders();
                OtryxRpcServer.Builder builder = preparedServerBuilder;
                preparedServerBuilder = null;
                preparedServerTransport = null;
                return builder.build();
            }
            transport = transportFactory.createServer(transportOptions.withObserver(observer()));
            return serverBuilder(transport, authorizer()).build();
        } catch (RuntimeException | Error failure) {
            failStartup(transport);
            throw failure;
        }
    }

    /**
     * 在协调器监视器内终结本次启动，关闭尚未交付给 Core 的所有权。
     *
     * <p>失败的 Builder 不可重建已关闭 Transport，也不可隐式重新注册成员；
     * 已创建的其他运行时仍由正常 close 路径按既有总预算清理。
     *
     * @param attempted 当前构建失败、尚未交给 Core 的 Transport；成员回滚时为 null
     */
    private void failStartup(AutoCloseable attempted) {
        startupFailed = true;
        if (attempted == preparedClientTransport) {
            preparedClientTransport = null;
        }
        if (attempted == preparedServerTransport) {
            preparedServerTransport = null;
        }
        closePrepared(attempted);
        closePreparedTransports();
    }

    /** 实际策略保持有限许可；NETWORK 缺失策略不能转换为可启动的拒绝替身。 */
    private RpcAuthorizer authorizer() {
        RpcAuthorizer policy = authorizerProvider.getIfAvailable();
        if (policy != null) {
            return policy;
        }
        if (securityProfile == RpcSecurityProfile.NETWORK_MTLS) {
            throw new IllegalStateException("NETWORK_MTLS requires an explicit RpcAuthorizer bean");
        }
        return context -> CompletableFuture.completedFuture(
                RpcAuthorizationDecision.allowUntil(context.maximumValidUntil()));
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
     * Spring Bean 销毁等待 Client 资源释放与 Provider 排空，再注销共享成员。
     *
     * <p>本方法只应在应用生命周期销毁线程运行，不允许在 RPC EventLoop 调用。
     * 与运行时懒创建互斥，关闭后不再创建 Client 或 Server。各清理阶段共用
     * Provider 配置的总关闭预算；未创建 Provider 时使用 Core 默认预算。
     * 超时后仍发起剩余清理，异常以安全摘要记录，不宣称外部资源已全部释放。
     *
     * <p>并发与重复关闭共享第一次关闭的完成信号和绝对截止时间，不能仅因
     * 已停止创建运行时便提前返回。发起关闭的线程同步重入时不等待自己，
     * 由最外层调用负责清理和完成通知。等待中断会保留中断标记并报告未完成。
     */
    @Override
    public void close() {
        RpcShutdownOptions options = null;
        long deadlineNanos;
        synchronized (this) {
            if (closed) {
                if (closeOwner == Thread.currentThread()) {
                    return;
                }
            } else {
                options = server == null ? RpcShutdownOptions.DEFAULT : serverShutdownOptions();
                closeDeadlineNanos = System.nanoTime() + options.totalTimeout().toNanos();
                closeOwner = Thread.currentThread();
                closed = true;
            }
            deadlineNanos = closeDeadlineNanos;
        }
        if (options == null) {
            awaitConcurrentClose(deadlineNanos);
            return;
        }
        try {
            // closed 与运行时发布共用监视器；从此两端引用不再变化。
            closeCreatedRuntimes(client, server, options, deadlineNanos);
        } finally {
            closePreparedTransports();
            synchronized (this) {
                closeOwner = null;
            }
            closeCompletion.complete(null);
        }
    }

    private void closeCreatedRuntimes(
            OtryxRpcClient currentClient, OtryxRpcServer currentServer,
            RpcShutdownOptions options, long deadlineNanos) {
        CompletionStage<RpcClientShutdownReport> clientShutdown = currentClient == null
                ? null : beginShutdown(() -> currentClient.shutdown(options));
        // 两端先停收，不能因等待 Consumer 的 Transport.close() 而延后 Provider 排空。
        CompletionStage<RpcShutdownReport> serverShutdown = currentServer == null
                ? null : beginShutdown(() -> currentServer.shutdown(options));
        try {
            if (clientShutdown != null) {
                RpcClientShutdownReport report = awaitClose(clientShutdown, deadlineNanos);
                if (report.deadlineExceeded() || report.cleanupFailures() > 0) {
                    LOGGER.warn("Spring RPC Consumer shutdown incomplete. timedOut={}, failures={}",
                            report.deadlineExceeded(), report.cleanupFailures());
                }
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            LOGGER.warn("Spring RPC Consumer shutdown interrupted");
        } catch (Exception failure) {
            LOGGER.warn("Spring RPC Consumer shutdown incomplete. errorType={}",
                    failure.getClass().getSimpleName());
        }
        try {
            if (serverShutdown != null) {
                RpcShutdownReport report = awaitClose(serverShutdown, deadlineNanos);
                if (report.deadlineExceeded() || report.cleanupFailures() > 0) {
                    LOGGER.warn("Spring RPC Provider shutdown incomplete. timedOut={}, failures={}",
                            report.deadlineExceeded(), report.cleanupFailures());
                }
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            LOGGER.warn("Spring RPC Provider shutdown interrupted");
        } catch (Exception failure) {
            LOGGER.warn("Spring RPC Provider shutdown incomplete. errorType={}",
                    failure.getClass().getSimpleName());
        } finally {
            try {
                awaitClose(beginShutdown(membership::shutdown), deadlineNanos);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                LOGGER.warn("Spring RPC membership shutdown interrupted");
            } catch (Exception failure) {
                LOGGER.warn("Spring RPC membership shutdown incomplete. errorType={}",
                        failure.getClass().getSimpleName());
            }
        }
    }

    private void awaitConcurrentClose(long deadlineNanos) {
        try {
            awaitClose(closeCompletion, deadlineNanos);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            LOGGER.warn("Spring RPC concurrent shutdown wait interrupted");
        } catch (ExecutionException | TimeoutException failure) {
            LOGGER.warn("Spring RPC concurrent shutdown wait incomplete. errorType={}",
                    failure.getClass().getSimpleName());
        }
    }

    /** 将同步启动异常转为失败 Stage，仍继续尝试其他运行时及共享成员的清理。 */
    private static <T> CompletionStage<T> beginShutdown(Supplier<CompletionStage<T>> action) {
        try {
            return Objects.requireNonNull(action.get(), "shutdown stage");
        } catch (RuntimeException failure) {
            return CompletableFuture.failedFuture(failure);
        }
    }

    private static <T> T awaitClose(CompletionStage<T> stage, long deadlineNanos)
            throws InterruptedException, ExecutionException, TimeoutException {
        return stage.toCompletableFuture().get(
                Math.max(1L, deadlineNanos - System.nanoTime()), TimeUnit.NANOSECONDS);
    }
}
