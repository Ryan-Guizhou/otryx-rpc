package com.peachsoft.otryx.core;

import com.peachsoft.otryx.api.RpcCompatibilityMetadata;
import com.peachsoft.otryx.api.RpcEndpoint;
import com.peachsoft.otryx.api.RpcException;
import com.peachsoft.otryx.api.RpcExecutionMode;
import com.peachsoft.otryx.api.RpcIds;
import com.peachsoft.otryx.api.RpcRemoteError;
import com.peachsoft.otryx.api.RpcStatus;
import com.peachsoft.otryx.api.ServiceInstance;
import com.peachsoft.otryx.api.ServiceKey;
import com.peachsoft.otryx.codec.RpcCodecRegistry;
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
import com.peachsoft.otryx.registry.ServiceRegistrar;
import com.peachsoft.otryx.transport.RpcTransportServer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * OTRYX RPC Provider 运行时，协调服务注册、请求准入、执行与响应。
 *
 * <p>Provider 启动会先监听网络端点、注册服务实例，随后进入可处理请求的
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

    private final ServiceRegistrar registrar;
    private final RpcTransportServer transport;
    private final RpcCodecRegistry codecs;
    private final RpcEndpoint bindEndpoint;
    private final String advertisedHost;
    private final int advertisedPort;
    private final int maxConcurrent;
    private final RpcProviderAdmissionOptions admissionOptions;
    private volatile ProviderAdmissionController admission;
    private final Duration drainTimeout;
    private final Duration controlPlaneTimeout;
    private final RpcProviderExecutionOptions executionOptions;
    private final RpcObserver observer;
    private final RpcMetadataPropagator metadataPropagator;
    private final RpcTracingBridge tracingBridge;
    private final ExecutorService blockingExecutor =
            Executors.newVirtualThreadPerTaskExecutor();
    private final ThreadPoolExecutor cpuExecutor;
    private final ConcurrentMap<Integer, ServiceBinding> bindings =
            new ConcurrentHashMap<>();
    private final List<ServiceInstance> configuredInstances =
            new CopyOnWriteArrayList<>();
    private final AtomicReference<State> state =
            new AtomicReference<>(State.NEW);

    private volatile RpcEndpoint actualEndpoint;
    private volatile RpcEndpoint advertisedEndpoint;

    private OtryxRpcServer(Builder builder) {
        this.registrar = Objects.requireNonNull(
                builder.registrar,
                "serviceRegistrar");
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
        this.metadataPropagator = Objects.requireNonNull(
                builder.metadataPropagator,
                "metadataPropagator");
        this.tracingBridge = Objects.requireNonNull(
                builder.tracingBridge,
                "tracingBridge");
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
                new ServiceBinding(key, api, implementation, codecs);
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
        configuredInstances.add(new ServiceInstance(
                UUID.randomUUID().toString(),
                key,
                bindEndpoint,
                100,
                RpcCompatibilityMetadata.providerMetadata(
                        key,
                        api)));
        return this;
    }

    /**
     * 启动 TCP Server 并注册所有服务实例。
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
            Map<Integer, Set<Integer>> methodsByService = new HashMap<>();
            bindings.forEach((serviceId, binding) ->
                    methodsByService.put(serviceId, binding.methodIds()));
            admission = new ProviderAdmissionController(
                    maxConcurrent,
                    admissionOptions,
                    methodsByService);
        } catch (RuntimeException error) {
            state.set(State.CLOSED);
            return CompletableFuture.failedFuture(error);
        }

        CompletableFuture<RpcEndpoint> started = new CompletableFuture<>();
        transport.start(bindEndpoint, this::handle)
                .whenComplete((endpoint, transportError) -> {
                    if (transportError != null) {
                        state.set(State.CLOSED);
                        started.completeExceptionally(transportError);
                        return;
                    }
                    actualEndpoint = endpoint;
                    RpcEndpoint published;
                    try {
                        published = resolveAdvertisedEndpoint(endpoint);
                    } catch (RuntimeException error) {
                        state.set(State.CLOSED);
                        transport.close();
                        started.completeExceptionally(error);
                        return;
                    }
                    advertisedEndpoint = published;
                    registerConfiguredServices(published)
                            .whenComplete((ignored, registryError) -> {
                                if (registryError != null) {
                                    rollbackRegisteredServices(published)
                                            .whenComplete((rollbackIgnored, rollbackError) -> {
                                                state.set(State.CLOSED);
                                                transport.close();
                                                if (rollbackError != null) {
                                                    registryError.addSuppressed(rollbackError);
                                                }
                                                started.completeExceptionally(registryError);
                                            });
                                    return;
                                }
                                state.set(State.STARTED);
                                LOGGER.info(
                                        "OTRYX RPC server started: bind={}, advertised={}",
                                        endpoint.authority(),
                                        published.authority());
                                started.complete(endpoint);
                            });
                });
        return started;
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

    private CompletionStage<Void> registerConfiguredServices(
            RpcEndpoint endpoint) {
        List<CompletableFuture<Void>> registrations = new ArrayList<>();
        for (ServiceInstance configured : configuredInstances) {
            registrations.add(registrar.register(
                            runtimeInstance(configured, endpoint))
                    .toCompletableFuture());
        }
        return CompletableFuture.allOf(
                        registrations.toArray(CompletableFuture[]::new))
                .orTimeout(
                        controlPlaneTimeout.toMillis(),
                        TimeUnit.MILLISECONDS);
    }

    private CompletionStage<Void> rollbackRegisteredServices(
            RpcEndpoint endpoint) {
        List<CompletableFuture<Void>> rollbacks = new ArrayList<>();
        for (ServiceInstance configured : configuredInstances) {
            CompletableFuture<Void> rollback = registrar
                    .unregister(runtimeInstance(configured, endpoint))
                    .exceptionally(error -> {
                        LOGGER.warn(
                                "Failed to rollback RPC service registration: service={}, errorType={}",
                                configured.serviceKey().canonicalName(),
                                error.getClass().getName());
                        return null;
                    })
                    .toCompletableFuture();
            rollbacks.add(rollback);
        }
        return CompletableFuture.allOf(
                        rollbacks.toArray(CompletableFuture[]::new))
                .orTimeout(
                        controlPlaneTimeout.toMillis(),
                        TimeUnit.MILLISECONDS);
    }

    private CompletionStage<byte[]> handle(
            RpcEndpoint remote,
            byte[] rawFrame) {
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
        if (timeoutBudgetMillis <= 0L
                && deadline > 0L
                && System.currentTimeMillis() > deadline) {
            return CompletableFuture.completedFuture(
                    frameworkError(
                            request,
                            RpcStatus.DEADLINE_EXCEEDED,
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
        result.whenComplete((ignoredValue, ignoredError) ->
                releaseIfComplete.run());
        if (observed) {
            observer.onServerInflightChanged(1);
            observer.onServerInflightBytesChanged(request.bytes().length);
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

        if (executionMode == RpcExecutionMode.DIRECT) {
            execute(
                    request,
                    binding,
                    methodCodec,
                    propagatedMetadata,
                    trace,
                    result,
                    cpuExecutor,
                    markWorkFinished);
            return result;
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
            return result;
        }
        result.whenComplete((ignoredValue, ignoredError) -> {
            if (result.isCancelled()
                    && task.cancel(true)
                    && executionClaimed.compareAndSet(false, true)) {
                // Runnable 未进入业务代码；被取消的排队任务不会再执行。
                markWorkFinished.run();
            }
        });
        return result;
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
            CompletableFuture<byte[]> result,
            ExecutorService asyncCompletionExecutor,
            Runnable markWorkFinished) {
        boolean deferredCompletion = false;
        try (RpcMetadataScope traceScope =
                     trace.makeCurrent();
             RpcMetadataScope propagationScope =
                     metadataPropagator.enabled()
                             ? metadataPropagator.extract(
                                     propagatedMetadata)
                             : RpcMetadataScope.noop()) {
            if (result.isCancelled()) {
                return;
            }
            Object[] arguments = methodCodec.decodeArguments(
                    request.bytes(),
                    request.payloadOffset(),
                    request.payloadLength());
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
                        markWorkFinished);
                return;
            }
            byte[] responseBytes = response(
                    request,
                    methodCodec.codecId(),
                    RpcStatus.OK,
                    methodCodec.encodeResult(value));
            completeResponse(result, responseBytes);
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
            Runnable markWorkFinished) {
        try {
            CompletableFuture<?> asyncFuture = stage.toCompletableFuture();
            boolean completedInline = asyncFuture.isDone();
            // 不主动取消业务 Stage：CompletableFuture.cancel 仅保证 Future
            // 逻辑终态，不保证业务的底层 IO 或线程已经结束。
            asyncFuture.whenComplete((value, error) -> {
                if (result.isCancelled()) {
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
            Object value,
            Throwable error,
            Runnable markWorkFinished) {
        if (result.isCancelled()) {
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
            Object value,
            Throwable error) {
        try (RpcMetadataScope traceScope =
                     trace.makeCurrent();
             RpcMetadataScope propagationScope =
                     metadataPropagator.enabled()
                             ? metadataPropagator.extract(
                                     propagatedMetadata)
                             : RpcMetadataScope.noop()) {
            if (result.isCancelled()) {
                return;
            }
            if (error != null) {
                completeInvocationFailure(
                        request,
                        result,
                        unwrapCompletionFailure(error));
                return;
            }
            byte[] responseBytes = response(
                    request,
                    methodCodec.codecId(),
                    RpcStatus.OK,
                    methodCodec.encodeResult(value));
            completeResponse(
                    result,
                    responseBytes);
        } catch (Throwable completionError) {
            completeInvocationFailure(
                    request,
                    result,
                    completionError);
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

    private void completeInvocationFailure(
            RpcFrameView request,
            CompletableFuture<byte[]> result,
            Throwable error) {
        if (result.isCancelled()) {
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

    private static ServiceInstance runtimeInstance(
            ServiceInstance configured,
            RpcEndpoint endpoint) {
        return new ServiceInstance(
                configured.instanceId(),
                configured.serviceKey(),
                endpoint,
                configured.weight(),
                configured.metadata());
    }

    @Override
    public void close() {
        State current = state.get();
        if (current == State.CLOSED) {
            return;
        }

        boolean drain = current == State.STARTED
                && state.compareAndSet(State.STARTED, State.DRAINING);
        if (!drain) {
            state.set(State.CLOSED);
        }

        RpcEndpoint endpoint = advertisedEndpoint;
        if (endpoint != null) {
            for (ServiceInstance configured : configuredInstances) {
                try {
                    registrar.unregister(
                                    runtimeInstance(configured, endpoint))
                            .toCompletableFuture()
                            .orTimeout(
                                    controlPlaneTimeout.toMillis(),
                                    TimeUnit.MILLISECONDS)
                            .join();
                } catch (RuntimeException error) {
                    LOGGER.warn(
                            "Failed to unregister RPC service: service={}, errorType={}",
                            configured.serviceKey().canonicalName(),
                            error.getClass().getName());
                }
            }
        }

        if (drain) {
            try {
                transport.drain(drainTimeout)
                        .toCompletableFuture()
                        .join();
            } catch (RuntimeException error) {
                LOGGER.warn(
                        "RPC graceful drain failed; forcing transport close. errorType={}",
                        error.getClass().getName());
            } finally {
                state.set(State.CLOSED);
            }
        }
        transport.close();
        blockingExecutor.close();
        cpuExecutor.shutdownNow();
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
        private ServiceRegistrar registrar;
        private RpcTransportServer transport;
        private RpcCodecRegistry codecs;
        private RpcEndpoint bind;
        private String advertisedHost;
        private int advertisedPort;
        private int maxConcurrent = 4096;
        private RpcProviderAdmissionOptions admissionOptions =
                RpcProviderAdmissionOptions.DEFAULT;
        private Duration drainTimeout = Duration.ofSeconds(30);
        private Duration controlPlaneTimeout = Duration.ofSeconds(3);
        private RpcProviderExecutionOptions executionOptions =
                RpcProviderExecutionOptions.DEFAULT;
        private RpcObserver observer = RpcObserver.noop();
        private RpcMetadataPropagator metadataPropagator =
                RpcMetadataPropagator.noop();
        private RpcTracingBridge tracingBridge =
                RpcTracingBridge.noop();

        /** 创建 Provider Builder。 */
        public Builder() {
        }

        /**
         * 设置 Provider 服务注册控制面。
         *
         * @param value 服务注册控制面
         * @return Provider Builder
         */
        public Builder serviceRegistrar(ServiceRegistrar value) {
            this.registrar = value;
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
         * 创建 Provider 运行时。
         *
         * @return Provider 运行时
         */
        public OtryxRpcServer build() {
            return new OtryxRpcServer(this);
        }
    }
}
