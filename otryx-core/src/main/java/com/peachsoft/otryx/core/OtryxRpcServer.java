package com.peachsoft.otryx.core;

import com.peachsoft.otryx.api.RpcAuthorizationContext;
import com.peachsoft.otryx.api.RpcPeerIdentity;
import com.peachsoft.otryx.api.RpcEndpoint;
import com.peachsoft.otryx.api.RpcException;
import com.peachsoft.otryx.api.RpcExecutionMode;
import com.peachsoft.otryx.api.RpcIds;
import com.peachsoft.otryx.api.RpcRemoteError;
import com.peachsoft.otryx.api.RpcStatus;
import com.peachsoft.otryx.api.ExportedService;
import com.peachsoft.otryx.api.ServiceKey;
import com.peachsoft.otryx.api.RpcSecurityProfile;
import com.peachsoft.otryx.api.RpcAuthorizationDecision;
import com.peachsoft.otryx.codec.RpcCodecRegistry;
import com.peachsoft.otryx.interceptor.RpcInterceptor;
import com.peachsoft.otryx.interceptor.RpcInterceptorRuntime;
import com.peachsoft.otryx.interceptor.RpcInvocation;
import com.peachsoft.otryx.codec.RpcMethodCodec;
import com.peachsoft.otryx.codec.RpcCodecIds;
import com.peachsoft.otryx.observability.RpcMetadataPropagator;
import com.peachsoft.otryx.observability.RpcMetadataScope;
import com.peachsoft.otryx.observability.RpcObserver;
import com.peachsoft.otryx.observability.RpcTraceContext;
import com.peachsoft.otryx.observability.RpcTracingBridge;
import com.peachsoft.otryx.protocol.RpcErrorCodec;
import com.peachsoft.otryx.protocol.RpcFrameView;
import com.peachsoft.otryx.protocol.RpcMessageType;
import com.peachsoft.otryx.protocol.RpcProtocolCodec;
import com.peachsoft.otryx.protocol.RpcProtocolException;
import com.peachsoft.otryx.transport.RpcRequestHandler;
import com.peachsoft.otryx.transport.RpcTransportServer;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * OTRYX RPC Provider 运行时，协调服务注册、请求准入、执行与响应。
 *
 * <p>Provider 启动会先发布导出契约、监听网络端点和注册应用成员，随后进入可处理请求的
 * STARTED 状态。仅 TCP 端口可连接不代表业务已就绪。
 *
 * <p>并发和已准入 Frame 字节数受到全局/服务/方法级预算约束；
 * Lease 在响应结束且业务执行实际退出后才归还；取消不会假定业务已停止。
 * 预算不代表 JVM Heap 的硬上限。
 *
 * <p>使用者负责在停止服务时调用 {@link #close()}，等待排空期间的资源管理
 * 以实际关闭策略为准。
 *
 * @Author Ryan
 * @Version 1.0.0
 * @CreateTime 2026/9/23 10:51
 */
public final class OtryxRpcServer implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger(OtryxRpcServer.class);

    private final RpcSecurityProfile securityProfile;
    private final Clock identityClock;
    private final LongSupplier identityNanoClock;
    private final Duration maxAuthorizationGrantLifetime;
    private final ApplicationMembershipCoordinator membership;
    private final List<ExportedService> exportedServices = new CopyOnWriteArrayList<>();
    private final RpcTransportServer transport;
    private final RpcCodecRegistry codecs;
    private final RpcEndpoint bindEndpoint;
    private final String advertisedHost;
    private final int advertisedPort;
    private final int maxConcurrent;
    private final RpcProviderAdmissionOptions admissionOptions;
    private final RpcResponseEncodingBudget responseEncodingBudget;
    private volatile ProviderAdmissionController admission;
    private final Duration drainTimeout;
    private final Duration controlPlaneTimeout;
    private final RpcProviderExecutionOptions executionOptions;
    private final RpcObserver observer;
    private final RpcInterceptorRuntime interceptors;
    private final RpcMetadataPropagator metadataPropagator;
    private final RpcTracingBridge tracingBridge;
    private final RpcAuthorizationGate authorizationGate;
    private final ScheduledThreadPoolExecutor requestDeadlineScheduler =
            new ScheduledThreadPoolExecutor(1,
                    Thread.ofPlatform().daemon().name("otryx-provider-deadline-", 0).factory());
    private final ExecutorService blockingExecutor =
            Executors.newVirtualThreadPerTaskExecutor();
    private final ThreadPoolExecutor cpuExecutor;
    private final ConcurrentMap<Integer, ServiceBinding> bindings =
            new ConcurrentHashMap<>();
    private final AtomicReference<State> state =
            new AtomicReference<>(State.NEW);
    private final AtomicReference<CompletableFuture<RpcShutdownReport>> shutdownResult =
            new AtomicReference<>();
    private final Set<CompletableFuture<byte[]>> pendingResponses =
            ConcurrentHashMap.newKeySet();

    private volatile RpcEndpoint actualEndpoint;
    private volatile RpcEndpoint advertisedEndpoint;

    private OtryxRpcServer(Builder builder) {
        this.securityProfile = builder.securityProfile;
        this.identityClock = builder.identityClock;
        this.identityNanoClock = builder.identityNanoClock;
        this.maxAuthorizationGrantLifetime = builder.maxAuthorizationGrantLifetime;
        this.membership = Objects.requireNonNull(builder.membership, "applicationMembership");
        this.transport = Objects.requireNonNull(
                builder.transport,
                "transportServer");
        this.codecs = Objects.requireNonNull(
                builder.codecs,
                "codecRegistry");
        this.bindEndpoint = Objects.requireNonNull(
                builder.bind,
                "bindEndpoint");
        this.advertisedHost = builder.advertisedHost;
        this.advertisedPort = builder.advertisedPort;
        this.maxConcurrent = builder.maxConcurrent;
        this.admissionOptions = Objects.requireNonNull(
                builder.admissionOptions,
                "admissionOptions");
        this.responseEncodingBudget = new RpcResponseEncodingBudget(
                builder.maxBufferedEncodingBytes, builder.maxSingleResponseBytes);
        this.drainTimeout = Objects.requireNonNull(
                builder.drainTimeout,
                "drainTimeout");
        this.controlPlaneTimeout = Objects.requireNonNull(
                builder.controlPlaneTimeout,
                "controlPlaneTimeout");
        this.executionOptions = Objects.requireNonNull(
                builder.executionOptions,
                "executionOptions");
        // 单个自定义 Observer 也必须隔离异常，避免影响 Admission 归还和响应 Future。
        // NOOP Observer 会由 composite 保持为无事件的轻量实现。
        this.observer = RpcObserver.composite(
                List.of(Objects.requireNonNull(
                        builder.observer,
                        "observer")));
        this.interceptors = builder.interceptors.isEmpty() ? null : new RpcInterceptorRuntime(
                builder.interceptors, builder.interceptorWorkers,
                builder.interceptorMaxActive, "provider");
        this.metadataPropagator = Objects.requireNonNull(
                builder.metadataPropagator,
                "metadataPropagator");
        this.tracingBridge = Objects.requireNonNull(
                builder.tracingBridge,
                "tracingBridge");
        this.requestDeadlineScheduler.setRemoveOnCancelPolicy(true);
        this.authorizationGate = new RpcAuthorizationGate(
                builder.authorizer != null ? builder.authorizer
                        : context -> CompletableFuture.completedFuture(
                                RpcAuthorizationDecision.allowUntil(context.maximumValidUntil())),
                builder.maxPendingAuthorizations,
                builder.maxPendingAuthorizationBytes,
                builder.authorizationTimeout);
        this.cpuExecutor = new ThreadPoolExecutor(
                executionOptions.cpuParallelism(),
                executionOptions.cpuParallelism(),
                0L,
                TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(
                        executionOptions.cpuQueueCapacity()),
                Thread.ofPlatform()
                        .name("otryx-cpu-", 0)
                        .factory(),
                new ThreadPoolExecutor.AbortPolicy());
    }

    /**
     * 创建 Provider Builder。
     *
     * @return Provider Builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * 注册本地服务实现。
     *
     * @param api 服务接口
     * @param implementation 服务实现
     * @param version 服务版本
     * @param group 服务分组
     * @return 当前 Server
     */
    public OtryxRpcServer registerService(
            Class<?> api,
            Object implementation,
            String version,
            String group) {
        if (state.get() != State.NEW) {
            throw new IllegalStateException(
                    "Services can only be registered before server start");
        }
        Objects.requireNonNull(api, "api");
        Objects.requireNonNull(implementation, "implementation");
        if (!api.isInstance(implementation)) {
            throw new IllegalArgumentException(
                    "Service implementation does not implement " + api.getName());
        }

        ServiceKey key = new ServiceKey(api.getName(), version, group);
        int serviceId = RpcIds.serviceId(key);
        ServiceBinding binding =
                new ServiceBinding(key, api, implementation, codecs, securityProfile);
        if (binding.usesDirectExecution()
                && !executionOptions.allowDirect()) {
            throw new IllegalStateException(
                    "DIRECT RPC execution is disabled; enable it explicitly before registering "
                            + api.getName());
        }
        ServiceBinding previous = bindings.putIfAbsent(
                serviceId,
                binding);
        if (previous != null) {
            throw new IllegalStateException(
                    "Service id collision: " + serviceId);
        }
        exportedServices.add(ExportedService.fromContract(key, api));
        return this;
    }

    /**
     * 发布 Manifest/Mapping，启动 TCP Server，再注册应用成员。
     *
     * @return 异步启动结果，成功时返回实际监听端点
     */
    public CompletionStage<RpcEndpoint> start() {
        if (!state.compareAndSet(State.NEW, State.STARTING)) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException(
                            "RPC server has already been started or closed"));
        }

        try {
            RpcSecurityChecks.profile(securityProfile);
            RpcSecurityChecks.transport(securityProfile, transport::securityDescriptor,
                    membership.application(), codecs, false, identityClock.instant());
            RpcSecurityChecks.codecs(securityProfile, codecs);
            RpcSecurityChecks.registry(securityProfile, membership::securityDescriptor,
                    membership.application().namespace());
            Map<Integer, Set<Integer>> methodsByService = new HashMap<>();
            bindings.forEach((serviceId, binding) ->
                    methodsByService.put(serviceId, binding.methodIds()));
            admission = new ProviderAdmissionController(
                    maxConcurrent,
                    admissionOptions,
                    methodsByService);
        } catch (RuntimeException error) {
            state.compareAndSet(State.STARTING, State.CLOSED);
            return CompletableFuture.failedFuture(error);
        }

        CompletableFuture<RpcEndpoint> started = new CompletableFuture<>();
        CompletionStage<Void> preparation = membership.prepareProvider(exportedServices);
        preparation.thenCompose(ignored -> transport.start(bindEndpoint, new RpcRequestHandler() {
                    @Override
                    public CompletionStage<byte[]> handle(RpcEndpoint remote, byte[] frame) {
                        return OtryxRpcServer.this.handle(
                                remote, RpcPeerIdentity.anonymous(), frame, null);
                    }

                    @Override
                    public CompletionStage<byte[]> handle(
                            RpcEndpoint remote, RpcPeerIdentity peer, byte[] frame) {
                        return OtryxRpcServer.this.handle(remote, peer, frame, null);
                    }

                    @Override
                    public CompletionStage<byte[]> handle(
                            RpcEndpoint remote, RpcPeerIdentity peer,
                            byte[] frame, Executor requestExecutor) {
                        return OtryxRpcServer.this.handle(
                                remote, peer, frame, requestExecutor);
                    }
                }))
                .whenComplete((endpoint, transportError) -> {
                    if (transportError != null) {
                        failAfterPreparation(started, transportError);
                        return;
                    }
                    if (state.get() != State.STARTING) {
                        failAfterPreparation(started,
                                new IllegalStateException("RPC server was closed during startup"));
                        return;
                    }
                    actualEndpoint = endpoint;
                    RpcEndpoint published;
                    try {
                        published = resolveAdvertisedEndpoint(endpoint);
                    } catch (RuntimeException error) {
                        failAfterPreparation(started, error);
                        return;
                    }
                    advertisedEndpoint = published;
                    registerApplication(published)
                            .whenComplete((ignored, registryError) -> {
                                if (registryError != null) {
                                    rollbackApplication()
                                            .whenComplete((rollbackIgnored, rollbackError) -> {
                                                state.compareAndSet(
                                                        State.STARTING,
                                                        State.CLOSED);
                                                try {
                                                    transport.close();
                                                } catch (RuntimeException closeError) {
                                                    registryError.addSuppressed(closeError);
                                                }
                                                if (rollbackError != null) {
                                                    registryError.addSuppressed(rollbackError);
                                                }
                                                started.completeExceptionally(registryError);
                                            });
                                    return;
                                }
                                if (!state.compareAndSet(
                                        State.STARTING,
                                        State.STARTED)) {
                                    rollbackApplication()
                                            .whenComplete((ignoredRollback, rollbackError) -> {
                                                if (rollbackError != null) {
                                                    LOGGER.warn(
                                                            "RPC startup rollback failed during shutdown: "
                                                                    + "errorType={}",
                                                            rollbackError.getClass().getName());
                                                }
                                                started.completeExceptionally(
                                                        new IllegalStateException(
                                                                "RPC server closed during startup"));
                                            });
                                    return;
                                }
                                LOGGER.info(
                                        "OTRYX RPC server started: bind={}, advertised={}",
                                        endpoint.authority(),
                                        published.authority());
                                started.complete(endpoint);
                            });
                });
        return started;
    }

    private void failAfterPreparation(CompletableFuture<RpcEndpoint> started, Throwable error) {
        state.compareAndSet(State.STARTING, State.CLOSED);
        try {
            transport.close();
        } catch (RuntimeException closeError) {
            error.addSuppressed(closeError);
        }
        membership.stopProvider().whenComplete((ignored, rollbackError) -> {
            if (rollbackError != null) {
                error.addSuppressed(rollbackError);
            }
            started.completeExceptionally(error);
        });
    }

    private RpcEndpoint resolveAdvertisedEndpoint(RpcEndpoint endpoint) {
        String host = advertisedHost;
        if (host == null || host.isBlank()) {
            host = bindEndpoint.host();
            if (isWildcardHost(host)) {
                throw new IllegalStateException(
                        "RPC advertised host is required when bind host is " + host);
            }
        }
        int port = advertisedPort > 0
                ? advertisedPort
                : endpoint.port();
        if (port <= 0) {
            throw new IllegalStateException(
                    "RPC advertised port could not be resolved");
        }
        return new RpcEndpoint(host, port);
    }

    private static boolean isWildcardHost(String host) {
        return RpcEndpoint.UNSPECIFIED_IPV4_HOST.equals(host)
                || RpcEndpoint.UNSPECIFIED_IPV6_HOST.equals(host)
                || RpcEndpoint.UNSPECIFIED_IPV6_BRACKETED_HOST.equals(host);
    }

    private CompletionStage<Void> registerApplication(RpcEndpoint endpoint) {
        return membership.providerReady(endpoint).toCompletableFuture()
                .copy().orTimeout(controlPlaneTimeout.toMillis(), TimeUnit.MILLISECONDS);
    }

    private CompletionStage<Void> rollbackApplication() {
        return membership.stopProvider().toCompletableFuture()
                .copy().orTimeout(controlPlaneTimeout.toMillis(), TimeUnit.MILLISECONDS);
    }

    private CompletionStage<byte[]> handle(
            RpcEndpoint remote,
            RpcPeerIdentity peer,
            byte[] rawFrame,
            Executor requestExecutor) {
        long receivedAtNanos = System.nanoTime();
        Instant receivedAt = identityClock.instant();
        long receivedIdentityNanos = identityNanoClock.getAsLong();
        RpcFrameView request;
        try {
            request = RpcProtocolCodec.view(rawFrame);
        } catch (RuntimeException error) {
            return CompletableFuture.failedFuture(error);
        }

        if (request.messageType() != RpcMessageType.REQUEST) {
            return CompletableFuture.failedFuture(
                    new RpcProtocolException("Expected request frame"));
        }
        if (state.get() != State.STARTED) {
            return CompletableFuture.completedFuture(
                    frameworkError(
                            request,
                            RpcStatus.UNAVAILABLE,
                            "Server is not ready"));
        }

        long deadline;
        long timeoutBudgetMillis;
        try {
            deadline = request.deadlineEpochMillis();
            timeoutBudgetMillis = request.timeoutBudgetMillis();
        } catch (RpcProtocolException error) {
            return CompletableFuture.completedFuture(
                    frameworkError(
                            request,
                            RpcStatus.BAD_REQUEST,
                            "Invalid deadline"));
        }
        // 新节点优先采用相对时限；历史 Epoch-only 客户端在接收时
        // 折算为本机的单调时间预算，避免授权队列重获完整超时。
        long initialRequestNanos = Long.MAX_VALUE;
        if (timeoutBudgetMillis > 0L) {
            initialRequestNanos = TimeUnit.MILLISECONDS.toNanos(timeoutBudgetMillis);
        } else if (deadline > 0L) {
            initialRequestNanos = TimeUnit.MILLISECONDS.toNanos(
                    deadline - System.currentTimeMillis());
        }
        long requestDeadlineNanos = initialRequestNanos == Long.MAX_VALUE
                ? Long.MAX_VALUE : receivedAtNanos + initialRequestNanos;
        if (initialRequestNanos != Long.MAX_VALUE
                && initialRequestNanos <= System.nanoTime() - receivedAtNanos) {
            return CompletableFuture.completedFuture(
                    frameworkError(request, RpcStatus.DEADLINE_EXCEEDED,
                            "Deadline exceeded"));
        }

        ServiceBinding binding = bindings.get(request.serviceId());
        if (binding == null) {
            return CompletableFuture.completedFuture(
                    frameworkError(
                            request,
                            RpcStatus.SERVICE_NOT_FOUND,
                            "Service not found"));
        }

        RpcMethodCodec methodCodec;
        try {
            methodCodec = binding.codec(
                    request.methodId(),
                    request.codec());
        } catch (NoSuchMethodException error) {
            return CompletableFuture.completedFuture(
                    frameworkError(
                            request,
                            RpcStatus.METHOD_NOT_FOUND,
                            "Method not found"));
        } catch (IllegalArgumentException error) {
            return CompletableFuture.completedFuture(
                    frameworkError(
                            request,
                            RpcStatus.BAD_REQUEST,
                            "Unsupported codec"));
        }

        // Validate application metadata even when tracing is disabled. The peer
        // identity is sourced exclusively from the authenticated transport connection.
        RpcCallContext callContext;
        try {
            callContext = RpcCallContext.inbound(
                    requestDeadlineNanos, request.metadataCopy());
        } catch (RuntimeException invalidMetadata) {
            return CompletableFuture.completedFuture(frameworkError(
                    request, RpcStatus.BAD_REQUEST, "Invalid request metadata"));
        }

        if (!RpcSecurityChecks.providerPeer(securityProfile, peer, membership.application(),
                identityClock.instant(), identityNanoClock.getAsLong())) {
            return CompletableFuture.completedFuture(frameworkError(
                    request, RpcStatus.BAD_REQUEST, "RPC authentication rejected"));
        }
        Instant maximumValidUntil = receivedAt.plus(maxAuthorizationGrantLifetime);
        if (initialRequestNanos != Long.MAX_VALUE) {
            Instant requestUntil = receivedAt.plusNanos(initialRequestNanos);
            if (requestUntil.isBefore(maximumValidUntil)) {
                maximumValidUntil = requestUntil;
            }
        }
        if (peer.authenticated() && peer.validUntil().orElseThrow().isBefore(maximumValidUntil)) {
            maximumValidUntil = peer.validUntil().orElseThrow();
        }
        RpcAuthorizationContext context = new RpcAuthorizationContext(
                peer, membership.application(), binding.serviceKey(), request.methodId(), remote,
                receivedAt, maximumValidUntil);
        long authorizationStartedNanos = System.nanoTime();
        final long authorizationRemainingNanos = initialRequestNanos == Long.MAX_VALUE
                ? Long.MAX_VALUE : initialRequestNanos - (authorizationStartedNanos - receivedAtNanos);
        long maximumAuthorizationNanos = Duration.between(receivedAt, context.maximumValidUntil()).toNanos();
        CompletableFuture<RpcAuthorizationGate.Result> pendingAuthorization = authorizationGate.authorize(
                context, request.bytes().length, authorizationRemainingNanos, () -> {
                    long elapsed = identityNanoClock.getAsLong() - receivedIdentityNanos;
                    return elapsed >= 0 && elapsed < maximumAuthorizationNanos && callContext.remainingNanos() > 0L
                            && identityClock.instant().isBefore(context.maximumValidUntil())
                            && RpcSecurityChecks.providerPeer(securityProfile, peer, membership.application(),
                                    identityClock.instant(), identityNanoClock.getAsLong());
                }).toCompletableFuture();
        boolean authorizedInline = pendingAuthorization.isDone();
        CompletableFuture<byte[]> reply = new CompletableFuture<>();
        AtomicReference<CompletableFuture<byte[]>> executing = new AtomicReference<>();
        reply.whenComplete((ignoredValue, ignoredFailure) -> {
            if (reply.isCancelled()) {
                CompletableFuture<byte[]> running = executing.get();
                if (running != null) {
                    running.cancel(true);
                }
            }
        });
        pendingAuthorization.whenComplete((outcome, failure) -> {
            if (reply.isDone()) {
                return;
            }
            try {
                if (failure != null || outcome == null) {
                    reply.complete(frameworkError(request, RpcStatus.BAD_REQUEST, "RPC authorization denied"));
                    return;
                }
                if (outcome.decision() == RpcAuthorizationGate.Decision.OVERLOADED) {
                    reply.complete(frameworkError(request, RpcStatus.OVERLOADED, "Authorization capacity exhausted"));
                    return;
                }
                if (outcome.decision() == RpcAuthorizationGate.Decision.DEADLINE_EXCEEDED
                        || callContext.remainingNanos() == 0L) {
                    reply.complete(frameworkError(request, RpcStatus.DEADLINE_EXCEEDED,
                            "Deadline exceeded during authorization"));
                    return;
                }
                RpcAuthorizationGrant grant = RpcAuthorizationGrant.create(
                        context, outcome.authorization(), securityProfile, receivedIdentityNanos, reply::isDone);
                if (outcome.decision() != RpcAuthorizationGate.Decision.ALLOW || grant == null
                        || !grant.validAt(identityClock.instant(), identityNanoClock.getAsLong())) {
                    reply.complete(frameworkError(request, RpcStatus.BAD_REQUEST, "RPC authorization denied"));
                    return;
                }
                CompletionStage<byte[]> authorized;
                if (!authorizedInline && requestExecutor != null) {
                    authorized = dispatchAuthorizedOnRequestExecutor(
                            requestExecutor, request, binding, methodCodec, callContext, grant);
                } else if (!authorizedInline && requestExecutor == null
                        && binding.executionMode(request.methodId()) == RpcExecutionMode.DIRECT) {
                    authorized = CompletableFuture.completedFuture(frameworkError(request, RpcStatus.UNAVAILABLE,
                            "DIRECT execution requires a transport request dispatcher"));
                } else {
                    authorized = executeAuthorized(request, binding, methodCodec, callContext, grant);
                }
                CompletableFuture<byte[]> activeResponse = authorized.toCompletableFuture();
                executing.set(activeResponse);
                if (reply.isCancelled()) {
                    activeResponse.cancel(true);
                }
                activeResponse.whenComplete((bytes, error) -> {
                    if (error != null) {
                        reply.completeExceptionally(error);
                    } else {
                        reply.complete(bytes);
                    }
                });
            } catch (Throwable error) {
                reply.complete(frameworkError(request, RpcStatus.BAD_REQUEST, "RPC authorization denied"));
            }
        });
        return reply;
    }

    /**
     * 异步授权后回到实际请求所属 EventLoop，再复核原许可并进入业务准入。
     */
    private CompletionStage<byte[]> dispatchAuthorizedOnRequestExecutor(
            Executor dispatcher,
            RpcFrameView request,
            ServiceBinding binding,
            RpcMethodCodec codec,
            RpcCallContext callContext,
            RpcAuthorizationGrant grant) {
        CompletableFuture<byte[]> reply = new CompletableFuture<>();
        try {
            dispatcher.execute(() -> {
                if (reply.isDone()) {
                    return;
                }
                if (callContext.remainingNanos() == 0L) {
                    reply.complete(frameworkError(request, RpcStatus.DEADLINE_EXCEEDED,
                            "Deadline exceeded during request dispatch"));
                    return;
                }
                if (!grant.validAt(identityClock.instant(), identityNanoClock.getAsLong())) {
                    reply.complete(frameworkError(request, RpcStatus.BAD_REQUEST, "RPC authorization denied"));
                    return;
                }
                try {
                    CompletableFuture<byte[]> response = executeAuthorized(
                            request, binding, codec, callContext, grant).toCompletableFuture();
                    reply.whenComplete((ignored, error) -> {
                        if (reply.isCancelled()) {
                            response.cancel(true);
                        }
                    });
                    response.whenComplete((bytes, error) -> {
                        if (error != null) {
                            reply.completeExceptionally(error);
                        } else {
                            reply.complete(bytes);
                        }
                    });
                } catch (Throwable error) {
                    reply.completeExceptionally(error);
                }
            });
        } catch (RuntimeException error) {
            reply.complete(frameworkError(request, RpcStatus.UNAVAILABLE, "Request dispatcher is unavailable"));
        }
        return reply;
    }

    private CompletionStage<byte[]> executeAuthorized(
            RpcFrameView request,
            ServiceBinding binding,
            RpcMethodCodec methodCodec,
            RpcCallContext callContext,
            RpcAuthorizationGrant grant) {
        if (state.get() != State.STARTED) {
            return CompletableFuture.completedFuture(
                    frameworkError(request, RpcStatus.UNAVAILABLE, "Server is not ready"));
        }
        if (callContext.remainingNanos() == 0L) {
            return CompletableFuture.completedFuture(frameworkError(
                    request, RpcStatus.DEADLINE_EXCEEDED, "Deadline exceeded before admission"));
        }
        if (!grant.validAt(identityClock.instant(), identityNanoClock.getAsLong())) {
            return CompletableFuture.completedFuture(frameworkError(
                    request, RpcStatus.BAD_REQUEST, "RPC authorization denied"));
        }
        ProviderAdmissionController.Decision decision =
                admission.tryAcquire(
                        request.serviceId(),
                        request.methodId(),
                        request.bytes().length);
        if (!decision.accepted()) {
            observeAdmissionRejected(request, decision.reason());
            return CompletableFuture.completedFuture(
                    errorResponse(
                            request,
                            RpcStatus.OVERLOADED,
                            RpcException.class.getName(),
                            "Provider admission rejected: " + decision.reason()));
        }

        CompletableFuture<byte[]> result =
                new CompletableFuture<>();
        boolean observed = observer.enabled();
        // 两个终态：传输侧响应结束，以及业务执行（含异步 Stage）结束。
        // Future.cancel 不能证明业务线程停止，故不得直接释放并发资源。
        AtomicInteger remainingTerminals = new AtomicInteger(2);
        AtomicBoolean workFinished = new AtomicBoolean();
        Runnable releaseIfComplete = () -> {
            if (remainingTerminals.decrementAndGet() == 0
                    && decision.lease().release() && observed) {
                observer.onServerInflightBytesChanged(
                        -request.bytes().length);
                observer.onServerInflightChanged(-1);
            }
        };
        Runnable markWorkFinished = () -> {
            if (workFinished.compareAndSet(false, true)) {
                releaseIfComplete.run();
            }
        };
        pendingResponses.add(result);
        result.whenComplete((ignoredValue, ignoredError) -> {
            pendingResponses.remove(result);
            releaseIfComplete.run();
        });
        if (state.get() != State.STARTED) {
            result.cancel(true);
        }
        if (observed) {
            observer.onServerInflightChanged(1);
            observer.onServerInflightBytesChanged(request.bytes().length);
        }
        // Timeout completes only the response terminal. A running business task
        // retains its admission lease until it actually finishes.
        if (callContext.deadlineNanos() != Long.MAX_VALUE) {
            long remainingBudget = callContext.remainingNanos();
            try {
                ScheduledFuture<?> timeoutTask = requestDeadlineScheduler.schedule(
                        () -> result.complete(frameworkError(
                                request, RpcStatus.DEADLINE_EXCEEDED,
                                "RPC request deadline exceeded")),
                        remainingBudget, TimeUnit.NANOSECONDS);
                result.whenComplete((ignoredValue, ignoredFailure) ->
                        timeoutTask.cancel(false));
            } catch (RejectedExecutionException shutdown) {
                result.complete(frameworkError(
                        request, RpcStatus.UNAVAILABLE, "Deadline scheduler unavailable"));
                markWorkFinished.run();
                return result;
            }
        }
        if (result.isDone()) {
            markWorkFinished.run();
            return result;
        }

        RpcExecutionMode executionMode;
        try {
            executionMode = binding.executionMode(request.methodId());
        } catch (NoSuchMethodException error) {
            result.complete(frameworkError(
                    request,
                    RpcStatus.METHOD_NOT_FOUND,
                    "Method not found"));
            markWorkFinished.run();
            return result;
        }

        Map<String, String> propagatedMetadata;
        RpcTraceContext trace;
        try {
            propagatedMetadata = tracingBridge.enabled()
                    || metadataPropagator.enabled()
                    ? request.metadataCopy()
                    : Map.of();
            trace = tracingBridge.enabled()
                    ? tracingBridge.startServer(
                            request.serviceId(),
                            request.methodId(),
                            propagatedMetadata)
                    : RpcTraceContext.noop();
        } catch (RpcProtocolException invalidMetadata) {
            result.complete(frameworkError(
                    request,
                    RpcStatus.BAD_REQUEST,
                    "Invalid request metadata"));
            markWorkFinished.run();
            return result;
        } catch (RuntimeException setupError) {
            LOGGER.warn(
                    "RPC tracing setup failed: serviceId={}, methodId={}, errorType={}",
                    request.serviceId(),
                    request.methodId(),
                    setupError.getClass().getName());
            result.complete(frameworkError(
                    request,
                    RpcStatus.INTERNAL_ERROR,
                    "Provider request setup failed"));
            markWorkFinished.run();
            return result;
        }
        result.whenComplete((responseBytes, error) ->
                trace.end(
                        responseStatus(
                                responseBytes,
                                error),
                        error));

        long observationStartedAtNanos =
                observer.enabled() ? System.nanoTime() : 0L;
        if (observer.enabled()) {
            result.whenComplete((responseBytes, error) ->
                    observeServerInvocation(
                            request,
                            executionMode,
                            observationStartedAtNanos,
                            responseBytes,
                            error));
        }

        if (interceptors != null) {
            RpcInterceptorRuntime.Session session;
            try {
                session = interceptors.open(
                        new RpcInvocation(RpcInvocation.Side.PROVIDER, binding.serviceKey(),
                                request.methodId(), grant.callerApplication()), callContext);
            } catch (com.peachsoft.otryx.api.RpcOverloadedException capacity) {
                observeAdmissionRejected(request, "interceptor-capacity");
                result.complete(frameworkError(request, RpcStatus.OVERLOADED,
                        "Provider interceptor admission exhausted"));
                markWorkFinished.run();
                return result;
            }
            result.whenComplete((bytes, failure) ->
                    session.finish(responseStatus(bytes, failure), result.isCancelled()));
            session.before(() -> {
                if (result.isDone() || !permitExecution(request, result, callContext, grant)) {
                    markWorkFinished.run();
                    return;
                }
                dispatchProviderExecution(request, binding, methodCodec, propagatedMetadata,
                        trace, callContext, grant, result, executionMode, markWorkFinished);
            }, rejected -> {
                result.complete(frameworkError(request, RpcStatus.INTERNAL_ERROR,
                        "Provider interceptor rejected request"));
                markWorkFinished.run();
            });
        } else {
            dispatchProviderExecution(request, binding, methodCodec, propagatedMetadata,
                    trace, callContext, grant, result, executionMode, markWorkFinished);
        }
        return result;
    }

    /**
     * 原始准入/授权检查完成后的唯一 Provider 执行路径，DIRECT 在 Hook Worker 上调用。
     *
     * <p>带拦截器时被有界 Worker 调用；无拦截器时保留原来的执行线程和热路径。
     * 真实 Stage 和响应终态的双租约由既有 markWorkFinished 逻辑保持不变。
     */
    private void dispatchProviderExecution(
            RpcFrameView request,
            ServiceBinding binding,
            RpcMethodCodec methodCodec,
            Map<String, String> propagatedMetadata,
            RpcTraceContext trace,
            RpcCallContext callContext,
            RpcAuthorizationGrant grant,
            CompletableFuture<byte[]> result,
            RpcExecutionMode executionMode,
            Runnable markWorkFinished) {
        if (executionMode == RpcExecutionMode.DIRECT) {
            execute(
                    request,
                    binding,
                    methodCodec,
                    propagatedMetadata,
                    trace,
                    callContext,
                    grant,
                    result,
                    cpuExecutor,
                    markWorkFinished);
            return;
        }

        ExecutorService selectedExecutor =
                executionMode == RpcExecutionMode.CPU
                        ? cpuExecutor
                        : blockingExecutor;
        AtomicBoolean executionClaimed = new AtomicBoolean();
        Future<?> task;
        try {
            task = selectedExecutor.submit(() -> {
                if (executionClaimed.compareAndSet(false, true)) {
                    execute(
                            request,
                            binding,
                            methodCodec,
                            propagatedMetadata,
                            trace,
                            callContext,
                            grant,
                            result,
                            selectedExecutor,
                            markWorkFinished);
                }
            });
        } catch (RejectedExecutionException error) {
            observeAdmissionRejected(request, "cpu-queue");
            result.complete(errorResponse(
                    request,
                    RpcStatus.OVERLOADED,
                    RpcException.class.getName(),
                    "Provider execution queue is full"));
            markWorkFinished.run();
            return;
        }
        result.whenComplete((ignoredValue, ignoredError) -> {
            if (result.isCancelled()
                    && task.cancel(true)
                    && executionClaimed.compareAndSet(false, true)) {
                // Runnable 未进入业务代码；被取消的排队任务不会再执行。
                markWorkFinished.run();
            }
        });
        return;
    }

    private void observeAdmissionRejected(
            RpcFrameView request,
            String reason) {
        if (!observer.enabled()) {
            return;
        }
        observer.onServerAdmissionRejected(
                request.serviceId(),
                request.methodId(),
                reason);
    }

    private void observeServerInvocation(
            RpcFrameView request,
            RpcExecutionMode executionMode,
            long startedAtNanos,
            byte[] responseBytes,
            Throwable error) {
        observer.onServerInvocationCompleted(
                request.serviceId(),
                request.methodId(),
                executionMode,
                System.nanoTime() - startedAtNanos,
                responseStatus(responseBytes, error),
                error);
    }

    private static RpcStatus responseStatus(
            byte[] responseBytes,
            Throwable error) {
        if (error instanceof java.util.concurrent.CancellationException) {
            return RpcStatus.UNAVAILABLE;
        }
        if (error != null || responseBytes == null) {
            return RpcStatus.INTERNAL_ERROR;
        }
        try {
            return RpcProtocolCodec.view(responseBytes).status();
        } catch (RuntimeException ignored) {
            return RpcStatus.INTERNAL_ERROR;
        }
    }

    private void execute(
            RpcFrameView request,
            ServiceBinding binding,
            RpcMethodCodec methodCodec,
            Map<String, String> propagatedMetadata,
            RpcTraceContext trace,
            RpcCallContext callContext,
            RpcAuthorizationGrant grant,
            CompletableFuture<byte[]> result,
            ExecutorService asyncCompletionExecutor,
            Runnable markWorkFinished) {
        boolean deferredCompletion = false;
        try (RpcCallScope ignored = RpcCallContext.attach(callContext);
             RpcMetadataScope traceScope =
                     trace.makeCurrent();
             RpcMetadataScope propagationScope =
                     metadataPropagator.enabled()
                             ? metadataPropagator.extract(
                                     propagatedMetadata)
                             : RpcMetadataScope.noop()) {
            if (result.isDone()) {
                return;
            }
            if (!permitExecution(request, result, callContext, grant)) {
                return;
            }
            Object[] arguments = methodCodec.decodeArguments(
                    request.bytes(),
                    request.payloadOffset(),
                    request.payloadLength());
            if (!permitExecution(request, result, callContext, grant)) {
                return;
            }
            Object value = binding.invoke(
                    request.methodId(),
                    arguments);
            if (value instanceof CompletionStage<?> stage) {
                deferredCompletion = true;
                completeAsyncInvocation(
                        request,
                        methodCodec,
                        stage,
                        result,
                        asyncCompletionExecutor,
                        propagatedMetadata,
                        trace,
                        callContext,
                        grant,
                        markWorkFinished);
                return;
            }
            byte[] responseBytes = encodedResponse(request, methodCodec, value);
            completeAuthorizedResponse(request, result, responseBytes, callContext, grant);
        } catch (Throwable error) {
            completeInvocationFailure(request, result, error);
        } finally {
            if (!deferredCompletion) {
                markWorkFinished.run();
            }
        }
    }

    private void completeAsyncInvocation(
            RpcFrameView request,
            RpcMethodCodec methodCodec,
            CompletionStage<?> stage,
            CompletableFuture<byte[]> result,
            ExecutorService completionExecutor,
            Map<String, String> propagatedMetadata,
            RpcTraceContext trace,
            RpcCallContext callContext,
            RpcAuthorizationGrant grant,
            Runnable markWorkFinished) {
        try {
            CompletableFuture<?> asyncFuture = stage.toCompletableFuture();
            boolean completedInline = asyncFuture.isDone();
            // 不主动取消业务 Stage：CompletableFuture.cancel 仅保证 Future
            // 逻辑终态，不保证业务的底层 IO 或线程已经结束。
            asyncFuture.whenComplete((value, error) -> {
                if (result.isDone()) {
                    markWorkFinished.run();
                    return;
                }
                if (completedInline) {
                    try {
                        completeAsyncInvocationResult(
                                request,
                                methodCodec,
                                result,
                                propagatedMetadata,
                                trace,
                                callContext,
                                grant,
                                value,
                                error);
                    } finally {
                        markWorkFinished.run();
                    }
                    return;
                }
                dispatchAsyncCompletion(
                        request,
                        methodCodec,
                        result,
                        completionExecutor,
                        propagatedMetadata,
                        trace,
                        callContext,
                        grant,
                        value,
                        error,
                        markWorkFinished);
            });
        } catch (Throwable setupError) {
            try {
                completeInvocationFailure(request, result, setupError);
            } finally {
                markWorkFinished.run();
            }
        }
    }

    private void dispatchAsyncCompletion(
            RpcFrameView request,
            RpcMethodCodec methodCodec,
            CompletableFuture<byte[]> result,
            ExecutorService completionExecutor,
            Map<String, String> propagatedMetadata,
            RpcTraceContext trace,
            RpcCallContext callContext,
            RpcAuthorizationGrant grant,
            Object value,
            Throwable error,
            Runnable markWorkFinished) {
        if (result.isDone()) {
            markWorkFinished.run();
            return;
        }
        try {
            completionExecutor.execute(() -> {
                try {
                    completeAsyncInvocationResult(
                            request,
                            methodCodec,
                            result,
                            propagatedMetadata,
                            trace,
                            callContext,
                            grant,
                            value,
                            error);
                } finally {
                    markWorkFinished.run();
                }
            });
        } catch (RejectedExecutionException rejection) {
            try {
                observeAdmissionRejected(request, "async-completion-queue");
                if (!result.isCancelled()) {
                    LOGGER.warn(
                            "RPC async completion was rejected: requestId={}, serviceId={}, "
                                    + "methodId={}, errorType={}",
                            request.requestId(),
                            request.serviceId(),
                            request.methodId(),
                            rejection.getClass().getName());
                    byte[] responseBytes = errorResponse(
                            request,
                            RpcStatus.OVERLOADED,
                            RpcException.class.getName(),
                            "Provider async completion queue is full");
                    completeResponse(result, responseBytes);
                }
            } finally {
                markWorkFinished.run();
            }
        }
    }

    private void completeAsyncInvocationResult(
            RpcFrameView request,
            RpcMethodCodec methodCodec,
            CompletableFuture<byte[]> result,
            Map<String, String> propagatedMetadata,
            RpcTraceContext trace,
            RpcCallContext callContext,
            RpcAuthorizationGrant grant,
            Object value,
            Throwable error) {
        try (RpcCallScope ignored = RpcCallContext.attach(callContext);
             RpcMetadataScope traceScope =
                     trace.makeCurrent();
             RpcMetadataScope propagationScope =
                     metadataPropagator.enabled()
                             ? metadataPropagator.extract(
                                     propagatedMetadata)
                             : RpcMetadataScope.noop()) {
            if (result.isDone()) {
                return;
            }
            if (error != null) {
                completeInvocationFailure(
                        request,
                        result,
                        unwrapCompletionFailure(error));
                return;
            }
            if (!permitResponse(request, result, callContext, grant)) {
                return;
            }
            byte[] responseBytes = encodedResponse(request, methodCodec, value);
            completeAuthorizedResponse(request, result, responseBytes, callContext, grant);
        } catch (Throwable completionError) {
            completeInvocationFailure(
                    request,
                    result,
                    completionError);
        }
    }

    private boolean permitExecution(
            RpcFrameView request, CompletableFuture<byte[]> result,
            RpcCallContext callContext, RpcAuthorizationGrant grant) {
        if (result.isDone()) {
            return false;
        }
        if (callContext.remainingNanos() == 0L) {
            result.complete(frameworkError(request, RpcStatus.DEADLINE_EXCEEDED, "RPC request deadline exceeded"));
            return false;
        }
        if (!grant.validAt(identityClock.instant(), identityNanoClock.getAsLong())) {
            result.complete(frameworkError(request, RpcStatus.BAD_REQUEST, "RPC authorization denied"));
            return false;
        }
        return true;
    }

    private boolean permitResponse(
            RpcFrameView request, CompletableFuture<byte[]> result,
            RpcCallContext callContext, RpcAuthorizationGrant grant) {
        if (result.isDone()) {
            return false;
        }
        if (callContext.remainingNanos() == 0L) {
            result.complete(frameworkError(request, RpcStatus.DEADLINE_EXCEEDED, "RPC request deadline exceeded"));
            return false;
        }
        if (!grant.peerValidAt(identityClock.instant(), identityNanoClock.getAsLong())) {
            result.complete(frameworkError(request, RpcStatus.BAD_REQUEST, "RPC authentication rejected"));
            return false;
        }
        return true;
    }

    private void completeAuthorizedResponse(
            RpcFrameView request, CompletableFuture<byte[]> result, byte[] bytes,
            RpcCallContext callContext, RpcAuthorizationGrant grant) {
        if (permitResponse(request, result, callContext, grant)) {
            result.complete(bytes);
        }
    }

    private static Throwable unwrapCompletionFailure(Throwable error) {
        Throwable current = error;
        while (current instanceof java.util.concurrent.CompletionException
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    /**
     * 流式写入全局有界编码缓冲，完整构造 Wire Frame 后再释放局部输出额度。
     *
     * <p>NETWORK_MTLS 的方法级 Codec 必须声明流式编码契约；
     * 可信开发环境的默认兼容 Codec 仍可能预分配其内部 Payload。
     */
    private byte[] encodedResponse(RpcFrameView request, RpcMethodCodec methodCodec, Object value)
            throws java.io.IOException {
        int codecMax = methodCodec.securityDescriptor().maxPayloadBytes();
        try (RpcResponseEncodingBudget.Buffer encoded = responseEncodingBudget.open(codecMax)) {
            methodCodec.encodeResultTo(value, encoded);
            return encoded.toResponseFrame(methodCodec.codecId(), RpcStatus.OK,
                    request.requestId(), request.serviceId(), request.methodId());
        }
    }

    /** 供包内压力测试读取框架输出缓冲目前保留的容量。 */
    long responseEncodingBytes() {
        return responseEncodingBudget.reservedBytes();
    }

    /** 供包内压力测试读取该实例编码输出缓冲的最高保留容量。 */
    long responseEncodingHighWaterBytes() {
        return responseEncodingBudget.highWaterBytes();
    }

    private void completeInvocationFailure(
            RpcFrameView request,
            CompletableFuture<byte[]> result,
            Throwable error) {
        if (result.isCancelled()) {
            return;
        }
        if (RpcResponseEncodingBudget.isCapacityExceeded(error)) {
            observeAdmissionRejected(request, "response-encoding");
            result.complete(frameworkError(request, RpcStatus.OVERLOADED, "Response encoding capacity exhausted"));
            return;
        }
        // User exception messages may contain raw request parameters or
        // credentials. Log only bounded technical identifiers and the type.
        LOGGER.warn(
                "RPC service invocation failed. requestId={}, serviceId={}, methodId={}, errorType={}",
                request.requestId(),
                request.serviceId(),
                request.methodId(),
                error.getClass().getName());
        byte[] responseBytes;
        try {
            responseBytes = errorResponse(
                    request,
                    RpcStatus.BUSINESS_ERROR,
                    error.getClass().getName(),
                    "Remote service invocation failed");
        } catch (Throwable responseError) {
            result.completeExceptionally(responseError);
            return;
        }
        completeResponse(
                result,
                responseBytes);
    }

    private void completeResponse(
            CompletableFuture<byte[]> result,
            byte[] responseBytes) {
        result.complete(responseBytes);
    }

    private static byte[] frameworkError(
            RpcFrameView request,
            RpcStatus status,
            String message) {
        return errorResponse(
                request,
                status,
                RpcException.class.getName(),
                message);
    }

    private static byte[] errorResponse(
            RpcFrameView request,
            RpcStatus status,
            String errorType,
            String message) {
        return response(
                request,
                RpcCodecIds.CONTROL,
                status,
                RpcErrorCodec.encode(new RpcRemoteError(errorType, message)));
    }

    private static byte[] response(
            RpcFrameView request,
            byte codecId,
            RpcStatus status,
            byte[] payload) {
        return RpcProtocolCodec.encodeResponse(
                codecId,
                status,
                request.requestId(),
                request.serviceId(),
                request.methodId(),
                payload);
    }

    /**
     * 在一个总预算内异步关闭 Provider，合并并发关闭请求。
     *
     * <p>调用本方法不会阻塞 EventLoop。到达预算时返回报告，
     * 不把线程中断或业务 Future 取消视为已停止用户业务。
     * 重复调用返回首次关闭对应的同一个 Stage，后续传入预算不覆盖它。
     *
     * @param options 总关闭预算
     * @return 实际资源终态报告，包含剩余业务租约数量
     */
    public CompletionStage<RpcShutdownReport> shutdown(
            RpcShutdownOptions options) {
        Objects.requireNonNull(options, "options");
        CompletableFuture<RpcShutdownReport> current = shutdownResult.get();
        if (current != null) {
            return current;
        }
        CompletableFuture<RpcShutdownReport> created =
                new CompletableFuture<>();
        if (!shutdownResult.compareAndSet(null, created)) {
            return shutdownResult.get();
        }
        State previous = state.getAndSet(State.DRAINING);
        if (interceptors != null) {
            interceptors.close();
        }
        RpcShutdownBudget budget = RpcShutdownBudget.start(options);
        ShutdownProgress progress = new ShutdownProgress();

        // 超时哨兵独立于可能卡在外部 SDK 的协调器，保证 Stage 有界终态。
        CompletableFuture.delayedExecutor(
                        options.totalTimeout().toNanos(),
                        TimeUnit.NANOSECONDS)
                .execute(() -> created.complete(
                        shutdownReport(
                                progress,
                                budget.startedAtNanos(),
                                true)));

        Thread.ofVirtual()
                .name("otryx-provider-shutdown")
                .start(() -> runShutdown(
                        previous,
                        budget,
                        progress,
                        created));
        return created;
    }

    private void runShutdown(
            State previous,
            RpcShutdownBudget budget,
            ShutdownProgress progress,
            CompletableFuture<RpcShutdownReport> result) {
        try {
            unregisterWithinBudget(budget, progress);
            if (previous == State.STARTED
                    && budget.remainingNanos() > 0L) {
                try {
                    long drainBudgetNanos = Math.min(
                            budget.remainingNanos(),
                            drainTimeout.toNanos());
                    CompletionStage<Void> stage =
                            transport.drain(Duration.ofNanos(drainBudgetNanos));
                    progress.drained = awaitWithin(stage, drainBudgetNanos);
                    if (!progress.drained) {
                        progress.failures.incrementAndGet();
                        LOGGER.warn("RPC provider drain did not finish within shutdown budget");
                    }
                } catch (RuntimeException error) {
                    progress.failures.incrementAndGet();
                    LOGGER.warn(
                            "RPC provider drain failed: errorType={}",
                            error.getClass().getName());
                }
            } else if (previous != State.STARTED) {
                progress.drained = true;
            }
        } catch (Throwable error) {
            progress.failures.incrementAndGet();
            LOGGER.warn(
                    "RPC provider shutdown control phase failed: errorType={}",
                    error.getClass().getName());
        } finally {
            // Transport/执行器清理独立执行；任何一个失败都不能跳过余下资源。
            pendingResponses.forEach(response -> response.cancel(true));
            try {
                transport.close();
                progress.transportClosed = true;
            } catch (Throwable error) {
                progress.failures.incrementAndGet();
                LOGGER.warn(
                        "RPC provider transport close failed: errorType={}",
                        error.getClass().getName());
            }
            requestDeadlineScheduler.shutdownNow();
            try {
                authorizationGate.close();
            } catch (RuntimeException error) {
                progress.failures.incrementAndGet();
                LOGGER.warn(
                        "RPC authorization scheduler shutdown failed: errorType={}",
                        error.getClass().getName());
            }
            try {
                blockingExecutor.shutdownNow();
            } catch (RuntimeException error) {
                progress.failures.incrementAndGet();
                LOGGER.warn(
                        "RPC blocking executor shutdown failed: errorType={}",
                        error.getClass().getName());
            }
            try {
                cpuExecutor.shutdownNow();
            } catch (RuntimeException error) {
                progress.failures.incrementAndGet();
                LOGGER.warn(
                        "RPC CPU executor shutdown failed: errorType={}",
                        error.getClass().getName());
            }
            try {
                boolean virtualTerminated =
                        blockingExecutor.awaitTermination(
                                budget.remainingNanos(),
                                TimeUnit.NANOSECONDS);
                boolean cpuTerminated =
                        cpuExecutor.awaitTermination(
                                budget.remainingNanos(),
                                TimeUnit.NANOSECONDS);
                progress.executorsTerminated =
                        virtualTerminated && cpuTerminated;
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                progress.failures.incrementAndGet();
            } finally {
                state.set(State.CLOSED);
                result.complete(shutdownReport(
                        progress,
                        budget.startedAtNanos(),
                        budget.remainingNanos() == 0L));
            }
        }
    }

    private void unregisterWithinBudget(
            RpcShutdownBudget budget,
            ShutdownProgress progress) {
        long remaining = Math.min(budget.remainingNanos(), controlPlaneTimeout.toNanos());
        if (remaining > 0L) {
            try {
                progress.registrationsRemoved = awaitWithin(membership.stopProvider(), remaining);
            } catch (RuntimeException error) {
                progress.failures.incrementAndGet();
                LOGGER.warn("RPC application membership revoke failed: errorType={}",
                        error.getClass().getName());
            }
        }
        if (!progress.registrationsRemoved) {
            progress.failures.incrementAndGet();
        }
    }

    private static boolean awaitWithin(
            CompletionStage<?> stage,
            long timeoutNanos) {
        if (timeoutNanos <= 0L) {
            return false;
        }
        try {
            stage.toCompletableFuture().get(
                    timeoutNanos,
                    TimeUnit.NANOSECONDS);
            return true;
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            return false;
        } catch (ExecutionException | TimeoutException error) {
            return false;
        }
    }

    private RpcShutdownReport shutdownReport(
            ShutdownProgress progress,
            long startedAtNanos,
            boolean expired) {
        ProviderAdmissionController currentAdmission = admission;
        int remaining = currentAdmission == null
                ? 0
                : currentAdmission.inFlightCalls();
        return new RpcShutdownReport(
                TimeUnit.NANOSECONDS.toMillis(
                        System.nanoTime() - startedAtNanos),
                expired,
                progress.registrationsRemoved,
                progress.drained,
                progress.transportClosed,
                progress.executorsTerminated,
                remaining,
                progress.failures.get());
    }

    /**
     * 发起默认预算的非阻塞关闭。需要等待或检查终态的调用方应使用
     * {@link #shutdown(RpcShutdownOptions)} 并等待返回的 Stage。
     */
    @Override
    public void close() {
        shutdown(RpcShutdownOptions.DEFAULT);
    }

    private static final class ShutdownProgress {
        private volatile boolean registrationsRemoved;
        private volatile boolean drained;
        private volatile boolean transportClosed;
        private volatile boolean executorsTerminated;
        private final AtomicInteger failures = new AtomicInteger();
    }

    private enum State {
        NEW,
        STARTING,
        STARTED,
        DRAINING,
        CLOSED
    }

    /** Provider 运行时 Builder。 */
    public static final class Builder {
        private RpcSecurityProfile securityProfile = RpcSecurityProfile.UNSPECIFIED;
        private Clock identityClock = Clock.systemUTC();
        private LongSupplier identityNanoClock = System::nanoTime;
        private Duration maxAuthorizationGrantLifetime = Duration.ofSeconds(30);
        private ApplicationMembershipCoordinator membership;
        private RpcTransportServer transport;
        private RpcCodecRegistry codecs;
        private RpcEndpoint bind;
        private String advertisedHost;
        private int advertisedPort;
        private int maxConcurrent = 4096;
        private RpcProviderAdmissionOptions admissionOptions =
                RpcProviderAdmissionOptions.DEFAULT;
        private long maxBufferedEncodingBytes = RpcResponseEncodingBudget.DEFAULT_TOTAL_BYTES;
        private int maxSingleResponseBytes = RpcResponseEncodingBudget.DEFAULT_SINGLE_BYTES;
        private Duration drainTimeout = Duration.ofSeconds(30);
        private Duration controlPlaneTimeout = Duration.ofSeconds(3);
        private RpcProviderExecutionOptions executionOptions =
                RpcProviderExecutionOptions.DEFAULT;
        private RpcObserver observer = RpcObserver.noop();
        private List<RpcInterceptor> interceptors = List.of();
        private int interceptorWorkers = RpcInterceptorRuntime.DEFAULT_WORKERS;
        private int interceptorMaxActive = RpcInterceptorRuntime.DEFAULT_MAX_ACTIVE;
        private RpcMetadataPropagator metadataPropagator =
                RpcMetadataPropagator.noop();
        private RpcTracingBridge tracingBridge =
                RpcTracingBridge.noop();
        private RpcAuthorizer authorizer;
        private int maxPendingAuthorizations = 1024;
        private long maxPendingAuthorizationBytes = 64L * 1024L * 1024L;
        private Duration authorizationTimeout = Duration.ofSeconds(3);

        /** 创建 Provider Builder。 */
        public Builder() {
        }

        /**
         * 显式设置宿主安全要求，未指定不能创建运行资源。
         *
         * @param value 非 null 安全 Profile
         * @return Provider Builder
         */
        public Builder securityProfile(RpcSecurityProfile value) {
            this.securityProfile = Objects.requireNonNull(value, "securityProfile");
            return this;
        }

        /**
         * 设置从原始接收时刻起的最大授权许可期限。
         *
         * @param value 严格正数且最多一天
         * @return Provider Builder
         */
        public Builder maxAuthorizationGrantLifetime(Duration value) {
            RpcSecurityChecks.positiveFinite(value, "maxAuthorizationGrantLifetime");
            if (value.compareTo(Duration.ofDays(1)) > 0) {
                throw new IllegalArgumentException("maxAuthorizationGrantLifetime must not exceed one day");
            }
            this.maxAuthorizationGrantLifetime = value;
            return this;
        }

        Builder identityClock(Clock clock, LongSupplier nanoClock) {
            this.identityClock = Objects.requireNonNull(clock, "identityClock");
            this.identityNanoClock = Objects.requireNonNull(nanoClock, "identityNanoClock");
            return this;
        }

        /**
         * 设置同一进程共享的应用级成员控制面。
         *
         * @param value 同一个进程 Provider/Consumer 共享的成员协调器
         * @return Provider Builder
         */
        public Builder applicationMembership(ApplicationMembershipCoordinator value) {
            this.membership = Objects.requireNonNull(value, "value");
            return this;
        }

        /**
         * 设置 Transport Server。
         *
         * @param value Transport Server
         * @return Provider Builder
         */
        public Builder transportServer(RpcTransportServer value) {
            this.transport = value;
            return this;
        }

        /**
         * 设置 Codec 注册表。
         *
         * @param value Codec 注册表
         * @return Provider Builder
         */
        public Builder codecRegistry(RpcCodecRegistry value) {
            this.codecs = value;
            return this;
        }

        /**
         * 设置 Provider 绑定端点。
         *
         * @param value Provider 绑定端点
         * @return Provider Builder
         */
        public Builder bindEndpoint(RpcEndpoint value) {
            this.bind = value;
            return this;
        }

        /**
         * 设置向 Registry 发布的 Provider 主机地址。
         *
         * @param value 发布主机地址
         * @return Provider Builder
         */
        public Builder advertisedHost(String value) {
            this.advertisedHost = value == null
                    ? null
                    : value.trim();
            return this;
        }

        /**
         * 设置向 Registry 发布的 Provider 端口。
         *
         * @param value 发布端口，0 表示使用实际监听端口
         * @return Provider Builder
         */
        public Builder advertisedPort(int value) {
            if (value < 0 || value > 65_535) {
                throw new IllegalArgumentException(
                        "advertisedPort must be between 0 and 65535");
            }
            this.advertisedPort = value;
            return this;
        }

        /**
         * 设置 Provider 最大并发业务请求数。
         *
         * @param value 最大并发业务请求数
         * @return Provider Builder
         */
        public Builder maxConcurrent(int value) {
            if (value <= 0) {
                throw new IllegalArgumentException(
                        "maxConcurrent must be positive");
            }
            this.maxConcurrent = value;
            return this;
        }

        /**
         * 设置 Provider 分层并发与在途请求 Frame 字节预算。
         *
         * @param value Provider Admission 策略
         * @return Provider Builder
         */
        public Builder admissionOptions(RpcProviderAdmissionOptions value) {
            this.admissionOptions = Objects.requireNonNull(
                    value,
                    "admissionOptions");
            return this;
        }

        /**
         * 设置单实例编码缓冲累计额度及单个 Payload 硬输出上限。
         *
         * <p>使用渐进增长的框架缓冲租约，不按最大 Payload 预留每次小结果。
         *
         * @param totalBytes 全部并发编码操作允许保留的缓冲总字节数
         * @param singleBytes 单个响应的最大输出字节数
         * @return Provider Builder
         */
        public Builder responseEncodingLimits(long totalBytes, int singleBytes) {
            if (totalBytes <= 0L || singleBytes <= 0 || singleBytes > totalBytes) {
                throw new IllegalArgumentException("Invalid RPC response encoding limits");
            }
            this.maxBufferedEncodingBytes = totalBytes;
            this.maxSingleResponseBytes = singleBytes;
            return this;
        }

        /**
         * 设置 Provider 优雅排空超时时间。
         *
         * @param value 最大排空时间
         * @return Provider Builder
         */
        public Builder drainTimeout(Duration value) {
            if (value == null || value.isNegative() || value.isZero()) {
                throw new IllegalArgumentException(
                        "drainTimeout must be positive");
            }
            this.drainTimeout = value;
            return this;
        }

        /**
         * 设置 Registry 等控制面操作的最大等待时间。
         *
         * @param value 控制面操作超时时间
         * @return Provider Builder
         */
        public Builder controlPlaneTimeout(Duration value) {
            if (value == null || value.isNegative() || value.isZero()) {
                throw new IllegalArgumentException(
                        "controlPlaneTimeout must be positive");
            }
            this.controlPlaneTimeout = value;
            return this;
        }

        /**
         * 设置 Provider 业务执行资源策略。
         *
         * @param value 执行资源配置
         * @return Provider Builder
         */
        public Builder executionOptions(RpcProviderExecutionOptions value) {
            this.executionOptions = Objects.requireNonNull(
                    value,
                    "executionOptions");
            return this;
        }

        /**
         * 指定仅在真实对端身份与方法授权后运行的 Provider 拦截器集合。
         *
         * @param value 不含 null 的扩展集合；空集合禁用扩展
         * @return Provider Builder
         */
        public Builder interceptors(List<RpcInterceptor> value) {
            this.interceptors = List.copyOf(Objects.requireNonNull(value, "interceptors"));
            return this;
        }

        /**
         * 设置专属 Hook Worker 数量与整条调用链的并发额度。
         *
         * @param workers 有界 Worker 数
         * @param maxActive 最多同时保留的 Hook Session 数
         * @return Provider Builder
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
         * 设置服务和方法级非阻塞授权策略。
         *
         * @param value 授权策略
         * @return Provider Builder
         */
        public Builder authorizer(RpcAuthorizer value) {
            this.authorizer = Objects.requireNonNull(value, "authorizer");
            return this;
        }

        /**
         * 设置同时等待真实策略完成的最大授权数。
         *
         * @param value 挂起授权容量
         * @return Provider Builder
         */
        public Builder maxPendingAuthorizations(int value) {
            if (value <= 0) {
                throw new IllegalArgumentException("maxPendingAuthorizations must be positive");
            }
            this.maxPendingAuthorizations = value;
            return this;
        }

        /**
         * 设置授权等待阶段全局原始帧的硬字节预算。
         *
         * @param value 等待授权阶段最多保留的请求帧总字节数
         * @return Provider Builder
         */
        public Builder maxPendingAuthorizationBytes(long value) {
            if (value <= 0L) {
                throw new IllegalArgumentException(
                        "maxPendingAuthorizationBytes must be positive");
            }
            this.maxPendingAuthorizationBytes = value;
            return this;
        }

        /**
         * 设置授权逻辑超时，不会强制结束第三方任务。
         *
         * @param value 最大授权等待时间
         * @return Provider Builder
         */
        public Builder authorizationTimeout(Duration value) {
            if (value == null || value.isNegative() || value.isZero()
                    || value.compareTo(Duration.ofDays(1)) > 0) {
                throw new IllegalArgumentException("authorizationTimeout must be positive and finite");
            }
            this.authorizationTimeout = value;
            return this;
        }

        /**
         * 设置 Provider 可观测性 Observer。
         *
         * @param value Observer
         * @return Provider Builder
         */
        public Builder observer(RpcObserver value) {
            this.observer = Objects.requireNonNull(value, "observer");
            return this;
        }

        /**
         * 设置 RPC Metadata Propagator。
         *
         * @param value Metadata Propagator
         * @return Provider Builder
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
         * @return Provider Builder
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
         * 只读校验当前实际组件，全部通过后才能创建运行时或发布成员。
         */
        public void validate() {
            RpcSecurityChecks.profile(securityProfile);
            Objects.requireNonNull(membership, "applicationMembership");
            Objects.requireNonNull(transport, "transportServer");
            Objects.requireNonNull(codecs, "codecRegistry");
            Objects.requireNonNull(bind, "bindEndpoint");
            Objects.requireNonNull(admissionOptions, "admissionOptions");
            Objects.requireNonNull(executionOptions, "executionOptions");
            Objects.requireNonNull(observer, "observer");
            Objects.requireNonNull(metadataPropagator, "metadataPropagator");
            Objects.requireNonNull(tracingBridge, "tracingBridge");
            RpcSecurityChecks.transport(securityProfile, transport::securityDescriptor,
                    membership.application(), codecs, false, identityClock.instant());
            RpcSecurityChecks.codecs(securityProfile, codecs);
            RpcSecurityChecks.registry(securityProfile, membership::securityDescriptor,
                    membership.application().namespace());
            if (securityProfile == RpcSecurityProfile.NETWORK_MTLS && authorizer == null) {
                throw new IllegalStateException("NETWORK_MTLS Provider requires an explicit RpcAuthorizer");
            }
            RpcSecurityChecks.positiveFinite(drainTimeout, "drainTimeout");
            RpcSecurityChecks.positiveFinite(controlPlaneTimeout, "controlPlaneTimeout");
            RpcSecurityChecks.positiveFinite(authorizationTimeout, "authorizationTimeout");
            RpcSecurityChecks.positiveFinite(maxAuthorizationGrantLifetime, "maxAuthorizationGrantLifetime");
            if (maxConcurrent <= 0 || maxPendingAuthorizations <= 0 || maxPendingAuthorizationBytes <= 0
                    || maxAuthorizationGrantLifetime.compareTo(Duration.ofDays(1)) > 0) {
                throw new IllegalArgumentException("Invalid Provider authorization or admission capacity");
            }
        }

        /**
         * 创建 Provider 运行时。
         *
         * @return Provider 运行时
         */
        public OtryxRpcServer build() {
            validate();
            return new OtryxRpcServer(this);
        }
    }
}
