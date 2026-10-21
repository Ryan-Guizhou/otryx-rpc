package com.peachsoft.otryx.core;

import com.peachsoft.otryx.api.OtryxRpcIdempotent;
import com.peachsoft.otryx.api.RpcEndpoint;
import com.peachsoft.otryx.api.RpcTarget;
import com.peachsoft.otryx.api.RpcAuthenticationException;
import com.peachsoft.otryx.api.RpcSecurityProfile;
import com.peachsoft.otryx.api.RpcSchemaFingerprint;
import com.peachsoft.otryx.api.RpcException;
import com.peachsoft.otryx.api.RpcContractMethods;
import com.peachsoft.otryx.api.RpcMethodDescriptor;
import com.peachsoft.otryx.api.RpcOverloadedException;
import com.peachsoft.otryx.api.RpcRemoteException;
import com.peachsoft.otryx.api.RpcStatus;
import com.peachsoft.otryx.api.RpcTimeoutException;
import com.peachsoft.otryx.api.RpcUnavailableException;
import com.peachsoft.otryx.api.ServiceInstance;
import com.peachsoft.otryx.api.ServiceKey;
import com.peachsoft.otryx.codec.RpcCodecRegistry;
import com.peachsoft.otryx.codec.RpcMethodCodec;
import com.peachsoft.otryx.generated.RpcGeneratedClients;
import com.peachsoft.otryx.interceptor.RpcInterceptor;
import com.peachsoft.otryx.interceptor.RpcInterceptorRuntime;
import com.peachsoft.otryx.interceptor.RpcInvocation;
import com.peachsoft.otryx.generated.RpcGeneratedInvocation;
import com.peachsoft.otryx.loadbalance.LoadBalanceMetrics;
import com.peachsoft.otryx.loadbalance.LoadBalancer;
import com.peachsoft.otryx.observability.RpcDirectoryFailureReason;
import com.peachsoft.otryx.observability.RpcMetadataPropagator;
import com.peachsoft.otryx.observability.RpcObserver;
import com.peachsoft.otryx.observability.RpcRetryExhaustionReason;
import com.peachsoft.otryx.observability.RpcTraceContext;
import com.peachsoft.otryx.observability.RpcTracingBridge;
import com.peachsoft.otryx.protocol.RpcErrorCodec;
import com.peachsoft.otryx.protocol.RpcFrameView;
import com.peachsoft.otryx.protocol.RpcProtocolCodec;
import com.peachsoft.otryx.proxy.ProxyFactory;
import com.peachsoft.otryx.routing.RpcRouter;
import com.peachsoft.otryx.registry.ApplicationServiceResolver;
import com.peachsoft.otryx.registry.RegistrySecurityException;
import com.peachsoft.otryx.registry.ServiceMappingKey;
import com.peachsoft.otryx.spi.ExtensionLoader;
import com.peachsoft.otryx.transport.RpcTransportClient;
import com.peachsoft.otryx.transport.RpcTransportResponse;
import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * OTRYX RPC Consumer 运行时，负责从本地服务目录选取端点并执行请求。
 *
 * <p>重试、Circuit Breaker、Deadline 和完成回调的资源由该运行时管理。
 * 响应解码和用户完成回调不得阻塞 Vert.x EventLoop；异步完成通过
 * 有界执行器隔离，拒绝时以明确失败通知调用方。
 *
 * <p>实例由 Builder 构建，使用者应在不再发起调用时调用 {@link #close()}，
 * 避免遗留连接、服务发现订阅和执行资源。
 *
 * @Author Ryan
 * @Version 1.0.0
 * @CreateTime 2026/9/23 10:51
 */
public final class OtryxRpcClient implements AutoCloseable {
    private static final Logger LOGGER =
            LoggerFactory.getLogger(OtryxRpcClient.class);
    private static final int MAX_LOCAL_DIRECTORY_SELECTIONS = 2;
    private final RpcSecurityProfile securityProfile;
    private final Clock identityClock;
    private final LongSupplier identityNanoClock;
    private final ApplicationServiceResolver applicationResolver;
    private final String applicationNamespace;
    private final ApplicationMembershipCoordinator membership;
    private final CompletableFuture<Void> membershipReady;
    private final RpcTransportClient transport;
    private final RpcCodecRegistry codecs;
    private final byte defaultCodecId;
    private final LoadBalancer loadBalancer;
    private final RpcRouter router;
    private final RpcGovernanceRuntime governance;
    private final RpcInterceptorRuntime interceptors;
    private final ProxyFactory proxyFactory;
    private final Duration timeout;
    private final Map<String, Duration> methodTimeouts;
    private final RpcDirectoryFreshnessOptions directoryFreshnessOptions;
    private final RpcClientResilienceOptions resilienceOptions;
    private final RetryBudget retryBudget;
    private final ClientRetryController retryController;
    private final ThreadPoolExecutor responseCompletionExecutor;
    private final Semaphore responseCompletionPermits;
    private final ScheduledThreadPoolExecutor deadlineScheduler;
    private final RpcObserver observer;
    private final RpcMetadataPropagator metadataPropagator;
    private final RpcTracingBridge tracingBridge;
    private final ConcurrentMap<ServiceKey, ServiceDirectory> directories =
            new ConcurrentHashMap<>();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicReference<CompletableFuture<RpcClientShutdownReport>>
            shutdownResult = new AtomicReference<>();
    private final Set<CompletableFuture<Object>> pendingCalls =
            ConcurrentHashMap.newKeySet();
    private final ConcurrentMap<RpcTarget, EndpointStats> stats =
            new ConcurrentHashMap<>();
    private final LoadBalanceMetrics loadMetrics =
            new LoadBalanceMetrics() {
                @Override
                public long ewmaLatencyNanos(
                        ServiceInstance instance) {
                    return endpointStats(instance).ewma();
                }

                @Override
                public int inflight(
                        ServiceInstance instance) {
                    return endpointStats(instance).inflight();
                }

                @Override
                public boolean available(
                        ServiceInstance instance) {
                    return endpointStats(instance).available();
                }
            };

    private OtryxRpcClient(Builder builder) {
        this.securityProfile = builder.securityProfile;
        this.identityClock = builder.identityClock;
        this.identityNanoClock = builder.identityNanoClock;
        this.applicationResolver = Objects.requireNonNull(builder.applicationResolver, "applicationResolver");
        this.applicationNamespace = Objects.requireNonNull(builder.applicationNamespace, "applicationNamespace");
        this.membership = Objects.requireNonNull(builder.membership, "applicationMembership");
        this.transport = Objects.requireNonNull(builder.transport, "transportClient");
        this.codecs = Objects.requireNonNull(builder.codecs, "codecRegistry");
        this.defaultCodecId = codecs.defaultCodec().code();
        this.loadBalancer = builder.loadBalancer != null
                ? builder.loadBalancer
                : ExtensionLoader.getLoader(LoadBalancer.class).getDefaultExtension();
        this.router = builder.router != null
                ? builder.router
                : ExtensionLoader.getLoader(RpcRouter.class).getDefaultExtension();
        this.interceptors = builder.interceptors.isEmpty() ? null : new RpcInterceptorRuntime(
                builder.interceptors, builder.interceptorWorkers,
                builder.interceptorMaxActive, "consumer");
        this.governance = builder.governance;
        this.proxyFactory = builder.proxyFactory != null
                ? builder.proxyFactory
                : ExtensionLoader.getLoader(ProxyFactory.class).getDefaultExtension();
        this.timeout = Objects.requireNonNull(builder.timeout, "timeout");
        this.methodTimeouts = Map.copyOf(builder.methodTimeouts);
        this.directoryFreshnessOptions = Objects.requireNonNull(
                builder.directoryFreshnessOptions, "directoryFreshnessOptions");
        this.resilienceOptions = Objects.requireNonNull(
                builder.resilienceOptions,
                "resilienceOptions");
        this.retryBudget = new RetryBudget(resilienceOptions);
        this.retryController = new ClientRetryController(resilienceOptions, retryBudget);
        this.responseCompletionPermits = new Semaphore(
                builder.responseCompletionThreads
                        + builder.responseCompletionQueueCapacity);
        this.responseCompletionExecutor = new ThreadPoolExecutor(
                builder.responseCompletionThreads,
                builder.responseCompletionThreads,
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(
                        builder.responseCompletionQueueCapacity),
                Thread.ofPlatform()
                        .name("otryx-client-completion-", 0)
                        .factory(),
                new ThreadPoolExecutor.AbortPolicy());
        this.deadlineScheduler = new ScheduledThreadPoolExecutor(
                2,
                Thread.ofPlatform()
                        .daemon(true)
                        .name("otryx-client-deadline-", 0)
                        .factory());
        this.deadlineScheduler.setRemoveOnCancelPolicy(true);
        this.deadlineScheduler.scheduleWithFixedDelay(
                this::evictUnusedEndpointStats,
                1L,
                1L,
                TimeUnit.MINUTES);
        // 自定义 Observer 即使只有一个，也不能通过异常中断 RPC 完成链。
        // NOOP 配置仍走不产生事件对象的原有快路径。
        this.observer = RpcObserver.composite(
                List.of(Objects.requireNonNull(
                        builder.observer,
                        "observer")));
        this.metadataPropagator = Objects.requireNonNull(
                builder.metadataPropagator,
                "metadataPropagator");
        this.tracingBridge = Objects.requireNonNull(
                builder.tracingBridge,
                "tracingBridge");
        this.membershipReady = membership.startConsumer().toCompletableFuture();
    }

    /**
     * 创建 Consumer Builder。
     *
     * @return Consumer Builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * 创建指定服务的 Consumer 引用。
     *
     * <p>服务标识、方法标识、方法 Codec 与本地目录均在此阶段预解析。
     * 若编译期 Generated Stub 存在则优先使用，否则回退到配置的 ProxyFactory。
     *
     * @param <T> 服务接口类型
     * @param api 服务接口
     * @param version 服务版本
     * @param group 服务分组
     * @return RPC 服务代理
     */
    public <T> T refer(Class<T> api, String version, String group) {
        Objects.requireNonNull(api, "api");
        if (closed.get()) {
            throw new RpcUnavailableException("RPC client is closed");
        }
        ServiceKey key = new ServiceKey(api.getName(), version, group);
        String schemaFingerprint =
                RpcSchemaFingerprint.serviceFingerprint(
                        key,
                        api);
        ServiceDirectory directory = directories.computeIfAbsent(key, ignored -> new ServiceDirectory(
                applicationResolver,
                new ServiceMappingKey(applicationNamespace, key),
                schemaFingerprint,
                directoryFreshnessOptions,
                deadlineScheduler));
        ClientReference reference = ClientReference.create(
                key,
                api,
                directory,
                codecs,
                defaultCodecId,
                resilienceOptions,
                securityProfile);

        return RpcGeneratedClients.find(api)
                .map(factory -> factory.create(
                        new GeneratedInvocation(reference)))
                .orElseGet(() -> proxyFactory.create(
                        api,
                        (method, arguments) -> invokeN(
                                reference,
                                reference.require(method),
                                arguments)));
    }

    /**
     * 查询 Consumer 应用级成员入册是否成功，不在调用热路径等待 Registry。
     *
     * @return 可用于进程就绪检查的异步完成信号
     */
    public CompletionStage<Void> membershipReady() {
        return membershipReady;
    }

    /**
     * 无远程 IO 查询已有 RPC 引用的可信本地目录与当前安全状态。
     *
     * <p>不会创建服务引用、注册成员、主动刷新 Metadata 或重新执行 Router。
     * 原先真实业务选路失败仅在同一可信快照上记录，不携带 InstanceId/参数/地址。
     *
     * @param key 业务 ServiceKey；未引用的服务将返回 NOT_REFERENCED
     * @return 不可变的有限诊断摘要
     */
    public RpcServiceDiagnosticSnapshot diagnose(ServiceKey key) {
        Objects.requireNonNull(key, "key");
        if (closed.get()) {
            return diagnostic(key, RpcServiceDirectoryState.CLOSED,
                    RpcDirectoryFailureReason.DIRECTORY_CLOSED, 0, 0, 0L);
        }
        ServiceDirectory directory = directories.get(key);
        if (directory == null) {
            return diagnostic(key, RpcServiceDirectoryState.NOT_REFERENCED,
                    RpcDirectoryFailureReason.NONE, 0, 0, 0L);
        }
        if (!membershipReady.isDone() || membershipReady.isCompletedExceptionally()
                || membershipReady.isCancelled()) {
            return diagnostic(key, RpcServiceDirectoryState.MEMBERSHIP_NOT_READY,
                    RpcDirectoryFailureReason.MEMBERSHIP_NOT_READY, 0, 0, 0L);
        }
        try {
            ServiceDirectory.Selection selected = directory.selection();
            RpcServiceDirectoryState state = switch (selected.status()) {
                case NOT_READY -> RpcServiceDirectoryState.NOT_READY;
                case HEALTHY -> RpcServiceDirectoryState.HEALTHY;
                case LAST_KNOWN_GOOD -> RpcServiceDirectoryState.LAST_KNOWN_GOOD;
                case STALE -> RpcServiceDirectoryState.STALE;
                case CLOSED -> RpcServiceDirectoryState.CLOSED;
            };
            if (state == RpcServiceDirectoryState.CLOSED) {
                return diagnostic(key, state, RpcDirectoryFailureReason.DIRECTORY_CLOSED, 0, 0, 0L);
            }
            if (state == RpcServiceDirectoryState.NOT_READY) {
                return diagnostic(key, state, RpcDirectoryFailureReason.DIRECTORY_NOT_READY, 0, 0, 0L);
            }
            if (state == RpcServiceDirectoryState.STALE) {
                return diagnostic(key, state, RpcDirectoryFailureReason.DIRECTORY_STALE, 0, 0, 0L);
            }
            ServiceInstance[] verified = selected.instances();
            long revision = selected.candidate() == null ? 0L : selected.candidate().mappingRevision();
            if (verified.length == 0) {
                return diagnostic(key, state, RpcDirectoryFailureReason.VERIFIED_EMPTY, 0, 0, revision);
            }
            int available = 0;
            for (ServiceInstance instance : verified) {
                EndpointStats health = stats.get(instance.target());
                if (health == null || health.available()) {
                    available++;
                }
            }
            RpcDirectoryFailureReason previous = directory.lastRoutingFailure(selected);
            RpcDirectoryFailureReason reason = available == 0
                    ? RpcDirectoryFailureReason.NO_HEALTHY_ENDPOINT : previous;
            return diagnostic(key, state, reason, verified.length, available, revision);
        } catch (RegistrySecurityException rejected) {
            return diagnostic(key, RpcServiceDirectoryState.SECURITY_REJECTED,
                    RpcDirectoryFailureReason.SECURITY_REJECTED, 0, 0, 0L);
        }
    }

    private static RpcServiceDiagnosticSnapshot diagnostic(
            ServiceKey key, RpcServiceDirectoryState state,
            RpcDirectoryFailureReason reason, int verified, int available, long revision) {
        return new RpcServiceDiagnosticSnapshot(key, state, reason, verified, available, revision);
    }

    /**
     * 仅真实业务失败写入目录原因；首个逻辑 Attempt 最多一次指标/JFR 事件。
     *
     * @param reference 当前调用引用
     * @param reason 固定失败原因
     * @param selected 当前调用的已验证快照，允许为 null
     * @param attempt 网络尝试序号
     * @param localSelections 本地重选序号
     */
    private void recordDirectoryFailure(ClientReference reference, RpcDirectoryFailureReason reason,
            ServiceDirectory.Selection selected, int attempt, int localSelections) {
        reference.directory().recordRoutingFailure(reason, selected);
        if (observer.enabled() && attempt == 1 && localSelections == 0) {
            observer.onClientDirectoryFailure(reason);
        }
    }

    private CompletionStage<Object> invoke0(
            ClientReference reference,
            ClientMethodBinding method) {
        long startedAtNanos = System.nanoTime();
        if (closed.get()) {
            return clientClosed();
        }
        if (!responseCompletionPermits.tryAcquire()) {
            return capacityExceeded();
        }
        byte[] encodedArguments;
        try {
            encodedArguments = method.codec().encode0();
        } catch (RuntimeException | Error encodingError) {
            responseCompletionPermits.release();
            throw encodingError;
        }
        return invokeEncoded(
                reference, method, encodedArguments, startedAtNanos);
    }

    private CompletionStage<Object> invoke1(
            ClientReference reference,
            ClientMethodBinding method,
            Object argument0) {
        long startedAtNanos = System.nanoTime();
        if (closed.get()) {
            return clientClosed();
        }
        if (!responseCompletionPermits.tryAcquire()) {
            return capacityExceeded();
        }
        byte[] encodedArguments;
        try {
            encodedArguments = method.codec().encode1(argument0);
        } catch (RuntimeException | Error encodingError) {
            responseCompletionPermits.release();
            throw encodingError;
        }
        return invokeEncoded(
                reference, method, encodedArguments, startedAtNanos);
    }

    private CompletionStage<Object> invoke2(
            ClientReference reference,
            ClientMethodBinding method,
            Object argument0,
            Object argument1) {
        long startedAtNanos = System.nanoTime();
        if (closed.get()) {
            return clientClosed();
        }
        if (!responseCompletionPermits.tryAcquire()) {
            return capacityExceeded();
        }
        byte[] encodedArguments;
        try {
            encodedArguments = method.codec().encode2(argument0, argument1);
        } catch (RuntimeException | Error encodingError) {
            responseCompletionPermits.release();
            throw encodingError;
        }
        return invokeEncoded(
                reference, method, encodedArguments, startedAtNanos);
    }

    private CompletionStage<Object> invoke3(
            ClientReference reference,
            ClientMethodBinding method,
            Object argument0,
            Object argument1,
            Object argument2) {
        long startedAtNanos = System.nanoTime();
        if (closed.get()) {
            return clientClosed();
        }
        if (!responseCompletionPermits.tryAcquire()) {
            return capacityExceeded();
        }
        byte[] encodedArguments;
        try {
            encodedArguments = method.codec().encode3(
                        argument0,
                        argument1,
                        argument2);
        } catch (RuntimeException | Error encodingError) {
            responseCompletionPermits.release();
            throw encodingError;
        }
        return invokeEncoded(
                reference, method, encodedArguments, startedAtNanos);
    }

    private CompletionStage<Object> invoke4(
            ClientReference reference,
            ClientMethodBinding method,
            Object argument0,
            Object argument1,
            Object argument2,
            Object argument3) {
        long startedAtNanos = System.nanoTime();
        if (closed.get()) {
            return clientClosed();
        }
        if (!responseCompletionPermits.tryAcquire()) {
            return capacityExceeded();
        }
        byte[] encodedArguments;
        try {
            encodedArguments = method.codec().encode4(
                        argument0,
                        argument1,
                        argument2,
                        argument3);
        } catch (RuntimeException | Error encodingError) {
            responseCompletionPermits.release();
            throw encodingError;
        }
        return invokeEncoded(
                reference, method, encodedArguments, startedAtNanos);
    }

    private CompletionStage<Object> invokeN(
            ClientReference reference,
            ClientMethodBinding method,
            Object[] arguments) {
        long startedAtNanos = System.nanoTime();
        if (closed.get()) {
            return clientClosed();
        }
        if (!responseCompletionPermits.tryAcquire()) {
            return capacityExceeded();
        }
        byte[] encodedArguments;
        try {
            encodedArguments = method.codec().encodeArguments(arguments);
        } catch (RuntimeException | Error encodingError) {
            responseCompletionPermits.release();
            throw encodingError;
        }
        return invokeEncoded(
                reference, method, encodedArguments, startedAtNanos);
    }

    private static CompletionStage<Object> clientClosed() {
        return CompletableFuture.failedFuture(
                new RpcUnavailableException("RPC client is closed"));
    }

    private static CompletionStage<Object> capacityExceeded() {
        return CompletableFuture.failedFuture(
                new RpcOverloadedException(
                        "RPC consumer response completion capacity exceeded"));
    }

    private CompletionStage<Object> invokeEncoded(
            ClientReference reference,
            ClientMethodBinding method,
            byte[] encodedArguments,
            long callStartedAtNanos) {
        RpcGovernanceRuntime.Selection selectedGovernance;
        RpcGovernanceRuntime.QuotaLease governanceLease;
        try {
            selectedGovernance = governance == null ? null : governance.capture(reference.key(), method.methodId());
            governanceLease = governance == null ? null : governance.acquireQuota(selectedGovernance,
                    RpcGovernanceRuntime.QuotaSide.CONSUMER, reference.key(), method.methodId());
        } catch (RuntimeException rejected) {
            responseCompletionPermits.release();
            return CompletableFuture.failedFuture(rejected);
        }
        CompletableFuture<Object> result = interceptors == null && selectedGovernance == null
                ? new CompletableFuture<>() : new InterceptedCall();
        if (result instanceof InterceptedCall tracked) {
            tracked.governance = selectedGovernance;
        }
        RpcCircuitBreaker governedCircuit = selectedGovernance == null ? null : selectedGovernance.circuit();
        long[] governedGeneration = {RpcCircuitBreaker.REJECTED};
        long[] circuitGeneration = {RpcCircuitBreaker.REJECTED};
        result.whenComplete((ignoredValue, error) -> {
            pendingCalls.remove(result);
            responseCompletionPermits.release();
            if (governanceLease != null) {
                governanceLease.release();
            }
        });
        pendingCalls.add(result);
        // 与 close() 的关闭标记和快照遍历协同；编码与关闭并发时也必须结算 Future。
        if (closed.get()) {
            result.completeExceptionally(
                    new RpcUnavailableException("RPC client is closed"));
            return result;
        }
        try {
            retryBudget.onRequest();
            RpcTraceContext trace = tracingBridge.enabled()
                    ? tracingBridge.startClient(
                            reference.key(),
                            method.methodId())
                    : RpcTraceContext.noop();
            long logicalStartedAtNanos =
                    observer.enabled()
                            ? System.nanoTime()
                            : 0L;
            if (observer.enabled()) {
                observer.onClientInflightChanged(1);
                observeCircuitState(reference, method);
            }
            result.whenComplete((ignoredValue, error) -> {
                Throwable failure;
                RpcStatus finalStatus;
                if (result.isCancelled()) {
                    method.circuitBreaker().onCancelled(circuitGeneration[0]);
                    if (governedCircuit != null) {
                        governedCircuit.onCancelled(governedGeneration[0]);
                    }
                    failure = new CancellationException(
                            "RPC call cancelled");
                    finalStatus = RpcStatus.UNAVAILABLE;
                } else {
                    failure = error == null ? null : unwrap(error);
                    finalStatus = failure == null
                            ? RpcStatus.OK
                            : statusOf(failure);
                    if (securityFailure(failure)) {
                        method.circuitBreaker().onCancelled(circuitGeneration[0]);
                        if (governedCircuit != null) {
                            governedCircuit.onCancelled(governedGeneration[0]);
                        }
                    } else if (failure == null
                            || (failure instanceof RpcRemoteException
                                    && !ClientRetryController.isRetryable(failure))) {
                        method.circuitBreaker().onSuccess(circuitGeneration[0]);
                        if (governedCircuit != null) {
                            governedCircuit.onSuccess(governedGeneration[0]);
                        }
                    } else {
                        method.circuitBreaker().onFailure(circuitGeneration[0]);
                        if (governedCircuit != null) {
                            governedCircuit.onFailure(governedGeneration[0]);
                        }
                    }
                }
                observeCircuitState(reference, method);
                if (observer.enabled()) {
                    observer.onClientCallCompleted(
                            reference.key(),
                            method.methodId(),
                            System.nanoTime()
                                    - logicalStartedAtNanos,
                            finalStatus,
                            failure);
                    observer.onClientInflightChanged(-1);
                }
                trace.end(
                        finalStatus,
                        failure);
            });
            RpcCallContext currentCall = RpcCallContext.current();
            Map<String, String> propagatedMetadata =
                    propagatedMetadata(trace, currentCall);

            circuitGeneration[0] =
                    method.circuitBreaker().tryAcquire();
            observeCircuitState(reference, method);
            if (circuitGeneration[0] == RpcCircuitBreaker.REJECTED) {
                if (observer.enabled()) {
                    observer.onClientCircuitRejected(
                            reference.key(),
                            method.methodId());
                }
                result.completeExceptionally(
                        new RpcUnavailableException(
                                "RPC circuit is open for "
                                        + reference.key().canonicalName()
                                        + '#'
                                        + method.methodId()));
                return result;
            }

            if (governedCircuit != null) {
                governedGeneration[0] = governedCircuit.tryAcquire();
                if (governedGeneration[0] == RpcCircuitBreaker.REJECTED) {
                    method.circuitBreaker().onCancelled(circuitGeneration[0]);
                    circuitGeneration[0] = RpcCircuitBreaker.REJECTED;
                    result.completeExceptionally(new RpcUnavailableException(
                            "RPC governance circuit is open"));
                    return result;
                }
            }

            RpcCallContext parent = currentCall;
            Duration selectedTimeout = parent != null && parent.options().timeout() != null
                    ? parent.options().timeout()
                    : methodTimeouts.getOrDefault(method.methodKey(), timeout);
            if (selectedGovernance != null && selectedGovernance.method() != null
                    && selectedGovernance.method().maxTimeout() != null) {
                Duration policyLimit = selectedGovernance.method().maxTimeout();
                if (policyLimit.compareTo(selectedTimeout) < 0) {
                    selectedTimeout = policyLimit;
                }
            }
            long selectedBudgetNanos = selectedTimeout.toNanos();
            long deadlineNanos = callStartedAtNanos + selectedBudgetNanos;
            if (parent != null && parent.deadlineNanos() != Long.MAX_VALUE
                    && parent.deadlineNanos() - callStartedAtNanos <= selectedBudgetNanos) {
                deadlineNanos = parent.deadlineNanos();
            }
            long remainingDeadlineNanos = deadlineNanos - System.nanoTime();
            if (remainingDeadlineNanos <= 0L) {
                result.completeExceptionally(new RpcTimeoutException(
                        "RPC request deadline exceeded before dispatch"));
                return result;
            }
            ScheduledFuture<?> deadlineTask = deadlineScheduler.schedule(
                    () -> result.completeExceptionally(new RpcTimeoutException(
                            "RPC request deadline exceeded")),
                    remainingDeadlineNanos,
                    TimeUnit.NANOSECONDS);
            result.whenComplete((ignoredValue, error) ->
                    deadlineTask.cancel(false));
            if (interceptors != null) {
                RpcInterceptorRuntime.Session session = interceptors.open(
                        new RpcInvocation(RpcInvocation.Side.CONSUMER, reference.key(),
                                method.methodId(), Optional.of(membership.application())), currentCall);
                ((InterceptedCall) result).session = session;
                result.whenComplete((value, error) -> session.finish(
                        result.isCancelled() ? RpcStatus.UNAVAILABLE
                                : error == null ? RpcStatus.OK : statusOf(unwrap(error)),
                        result.isCancelled()));
                long interceptorDeadlineNanos = deadlineNanos;
                session.before(() -> attempt(reference, method, encodedArguments, propagatedMetadata,
                        currentCall, interceptorDeadlineNanos, 1, result), result::completeExceptionally);
            } else {
                attempt(reference, method, encodedArguments, propagatedMetadata,
                        currentCall, deadlineNanos, 1, result);
            }
            return result;
        } catch (RuntimeException | Error setupError) {
            result.completeExceptionally(setupError);
            return result;
        }
    }

    private Map<String, String> propagatedMetadata(
            RpcTraceContext trace, RpcCallContext callContext) {
        if (!tracingBridge.enabled()
                && !metadataPropagator.enabled()
                && callContext == null) {
            return Map.of();
        }
        Map<String, String> metadata =
                new LinkedHashMap<>();
        if (callContext != null) {
            metadata.putAll(callContext.metadata());
        }
        metadata.putAll(trace.metadata());
        if (metadataPropagator.enabled()) {
            metadataPropagator.inject(metadata);
        }
        RpcCallOptions.validateMetadata(metadata);
        return metadata.isEmpty()
                ? Map.of()
                : Map.copyOf(metadata);
    }

    private void attempt(
            ClientReference reference,
            ClientMethodBinding method,
            byte[] encodedArguments,
            Map<String, String> propagatedMetadata,
            RpcCallContext callContext,
            long deadlineNanos,
            int attempt,
            CompletableFuture<Object> result) {
        if (result instanceof InterceptedCall observed && observed.session != null) {
            observed.session.beforeAttempt(attempt,
                    () -> attemptWithLocalSelection(reference, method, encodedArguments,
                            propagatedMetadata, callContext, deadlineNanos, attempt, 0, result),
                    result::completeExceptionally);
        } else {
            attemptWithLocalSelection(reference, method, encodedArguments, propagatedMetadata,
                    callContext, deadlineNanos, attempt, 0, result);
        }
    }

    private void attemptWithLocalSelection(
            ClientReference reference,
            ClientMethodBinding method,
            byte[] encodedArguments,
            Map<String, String> propagatedMetadata,
            RpcCallContext callContext,
            long deadlineNanos,
            int attempt,
            int localSelections,
            CompletableFuture<Object> result) {
        try {
            attemptChecked(reference, method, encodedArguments, propagatedMetadata, callContext,
                    deadlineNanos, attempt, localSelections, result);
        } catch (RpcAuthenticationException | RegistrySecurityException rejected) {
            recordDirectoryFailure(reference, RpcDirectoryFailureReason.SECURITY_REJECTED,
                    null, attempt, localSelections);
            observeClientAttempt(reference, method, null, attempt, 0L, RpcStatus.BAD_REQUEST, rejected, result);
            result.completeExceptionally(rejected);
        } catch (RuntimeException failure) {
            result.completeExceptionally(failure);
        }
    }

    private void attemptChecked(
            ClientReference reference,
            ClientMethodBinding method,
            byte[] encodedArguments,
            Map<String, String> propagatedMetadata,
            RpcCallContext callContext,
            long deadlineNanos,
            int attempt,
            int localSelections,
            CompletableFuture<Object> result) {
        if (result.isDone()) {
            return;
        }
        if (closed.get()) {
            result.completeExceptionally(
                    new RpcUnavailableException("RPC client is closed"));
            return;
        }
        if (governance != null) {
            governance.checkActive();
        }
        if (!membershipReady.isDone() || membershipReady.isCompletedExceptionally()) {
            recordDirectoryFailure(reference, RpcDirectoryFailureReason.MEMBERSHIP_NOT_READY,
                    null, attempt, localSelections);
            result.completeExceptionally(new RpcUnavailableException(
                    "RPC consumer application membership is not ready"));
            return;
        }
        long remainingNanos = deadlineNanos - System.nanoTime();
        if (remainingNanos <= 0L) {
            var timeoutError = new com.peachsoft.otryx.api.RpcTimeoutException(
                    "RPC request deadline exceeded");
            observeClientAttempt(
                    reference,
                    method,
                    null,
                    attempt,
                    0L,
                    RpcStatus.DEADLINE_EXCEEDED,
                    timeoutError, result);
            result.completeExceptionally(timeoutError);
            return;
        }

        ServiceDirectory.Selection selection = reference.directory().selection();
        ServiceInstance[] verifiedInstances = selection.instances();
        ServiceInstance[] instances = verifiedInstances;
        if (verifiedInstances.length > 0 && router.hasRules(reference.key())) {
            ServiceInstance[] routed = router.route(reference.key(), membership.application(),
                    callContext == null ? Map.of() : callContext.metadata(), verifiedInstances.clone());
            requireVerifiedRoute(verifiedInstances, routed);
            instances = routed;
            if (routed.length == 0) {
                recordDirectoryFailure(reference, RpcDirectoryFailureReason.ROUTER_FILTERED_ALL,
                        selection, attempt, localSelections);
                RpcUnavailableException unavailable =
                        new RpcUnavailableException("RPC router excluded all verified providers");
                observeClientAttempt(reference, method, null, attempt, 0L, RpcStatus.UNAVAILABLE, unavailable, result);
                result.completeExceptionally(unavailable);
                return;
            }
        }
        if (governance != null && result instanceof InterceptedCall tracked
                && tracked.governance != null && instances.length > 0) {
            ServiceInstance[] controlled = governance.route(tracked.governance, instances);
            requireVerifiedRoute(instances, controlled);
            instances = controlled;
            if (controlled.length == 0) {
                recordDirectoryFailure(reference, RpcDirectoryFailureReason.GOVERNANCE_FILTERED_ALL,
                        selection, attempt, localSelections);
                RpcUnavailableException excluded = new RpcUnavailableException(
                        "RPC governance excluded all verified providers");
                observeClientAttempt(reference, method, null, attempt, 0L,
                        RpcStatus.UNAVAILABLE, excluded, result);
                result.completeExceptionally(excluded);
                return;
            }
        }
        ServiceInstance selected = instances.length == 0
                ? null : loadBalancer.select(instances, loadMetrics);
        if (selected == null) {
            RpcDirectoryFailureReason cause = switch (selection.status()) {
                case NOT_READY -> RpcDirectoryFailureReason.DIRECTORY_NOT_READY;
                case STALE -> RpcDirectoryFailureReason.DIRECTORY_STALE;
                case CLOSED -> RpcDirectoryFailureReason.DIRECTORY_CLOSED;
                case LAST_KNOWN_GOOD, HEALTHY -> instances.length == 0
                        ? RpcDirectoryFailureReason.VERIFIED_EMPTY
                        : RpcDirectoryFailureReason.NO_HEALTHY_ENDPOINT;
            };
            recordDirectoryFailure(reference, cause, selection, attempt, localSelections);
            String reason = switch (selection.status()) {
                case NOT_READY -> "Registry directory has no initial snapshot for ";
                case STALE -> "Registry directory freshness budget exceeded for ";
                case CLOSED -> "Registry directory has been closed for ";
                case LAST_KNOWN_GOOD -> "No available instance in LKG directory for ";
                case HEALTHY -> "No available instance for ";
            };
            RpcUnavailableException unavailable =
                    new RpcUnavailableException(
                            reason + reference.key().canonicalName());
            observeClientAttempt(
                    reference,
                    method,
                    null,
                    attempt,
                    0L,
                    RpcStatus.UNAVAILABLE,
                    unavailable, result);
            retryOrComplete(
                    reference,
                    method,
                    encodedArguments,
                    propagatedMetadata,
                    callContext,
                    deadlineNanos,
                    attempt,
                    result,
                    unavailable);
            return;
        }

        reference.directory().clearRoutingFailure();
        if (java.util.Arrays.stream(instances).noneMatch(selected::equals)) {
            throw new IllegalStateException("LoadBalancer selected an instance outside the verified directory");
        }
        EndpointStats endpointStats = stats.compute(
                selected.target(),
                (endpoint, existing) -> {
                    EndpointStats current =
                            existing == null ? new EndpointStats() : existing;
                    current.begin();
                    return current;
                });
        long startedAtNanos = System.nanoTime();

        try {
            // Wire v1 uses whole milliseconds: never round a sub-millisecond
            // parent budget up to a new full millisecond.
            long wireRemainingNanos = deadlineNanos - System.nanoTime();
            long remainingMillis = TimeUnit.NANOSECONDS.toMillis(wireRemainingNanos);
            if (remainingMillis <= 0L) {
                endpointStats.endCancelled(System.nanoTime() - startedAtNanos);
                result.completeExceptionally(
                        new RpcTimeoutException("RPC budget exhausted before transport send"));
                return;
            }
            long deadlineEpochMillis =
                    System.currentTimeMillis() + remainingMillis;
            byte[] request = RpcProtocolCodec.encodeRequest(
                    method.codec().codecId(),
                    reference.serviceId(),
                    method.methodId(),
                    deadlineEpochMillis,
                    remainingMillis,
                    propagatedMetadata,
                    encodedArguments);

            if (result.isDone()) {
                endpointStats.endCancelled(System.nanoTime() - startedAtNanos);
                return;
            }
            if (!reference.directory().isUsable(selection)) {
                endpointStats.endSecurityRejected();
                if (localSelections + 1 < MAX_LOCAL_DIRECTORY_SELECTIONS) {
                    // 普通刷新只允许有限次本地重选，不发送旧端点，也不消耗网络重试额度。
                    attemptWithLocalSelection(reference, method, encodedArguments, propagatedMetadata, callContext,
                            deadlineNanos, attempt, localSelections + 1, result);
                } else {
                    result.completeExceptionally(
                            new RpcUnavailableException("Verified directory changed before transport send"));
                }
                return;
            }
            CompletableFuture<RpcTransportResponse> transportFuture = transport.request(
                            selected.target(),
                            request,
                            Duration.ofNanos(wireRemainingNanos))
                    .toCompletableFuture();
            result.whenComplete((ignoredValue, completionError) -> {
                if (closed.get()
                        || result.isCancelled()
                        || unwrap(completionError) instanceof RpcTimeoutException) {
                    transportFuture.cancel(true);
                }
            });

            transportFuture.whenComplete((rawResponse, transportError) -> {
                if (result.isDone()) {
                    if (securityFailure(unwrap(transportError))) {
                        endpointStats.endSecurityRejected();
                    } else {
                        endpointStats.endCancelled(System.nanoTime() - startedAtNanos);
                    }
                    return;
                }
                try {
                    responseCompletionExecutor.execute(
                            RpcCallContext.wrap(callContext, () ->
                                    completeTransportResponse(
                                    reference,
                                    method,
                                    encodedArguments,
                                    propagatedMetadata,
                                    callContext,
                                    deadlineNanos,
                                    attempt,
                                    result,
                                    selected,
                                    endpointStats,
                                    startedAtNanos,
                                    rawResponse,
                                    transportError)));
                } catch (RejectedExecutionException rejected) {
                    // 容量在发起请求前预留。只有执行器关闭或竞争才可能触发这里。
                    endpointStats.endCancelled(
                            System.nanoTime() - startedAtNanos);
                    result.completeExceptionally(
                            new RpcOverloadedException(
                                    "RPC response completion executor unavailable"));
                }
            });
        } catch (RuntimeException failure) {
            Throwable rejected = unwrap(failure);
            if (securityFailure(rejected)) {
                endpointStats.endSecurityRejected();
                observeClientAttempt(reference, method, selected.endpoint(), attempt,
                        System.nanoTime() - startedAtNanos, RpcStatus.BAD_REQUEST, rejected, result);
                result.completeExceptionally(rejected);
                return;
            }
            recordEndpointFailure(reference, selected, endpointStats, System.nanoTime() - startedAtNanos);
            retryOrComplete(reference, method, encodedArguments, propagatedMetadata, callContext,
                    deadlineNanos, attempt, result, failure);
        }
    }

    /**
     * 校验可插拔 Router 只能返回原可信候选的对象引用，禁止伪造或重复端点。
     *
     * @param verified 原始只读候选数组
     * @param routed Router 选择的候选子集
     */
    private static void requireVerifiedRoute(ServiceInstance[] verified, ServiceInstance[] routed) {
        if (routed == null || routed.length > verified.length) {
            throw new IllegalStateException("RPC router returned an invalid candidate array");
        }
        for (int index = 0; index < routed.length; index++) {
            boolean found = false;
            for (ServiceInstance candidate : verified) {
                if (routed[index] == candidate) {
                    found = true;
                    break;
                }
            }
            if (!found) {
                throw new IllegalStateException("RPC router introduced an unverified provider");
            }
            for (int previous = 0; previous < index; previous++) {
                if (routed[index] == routed[previous]) {
                    throw new IllegalStateException("RPC router duplicated a provider");
                }
            }
        }
    }

    private void completeTransportResponse(
            ClientReference reference,
            ClientMethodBinding method,
            byte[] encodedArguments,
            Map<String, String> propagatedMetadata,
            RpcCallContext callContext,
            long deadlineNanos,
            int attempt,
            CompletableFuture<Object> result,
            ServiceInstance selected,
            EndpointStats endpointStats,
            long startedAtNanos,
            RpcTransportResponse rawResponse,
            Throwable transportError) {
        long elapsed = System.nanoTime() - startedAtNanos;
        if (result.isDone()) {
            if (securityFailure(unwrap(transportError))) {
                endpointStats.endSecurityRejected();
            } else {
                endpointStats.endCancelled(elapsed);
            }
            return;
        }
        if (transportError != null) {
            Throwable failure = unwrap(transportError);
            if (securityFailure(failure)) {
                endpointStats.endSecurityRejected();
                observeClientAttempt(reference, method, selected.endpoint(), attempt,
                        elapsed, RpcStatus.BAD_REQUEST, failure, result);
                result.completeExceptionally(failure);
                return;
            }
            if (failure instanceof CancellationException) {
                endpointStats.endCancelled(elapsed);
                return;
            }
            recordEndpointFailure(
                    reference,
                    selected,
                    endpointStats,
                    elapsed);
            observeClientAttempt(
                    reference,
                    method,
                    selected.endpoint(),
                    attempt,
                    elapsed,
                    statusOf(failure),
                    failure, result);
            retryOrComplete(
                    reference,
                    method,
                    encodedArguments,
                    propagatedMetadata,
                    callContext,
                    deadlineNanos,
                    attempt,
                    result,
                    failure);
            return;
        }

        try {
            RpcSecurityChecks.responsePeer(securityProfile, rawResponse.peer(), selected.target(),
                    identityClock.instant(), identityNanoClock.getAsLong());
            Object value = decodeResponse(method, rawResponse.frame());
            RpcSecurityChecks.responsePeer(securityProfile, rawResponse.peer(), selected.target(),
                    identityClock.instant(), identityNanoClock.getAsLong());
            if (result.isDone()) {
                endpointStats.endCancelled(elapsed);
                return;
            }
            endpointStats.endSuccess(elapsed);
            observeClientAttempt(
                    reference,
                    method,
                    selected.endpoint(),
                    attempt,
                    elapsed,
                    RpcStatus.OK,
                    null, result);
            result.complete(value);
        } catch (RpcAuthenticationException | RegistrySecurityException rejected) {
            endpointStats.endSecurityRejected();
            observeClientAttempt(reference, method, selected.endpoint(), attempt,
                    elapsed, RpcStatus.BAD_REQUEST, rejected, result);
            result.completeExceptionally(rejected);
        } catch (RpcRemoteException remoteError) {
            if (ClientRetryController.isRetryable(remoteError)) {
                recordEndpointFailure(
                    reference,
                    selected,
                    endpointStats,
                    elapsed);
                observeClientAttempt(
                        reference,
                        method,
                        selected.endpoint(),
                        attempt,
                        elapsed,
                        remoteError.status(),
                        remoteError, result);
                retryOrComplete(
                        reference,
                        method,
                        encodedArguments,
                        propagatedMetadata,
                        callContext,
                        deadlineNanos,
                        attempt,
                        result,
                        remoteError);
            } else {
                endpointStats.endSuccess(elapsed);
                observeClientAttempt(
                        reference,
                        method,
                        selected.endpoint(),
                        attempt,
                        elapsed,
                        remoteError.status(),
                        remoteError, result);
                result.completeExceptionally(remoteError);
            }
        } catch (Throwable error) {
            recordEndpointFailure(
                    reference,
                    selected,
                    endpointStats,
                    elapsed);
            observeClientAttempt(
                    reference,
                    method,
                    selected.endpoint(),
                    attempt,
                    elapsed,
                    statusOf(error),
                    error, result);
            retryOrComplete(
                    reference,
                    method,
                    encodedArguments,
                    propagatedMetadata,
                    callContext,
                    deadlineNanos,
                    attempt,
                    result,
                    error);
        }
    }

    private void recordEndpointFailure(
            ClientReference reference,
            ServiceInstance selected,
            EndpointStats endpointStats,
            long elapsedNanos) {
        boolean ejected = endpointStats.endFailure(
                elapsedNanos,
                resilienceOptions);
        if (ejected && observer.enabled()) {
            observer.onEndpointEjected(
                    reference.key(),
                    selected.endpoint(),
                    resilienceOptions
                            .outlierEjectionDuration()
                            .toMillis());
        }
    }

    private void retryOrComplete(
            ClientReference reference,
            ClientMethodBinding method,
            byte[] encodedArguments,
            Map<String, String> propagatedMetadata,
            RpcCallContext callContext,
            long deadlineNanos,
            int attempt,
            CompletableFuture<Object> result,
            Throwable failure) {
        if (result.isDone()) {
            return;
        }
        ClientRetryController.Decision decision = retryController.decide(
                method.idempotent(), failure, attempt, deadlineNanos);
        if (!decision.retry()) {
            if (decision.exhaustionReason() != null) {
                observeRetryExhausted(
                        reference, method, decision.exhaustionReason(), failure);
            }
            result.completeExceptionally(failure);
            return;
        }
        long delayMillis = decision.delayMillis();
        if (observer.enabled()) {
            observer.onClientRetryScheduled(
                    reference.key(),
                    method.methodId(),
                    attempt + 1,
                    delayMillis,
                    failure);
        }
        try {
            ScheduledFuture<?> nextAttempt = deadlineScheduler.schedule(
                    RpcCallContext.wrap(callContext, () -> attempt(
                            reference,
                            method,
                            encodedArguments,
                            propagatedMetadata,
                            callContext,
                            deadlineNanos,
                            attempt + 1,
                            result)),
                    delayMillis,
                    TimeUnit.MILLISECONDS);
            result.whenComplete((ignoredValue, error) ->
                    nextAttempt.cancel(false));
        } catch (RejectedExecutionException closedScheduler) {
            result.completeExceptionally(
                    new RpcUnavailableException(
                            "RPC client retry scheduler is unavailable",
                            closedScheduler));
        }
    }

    private void observeRetryExhausted(
            ClientReference reference,
            ClientMethodBinding method,
            RpcRetryExhaustionReason reason,
            Throwable failure) {
        if (!observer.enabled()) {
            return;
        }
        observer.onClientRetryExhausted(
                reference.key(),
                method.methodId(),
                reason,
                failure);
    }

    private void observeCircuitState(
            ClientReference reference,
            ClientMethodBinding method) {
        if (!observer.enabled()) {
            return;
        }
        observer.onClientCircuitStateChanged(
                reference.key(),
                method.methodId(),
                method.circuitBreaker().state());
    }

    private void observeClientAttempt(
            ClientReference reference,
            ClientMethodBinding method,
            RpcEndpoint endpoint,
            int attempt,
            long durationNanos,
            RpcStatus status,
            Throwable error,
            CompletableFuture<Object> result) {
        if (result instanceof InterceptedCall intercepted && intercepted.session != null) {
            intercepted.session.afterAttempt(attempt, status);
        }
        if (observer.enabled()) {
            observer.onClientAttemptCompleted(
                    reference.key(),
                    method.methodId(),
                    endpoint,
                    attempt,
                    durationNanos,
                    status,
                    error);
        }
    }

    /** 保存有拦截器的逻辑调用专属 Session；无拦截器时仍采用原生 CompletableFuture 快路径。 */
    private static final class InterceptedCall extends CompletableFuture<Object> {
        private volatile RpcInterceptorRuntime.Session session;
        private RpcGovernanceRuntime.Selection governance;
    }

    private static RpcStatus statusOf(Throwable error) {
        if (securityFailure(error)) {
            return RpcStatus.BAD_REQUEST;
        }
        if (error instanceof RpcRemoteException remote) {
            return remote.status();
        }
        if (error instanceof RpcOverloadedException) {
            return RpcStatus.OVERLOADED;
        }
        if (error instanceof com.peachsoft.otryx.api.RpcTimeoutException) {
            return RpcStatus.DEADLINE_EXCEEDED;
        }
        if (error instanceof RpcUnavailableException) {
            return RpcStatus.UNAVAILABLE;
        }
        return RpcStatus.INTERNAL_ERROR;
    }

    private static Throwable unwrap(Throwable error) {
        Throwable current = error;
        while (current instanceof CompletionException && current.getCause() != null
                && current.getCause() != current) {
            current = current.getCause();
        }
        return current;
    }

    private static boolean securityFailure(Throwable failure) {
        return failure instanceof RpcAuthenticationException || failure instanceof RegistrySecurityException;
    }

    /**
     * 依据注册中心最新快照清退已经不存在且无在途请求的端点统计。
     *
     * <p>延迟清退发生在控制面线程，不进入请求热路径。
     */
    void evictUnusedEndpointStats() {
        Set<RpcTarget> activeEndpoints = new HashSet<>();
        try {
            for (ServiceDirectory directory : directories.values()) {
                for (ServiceInstance instance : directory.snapshot()) {
                    activeEndpoints.add(instance.target());
                }
            }
        } catch (RegistrySecurityException rejected) {
            // 拒绝不能杀死周期清扫，也不能把安全失效误当作健康端点消失后清空失败历史。
            return;
        }
        stats.forEach((endpoint, ignored) -> {
            if (!activeEndpoints.contains(endpoint)) {
                // 与请求 begin() 在同一个 key 的计算区间内互斥，
                // 避免清退时恰有调用拿到即将移除的旧统计对象。
                stats.computeIfPresent(endpoint, (key, current) ->
                        current.inflight() == 0 ? null : current);
            }
        });
    }

    /** 返回缓存的端点统计数量，用于生命周期诊断。 */
    int trackedEndpointCount() {
        return stats.size();
    }

    private EndpointStats endpointStats(
            ServiceInstance instance) {
        return stats.computeIfAbsent(
                instance.target(),
                ignored -> new EndpointStats());
    }

    private static Object decodeResponse(
            ClientMethodBinding method,
            byte[] rawResponse) {
        RpcFrameView response = RpcProtocolCodec.view(rawResponse);
        if (response.status() != RpcStatus.OK) {
            var remoteError = RpcErrorCodec.decode(
                    response.bytes(),
                    response.payloadOffset(),
                    response.payloadLength());
            throw new RpcRemoteException(
                    response.status(),
                    remoteError.errorType(),
                    remoteError.message());
        }
        if (response.codec() != method.codec().codecId()) {
            throw new RpcException(
                    "RPC response codec does not match bound method codec");
        }
        return method.codec().decodeResult(
                response.bytes(),
                response.payloadOffset(),
                response.payloadLength());
    }

    /**
     * 使用统一单调时钟预算异步关闭 Consumer，不阻塞调用方线程。
     *
     * <p>先拒绝新调用，后台结算所有 Pending 逻辑调用并关闭由本实例持有的
     * 目录、Transport 与执行器。无法强制停止第三方同步 close() 或已经运行的
     * 用户回调；总预算届满时返回包含残留信息的报告。
     *
     * @param options 单次关闭的总预算
     * @return 可重复订阅的首次关闭结果 Stage
     */
    public CompletionStage<RpcClientShutdownReport> shutdown(
            RpcShutdownOptions options) {
        Objects.requireNonNull(options, "options");
        CompletableFuture<RpcClientShutdownReport> current =
                shutdownResult.get();
        if (current != null) {
            return current;
        }
        // 先线性化停收，再初始化共享关闭 Stage，避免关闭启动瞬间接纳新调用。
        closed.set(true);
        CompletableFuture<RpcClientShutdownReport> created =
                new CompletableFuture<>();
        if (!shutdownResult.compareAndSet(null, created)) {
            return shutdownResult.get();
        }
        RpcShutdownBudget budget = RpcShutdownBudget.start(options);
        ClientShutdownProgress progress = new ClientShutdownProgress();
        CompletableFuture.delayedExecutor(
                        options.totalTimeout().toNanos(),
                        TimeUnit.NANOSECONDS)
                .execute(() -> created.complete(
                        clientShutdownReport(
                                budget.startedAtNanos(),
                                progress,
                                true)));
        Thread.ofVirtual()
                .name("otryx-client-shutdown")
                .start(() -> runClientShutdown(
                        budget,
                        progress,
                        created));
        return created;
    }

    private void runClientShutdown(
            RpcShutdownBudget budget,
            ClientShutdownProgress progress,
            CompletableFuture<RpcClientShutdownReport> result) {
        // 执行器清理不能被任何不响应中断的外部 SPI 关闭调用推迟。
        responseCompletionExecutor.shutdownNow();
        deadlineScheduler.shutdownNow();
        if (interceptors != null) {
            interceptors.close();
        }

        // Future 回调可能执行不配合中断的用户代码，必须独立于其他清理阶段。
        CompletableFuture<Void> settleCalls = new CompletableFuture<>();
        Thread.ofVirtual()
                .name("otryx-client-settle-calls")
                .start(() -> {
                    try {
                        pendingCalls.forEach(call -> call.completeExceptionally(
                                new RpcUnavailableException("RPC client is closed")));
                    } finally {
                        settleCalls.complete(null);
                    }
                });

        CompletableFuture<Void> directoryClose = new CompletableFuture<>();
        Thread.ofVirtual()
                .name("otryx-client-directory-close")
                .start(() -> {
                    boolean success = true;
                    for (ServiceDirectory directory : directories.values()) {
                        try {
                            directory.close();
                        } catch (Throwable error) {
                            success = false;
                            progress.failures.incrementAndGet();
                            LOGGER.warn(
                                    "RPC service directory close failed: errorType={}",
                                    error.getClass().getName());
                        }
                    }
                    progress.directoriesClosed = success;
                    directoryClose.complete(null);
                });

        CompletableFuture<Void> unregisterConsumer = membership.stopConsumer().<Void>handle((ignored, error) -> {
            if (error != null) {
                progress.failures.incrementAndGet();
                LOGGER.warn("RPC consumer membership removal failed: errorType={}",
                        error.getClass().getName());
            }
            return null;
        }).toCompletableFuture();

        CompletableFuture<Void> transportClose = new CompletableFuture<>();
        Thread.ofVirtual()
                .name("otryx-client-transport-close")
                .start(() -> {
                    try {
                        transport.close();
                        progress.transportClosed = true;
                    } catch (Throwable error) {
                        progress.failures.incrementAndGet();
                        LOGGER.warn(
                                "RPC client transport close failed: errorType={}",
                                error.getClass().getName());
                    } finally {
                        transportClose.complete(null);
                    }
                });

        try {
            boolean completionTerminated =
                    responseCompletionExecutor.awaitTermination(
                            budget.remainingNanos(),
                            TimeUnit.NANOSECONDS);
            boolean deadlinesTerminated =
                    deadlineScheduler.awaitTermination(
                            budget.remainingNanos(),
                            TimeUnit.NANOSECONDS);
            progress.executorsTerminated =
                    completionTerminated && deadlinesTerminated;
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            progress.failures.incrementAndGet();
        }
        try {
            long remainingNanos = budget.remainingNanos();
            if (remainingNanos > 0L) {
                CompletableFuture.allOf(settleCalls, directoryClose, transportClose, unregisterConsumer)
                        .get(remainingNanos, TimeUnit.NANOSECONDS);
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            progress.failures.incrementAndGet();
        } catch (ExecutionException | TimeoutException error) {
            progress.failures.incrementAndGet();
        }
        result.complete(clientShutdownReport(
                budget.startedAtNanos(),
                progress,
                budget.remainingNanos() == 0L));
    }

    private RpcClientShutdownReport clientShutdownReport(
            long startedAtNanos,
            ClientShutdownProgress progress,
            boolean expired) {
        return new RpcClientShutdownReport(
                TimeUnit.NANOSECONDS.toMillis(
                        System.nanoTime() - startedAtNanos),
                expired,
                progress.directoriesClosed,
                progress.transportClosed,
                progress.executorsTerminated,
                pendingCalls.size(),
                progress.failures.get());
    }

    /**
     * 非阻塞发起默认预算关闭；需要确认资源终态应使用 shutdown(options)。
     */
    @Override
    public void close() {
        shutdown(RpcShutdownOptions.DEFAULT);
    }

    private static final class ClientShutdownProgress {
        private volatile boolean directoriesClosed;
        private volatile boolean transportClosed;
        private volatile boolean executorsTerminated;
        private final AtomicInteger failures = new AtomicInteger();
    }

    private record ClientReference(
            ServiceKey key,
            int serviceId,
            ServiceDirectory directory,
            Map<Method, ClientMethodBinding> methods,
            Map<Integer, ClientMethodBinding> methodsById) {

        private static ClientReference create(
                ServiceKey key,
                Class<?> api,
                ServiceDirectory directory,
                RpcCodecRegistry codecs,
                byte codecId,
                RpcClientResilienceOptions resilienceOptions,
                RpcSecurityProfile securityProfile) {
            Map<Method, ClientMethodBinding> methods = new HashMap<>();
            Map<Integer, ClientMethodBinding> methodsById = new HashMap<>();
            for (Method method : RpcContractMethods.resolve(api)) {
                RpcMethodDescriptor descriptor = RpcMethodDescriptor.from(key, method);
                ClientMethodBinding binding = new ClientMethodBinding(
                        descriptor.methodId(),
                        method.toGenericString(),
                        RpcSecurityChecks.method(securityProfile, codecs.bind(descriptor, codecId), codecId),
                        method.isAnnotationPresent(OtryxRpcIdempotent.class),
                        new RpcCircuitBreaker(
                                resilienceOptions.circuitConsecutiveFailureThreshold(),
                                resilienceOptions.circuitOpenDuration()));
                ClientMethodBinding collision = methodsById.putIfAbsent(
                        descriptor.methodId(),
                        binding);
                if (collision != null) {
                    throw new IllegalStateException(
                            "Method id collision in "
                                    + api.getName()
                                    + ": "
                                    + descriptor.methodId());
                }
                methods.put(method, binding);
            }
            return new ClientReference(
                    key,
                    com.peachsoft.otryx.api.RpcIds.serviceId(key),
                    directory,
                    Map.copyOf(methods),
                    Map.copyOf(methodsById));
        }

        private ClientMethodBinding require(Method method) {
            ClientMethodBinding binding = methods.get(method);
            if (binding == null) {
                throw new IllegalStateException(
                        "Method is not part of RPC service contract: " + method);
            }
            return binding;
        }

        private ClientMethodBinding require(int methodId) {
            ClientMethodBinding binding = methodsById.get(methodId);
            if (binding == null) {
                throw new IllegalStateException(
                        "Method id is not part of RPC service contract: " + methodId);
            }
            return binding;
        }
    }

    private record ClientMethodBinding(
            int methodId,
            String methodKey,
            RpcMethodCodec codec,
            boolean idempotent,
            RpcCircuitBreaker circuitBreaker) {
    }

    private final class GeneratedInvocation implements RpcGeneratedInvocation {
        private final ClientReference reference;

        private GeneratedInvocation(ClientReference reference) {
            this.reference = reference;
        }

        @Override
        public CompletionStage<Object> invoke0(int methodId) {
            return OtryxRpcClient.this.invoke0(
                    reference,
                    reference.require(methodId));
        }

        @Override
        public CompletionStage<Object> invoke1(
                int methodId,
                Object argument0) {
            return OtryxRpcClient.this.invoke1(
                    reference,
                    reference.require(methodId),
                    argument0);
        }

        @Override
        public CompletionStage<Object> invoke2(
                int methodId,
                Object argument0,
                Object argument1) {
            return OtryxRpcClient.this.invoke2(
                    reference,
                    reference.require(methodId),
                    argument0,
                    argument1);
        }

        @Override
        public CompletionStage<Object> invoke3(
                int methodId,
                Object argument0,
                Object argument1,
                Object argument2) {
            return OtryxRpcClient.this.invoke3(
                    reference,
                    reference.require(methodId),
                    argument0,
                    argument1,
                    argument2);
        }

        @Override
        public CompletionStage<Object> invoke4(
                int methodId,
                Object argument0,
                Object argument1,
                Object argument2,
                Object argument3) {
            return OtryxRpcClient.this.invoke4(
                    reference,
                    reference.require(methodId),
                    argument0,
                    argument1,
                    argument2,
                    argument3);
        }

        @Override
        public CompletionStage<Object> invokeN(
                int methodId,
                Object[] arguments) {
            return OtryxRpcClient.this.invokeN(
                    reference,
                    reference.require(methodId),
                    arguments);
        }
    }

    /** Consumer 运行时 Builder。 */
    public static final class Builder {
        private RpcSecurityProfile securityProfile = RpcSecurityProfile.UNSPECIFIED;
        private Clock identityClock = Clock.systemUTC();
        private LongSupplier identityNanoClock = System::nanoTime;
        private ApplicationServiceResolver applicationResolver;
        private ApplicationMembershipCoordinator membership;
        private String applicationNamespace;
        private RpcTransportClient transport;
        private RpcCodecRegistry codecs;
        private LoadBalancer loadBalancer;
        private RpcRouter router;
        private RpcGovernanceRuntime governance;
        private List<RpcInterceptor> interceptors = List.of();
        private int interceptorWorkers = RpcInterceptorRuntime.DEFAULT_WORKERS;
        private int interceptorMaxActive = RpcInterceptorRuntime.DEFAULT_MAX_ACTIVE;
        private ProxyFactory proxyFactory;
        private Duration timeout = Duration.ofSeconds(3);
        private Map<String, Duration> methodTimeouts = Map.of();
        private RpcDirectoryFreshnessOptions directoryFreshnessOptions =
                RpcDirectoryFreshnessOptions.DEFAULT;
        private RpcClientResilienceOptions resilienceOptions =
                RpcClientResilienceOptions.DEFAULT;
        private int responseCompletionThreads = Math.max(
                2, Math.min(16, Runtime.getRuntime().availableProcessors()));
        private int responseCompletionQueueCapacity = 4096;
        private RpcObserver observer = RpcObserver.noop();
        private RpcMetadataPropagator metadataPropagator =
                RpcMetadataPropagator.noop();
        private RpcTracingBridge tracingBridge =
                RpcTracingBridge.noop();

        /** 创建 Consumer Builder。 */
        public Builder() {
        }

        /**
         * 显式设置宿主安全要求，未指定不能创建运行资源。
         *
         * @param value 非 null 安全 Profile
         * @return Consumer Builder
         */
        public Builder securityProfile(RpcSecurityProfile value) {
            this.securityProfile = Objects.requireNonNull(value, "securityProfile");
            return this;
        }

        Builder identityClock(Clock clock, LongSupplier nanoClock) {
            this.identityClock = Objects.requireNonNull(clock, "identityClock");
            this.identityNanoClock = Objects.requireNonNull(nanoClock, "identityNanoClock");
            return this;
        }

        /**
         * 配置应用发现及必须注册的 Consumer 应用身份。
         *
         * @param resolver 经 Mapping/Manifest/Schema 验证的发现解析器
         * @param membership 与 Provider 共享的进程成员协调器
         * @param namespace 应用级隔离域
         * @return Consumer Builder
         */
        public Builder applicationControlPlane(
                ApplicationServiceResolver resolver,
                ApplicationMembershipCoordinator membership,
                String namespace) {
            this.applicationResolver = Objects.requireNonNull(resolver, "resolver");
            this.membership = Objects.requireNonNull(membership, "membership");
            this.applicationNamespace = new com.peachsoft.otryx.api.ApplicationKey(
                    namespace, "consumer").namespace();
            return this;
        }

        /**
         * 设置 Transport Client。
         *
         * @param value Transport Client
         * @return Consumer Builder
         */
        public Builder transportClient(RpcTransportClient value) {
            this.transport = value;
            return this;
        }

        /**
         * 设置 Codec 注册表。
         *
         * @param value Codec 注册表
         * @return Consumer Builder
         */
        public Builder codecRegistry(RpcCodecRegistry value) {
            this.codecs = value;
            return this;
        }

        /**
         * 设置负载均衡器。
         *
         * @param value 负载均衡器
         * @return Consumer Builder
         */
        public Builder loadBalancer(LoadBalancer value) {
            this.loadBalancer = value;
            return this;
        }

        /**
         * 配置路由 SPI，先对已验证应用候选做本地过滤，然后执行负载均衡。
         *
         * @param value 非 null 路由器，热路径不得远端 IO
         * @return Consumer Builder
         */
        public Builder router(RpcRouter value) {
            this.router = Objects.requireNonNull(value, "router");
            return this;
        }

        /**
         * 安装显式受信治理运行时；没有配置源时继续走零治理对象快路径。
         *
         * @param value 已配备认证验证器且只在控制面订阅的运行时
         * @return Consumer Builder
         */
        public Builder governanceRuntime(RpcGovernanceRuntime value) {
            this.governance = Objects.requireNonNull(value, "governanceRuntime");
            return this;
        }

        /**
         * 指定显式有序调用拦截器；空列表禁用并保留零 Hook 排队的快路径。
         *
         * @param value 不含 null 的拦截器实例
         * @return Consumer Builder
         */
        public Builder interceptors(List<RpcInterceptor> value) {
            this.interceptors = List.copyOf(Objects.requireNonNull(value, "interceptors"));
            return this;
        }

        /**
         * 指定每实例专属 Hook Worker 和逻辑调用配额。
         *
         * @param workers 有界固定 Worker 数
         * @param maxActive 逻辑调用持有的 Hook 生命周期数量
         * @return Consumer Builder
         */
        public Builder interceptorExecutionLimits(int workers, int maxActive) {
            if (workers < 1 || workers > RpcInterceptorRuntime.MAX_ALLOWED_WORKERS
                    || maxActive < 1 || maxActive > RpcInterceptorRuntime.MAX_ALLOWED_ACTIVE) {
                throw new IllegalArgumentException("Invalid RPC interceptor worker or session capacity");
            }
            interceptorWorkers = workers;
            interceptorMaxActive = maxActive;
            return this;
        }

        /**
         * 设置代理工厂。
         *
         * @param value 代理工厂
         * @return Consumer Builder
         */
        public Builder proxyFactory(ProxyFactory value) {
            this.proxyFactory = value;
            return this;
        }

        /**
         * 设置默认 RPC 调用超时时间。
         *
         * @param value 默认 RPC 调用超时时间
         * @return Consumer Builder
         */
        public Builder timeout(Duration value) {
            if (value == null || value.isZero() || value.isNegative()) {
                throw new IllegalArgumentException("timeout must be positive");
            }
            this.timeout = value;
            return this;
        }

        /**
         * 设置通过 Method.toGenericString() 标识的服务方法默认超时。
         *
         * @param value 方法签名与正数超时映射
         * @return Consumer Builder
         */
        public Builder methodTimeouts(Map<String, Duration> value) {
            Objects.requireNonNull(value, "methodTimeouts");
            value.forEach((key, duration) -> {
                if (key == null || key.isBlank() || duration == null
                        || duration.isNegative() || duration.isZero()) {
                    throw new IllegalArgumentException("Invalid RPC method timeout");
                }
                try {
                    duration.toNanos();
                } catch (ArithmeticException invalid) {
                    throw new IllegalArgumentException(
                            "RPC method timeout exceeds nanosecond range", invalid);
                }
            });
            this.methodTimeouts = Map.copyOf(value);
            return this;
        }

        /**
         * 配置 Registry 显式同步失败后的 Last-Known-Good 允许陈旧时长。
         *
         * <p>默认不限制。没有 Watch 变更事件本身不代表不健康。
         *
         * @param value 不允许为 null 的目录陈旧策略
         * @return 当前 Builder
         */
        public Builder directoryFreshnessOptions(RpcDirectoryFreshnessOptions value) {
            this.directoryFreshnessOptions = Objects.requireNonNull(
                    value, "directoryFreshnessOptions");
            return this;
        }

        /**
         * 设置 Consumer 容错参数。
         *
         * @param value 容错参数
         * @return Consumer Builder
         */
        public Builder resilienceOptions(RpcClientResilienceOptions value) {
            this.resilienceOptions = Objects.requireNonNull(
                    value,
                    "resilienceOptions");
            return this;
        }

        /**
         * 配置 Consumer 响应解码和回调隔离执行器线程数。
         *
         * @param value 正数线程数
         * @return Consumer Builder
         */
        public Builder responseCompletionThreads(int value) {
            if (value <= 0) {
                throw new IllegalArgumentException(
                        "responseCompletionThreads must be positive");
            }
            this.responseCompletionThreads = value;
            return this;
        }

        /**
         * 配置 Consumer 响应完成任务的有界队列容量。
         *
         * @param value 正数队列容量
         * @return Consumer Builder
         */
        public Builder responseCompletionQueueCapacity(int value) {
            if (value <= 0) {
                throw new IllegalArgumentException(
                        "responseCompletionQueueCapacity must be positive");
            }
            this.responseCompletionQueueCapacity = value;
            return this;
        }

        /**
         * 设置 Consumer 可观测性 Observer。
         *
         * @param value Observer
         * @return Consumer Builder
         */
        public Builder observer(RpcObserver value) {
            this.observer = Objects.requireNonNull(value, "observer");
            return this;
        }

        /**
         * 设置 RPC Metadata Propagator。
         *
         * @param value Metadata Propagator
         * @return Consumer Builder
         */
        public Builder metadataPropagator(
                RpcMetadataPropagator value) {
            this.metadataPropagator =
                    Objects.requireNonNull(
                            value,
                            "metadataPropagator");
            return this;
        }

        /**
         * 设置分布式 Trace Bridge。
         *
         * @param value Trace Bridge
         * @return Consumer Builder
         */
        public Builder tracingBridge(
                RpcTracingBridge value) {
            this.tracingBridge =
                    Objects.requireNonNull(
                            value,
                            "tracingBridge");
            return this;
        }

        /**
         * 只读校验当前实际对象，不创建运行时、线程、周期任务或成员注册。
         */
        public void validate() {
            RpcSecurityChecks.profile(securityProfile);
            Objects.requireNonNull(applicationResolver, "applicationResolver");
            Objects.requireNonNull(membership, "applicationMembership");
            Objects.requireNonNull(applicationNamespace, "applicationNamespace");
            Objects.requireNonNull(transport, "transportClient");
            Objects.requireNonNull(codecs, "codecRegistry");
            Objects.requireNonNull(observer, "observer");
            Objects.requireNonNull(metadataPropagator, "metadataPropagator");
            Objects.requireNonNull(tracingBridge, "tracingBridge");
            Objects.requireNonNull(directoryFreshnessOptions, "directoryFreshnessOptions");
            Objects.requireNonNull(resilienceOptions, "resilienceOptions");
            RpcSecurityChecks.transport(securityProfile, transport::securityDescriptor,
                    membership.application(), codecs, true, identityClock.instant());
            RpcSecurityChecks.codecs(securityProfile, codecs);
            if (!membership.application().namespace().equals(applicationNamespace)) {
                throw new IllegalStateException("Consumer and membership namespaces must match");
            }
            RpcSecurityChecks.registry(securityProfile, membership::securityDescriptor, applicationNamespace);
            RpcSecurityChecks.registry(securityProfile, applicationResolver::securityDescriptor, applicationNamespace);
            RpcSecurityChecks.positiveFinite(timeout, "timeout");
            methodTimeouts.values().forEach(value -> RpcSecurityChecks.positiveFinite(value, "methodTimeout"));
            if (responseCompletionThreads <= 0 || responseCompletionQueueCapacity <= 0
                    || (long) responseCompletionThreads + responseCompletionQueueCapacity > Integer.MAX_VALUE) {
                throw new IllegalArgumentException("Invalid response completion capacity");
            }
        }

        /**
         * 创建 Consumer 运行时。
         *
         * @return Consumer 运行时
         */
        public OtryxRpcClient build() {
            validate();
            return new OtryxRpcClient(this);
        }
    }
}
