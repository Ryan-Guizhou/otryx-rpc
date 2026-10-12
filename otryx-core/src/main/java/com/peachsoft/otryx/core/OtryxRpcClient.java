package com.peachsoft.otryx.core;

import com.peachsoft.otryx.api.OtryxRpcIdempotent;
import com.peachsoft.otryx.api.RpcEndpoint;
import com.peachsoft.otryx.api.RpcSchemaFingerprint;
import com.peachsoft.otryx.api.RpcException;
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
import com.peachsoft.otryx.generated.RpcGeneratedInvocation;
import com.peachsoft.otryx.loadbalance.LoadBalanceMetrics;
import com.peachsoft.otryx.loadbalance.LoadBalancer;
import com.peachsoft.otryx.observability.RpcMetadataPropagator;
import com.peachsoft.otryx.observability.RpcObserver;
import com.peachsoft.otryx.observability.RpcRetryExhaustionReason;
import com.peachsoft.otryx.observability.RpcTraceContext;
import com.peachsoft.otryx.observability.RpcTracingBridge;
import com.peachsoft.otryx.protocol.RpcErrorCodec;
import com.peachsoft.otryx.protocol.RpcFrameView;
import com.peachsoft.otryx.protocol.RpcProtocolCodec;
import com.peachsoft.otryx.proxy.ProxyFactory;
import com.peachsoft.otryx.registry.ServiceDiscovery;
import com.peachsoft.otryx.spi.ExtensionLoader;
import com.peachsoft.otryx.transport.RpcTransportClient;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

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
    private final ServiceDiscovery discovery;
    private final RpcTransportClient transport;
    private final RpcCodecRegistry codecs;
    private final byte defaultCodecId;
    private final LoadBalancer loadBalancer;
    private final ProxyFactory proxyFactory;
    private final Duration timeout;
    private final RpcClientResilienceOptions resilienceOptions;
    private final RetryBudget retryBudget;
    private final ThreadPoolExecutor responseCompletionExecutor;
    private final Semaphore responseCompletionPermits;
    private final ScheduledThreadPoolExecutor deadlineScheduler;
    private final RpcObserver observer;
    private final RpcMetadataPropagator metadataPropagator;
    private final RpcTracingBridge tracingBridge;
    private final ConcurrentMap<ServiceKey, ServiceDirectory> directories =
            new ConcurrentHashMap<>();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Set<CompletableFuture<Object>> pendingCalls =
            ConcurrentHashMap.newKeySet();
    private final ConcurrentMap<RpcEndpoint, EndpointStats> stats =
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
        this.discovery = Objects.requireNonNull(builder.discovery, "serviceDiscovery");
        this.transport = Objects.requireNonNull(builder.transport, "transportClient");
        this.codecs = Objects.requireNonNull(builder.codecs, "codecRegistry");
        this.defaultCodecId = codecs.defaultCodec().code();
        this.loadBalancer = builder.loadBalancer != null
                ? builder.loadBalancer
                : ExtensionLoader.getLoader(LoadBalancer.class).getDefaultExtension();
        this.proxyFactory = builder.proxyFactory != null
                ? builder.proxyFactory
                : ExtensionLoader.getLoader(ProxyFactory.class).getDefaultExtension();
        this.timeout = Objects.requireNonNull(builder.timeout, "timeout");
        this.resilienceOptions = Objects.requireNonNull(
                builder.resilienceOptions,
                "resilienceOptions");
        this.retryBudget = new RetryBudget(resilienceOptions);
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
        ServiceDirectory directory = directories.computeIfAbsent(
                key,
                ignored -> new ServiceDirectory(
                        discovery,
                        key,
                        schemaFingerprint));
        ClientReference reference = ClientReference.create(
                key,
                api,
                directory,
                codecs,
                defaultCodecId,
                resilienceOptions);

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
        CompletableFuture<Object> result =
                new CompletableFuture<>();
        long[] circuitGeneration = {RpcCircuitBreaker.REJECTED};
        result.whenComplete((ignoredValue, error) -> {
            pendingCalls.remove(result);
            responseCompletionPermits.release();
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
                    method.circuitBreaker().onCancelled(
                            circuitGeneration[0]);
                    failure = new CancellationException(
                            "RPC call cancelled");
                    finalStatus = RpcStatus.UNAVAILABLE;
                } else {
                    failure = error == null ? null : unwrap(error);
                    finalStatus = failure == null
                            ? RpcStatus.OK
                            : statusOf(failure);
                    if (failure == null
                            || (failure instanceof RpcRemoteException
                                    && !isRetryable(failure))) {
                        method.circuitBreaker().onSuccess(
                                circuitGeneration[0]);
                    } else {
                        method.circuitBreaker().onFailure(
                                circuitGeneration[0]);
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
            Map<String, String> propagatedMetadata =
                    propagatedMetadata(trace);

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

            long deadlineNanos =
                    callStartedAtNanos + timeout.toNanos();
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
            attempt(
                    reference,
                    method,
                    encodedArguments,
                    propagatedMetadata,
                    deadlineNanos,
                    1,
                    result);
            return result;
        } catch (RuntimeException | Error setupError) {
            result.completeExceptionally(setupError);
            return result;
        }
    }

    private Map<String, String> propagatedMetadata(
            RpcTraceContext trace) {
        if (!tracingBridge.enabled()
                && !metadataPropagator.enabled()) {
            return Map.of();
        }
        Map<String, String> metadata =
                new LinkedHashMap<>();
        metadata.putAll(trace.metadata());
        if (metadataPropagator.enabled()) {
            metadataPropagator.inject(metadata);
        }
        return metadata.isEmpty()
                ? Map.of()
                : Map.copyOf(metadata);
    }

    private void attempt(
            ClientReference reference,
            ClientMethodBinding method,
            byte[] encodedArguments,
            Map<String, String> propagatedMetadata,
            long deadlineNanos,
            int attempt,
            CompletableFuture<Object> result) {
        if (result.isDone()) {
            return;
        }
        if (closed.get()) {
            result.completeExceptionally(
                    new RpcUnavailableException("RPC client is closed"));
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
                    timeoutError);
            result.completeExceptionally(timeoutError);
            return;
        }

        ServiceInstance[] instances = reference.directory().snapshot();
        ServiceInstance selected = instances.length == 0
                ? null
                : loadBalancer.select(instances, loadMetrics);
        if (selected == null) {
            RpcUnavailableException unavailable =
                    new RpcUnavailableException(
                            "No available instance for "
                                    + reference.key().canonicalName());
            observeClientAttempt(
                    reference,
                    method,
                    null,
                    attempt,
                    0L,
                    RpcStatus.UNAVAILABLE,
                    unavailable);
            retryOrComplete(
                    reference,
                    method,
                    encodedArguments,
                    propagatedMetadata,
                    deadlineNanos,
                    attempt,
                    result,
                    unavailable);
            return;
        }

        EndpointStats endpointStats = stats.compute(
                selected.endpoint(),
                (endpoint, existing) -> {
                    EndpointStats current =
                            existing == null ? new EndpointStats() : existing;
                    current.begin();
                    return current;
                });
        long startedAtNanos = System.nanoTime();

        long remainingMillis = Math.max(
                1L,
                TimeUnit.NANOSECONDS.toMillis(remainingNanos));
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

        CompletableFuture<byte[]> transportFuture = transport.request(
                        selected.endpoint(),
                        request,
                        Duration.ofNanos(remainingNanos))
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
                endpointStats.endCancelled(System.nanoTime() - startedAtNanos);
                return;
            }
            try {
                responseCompletionExecutor.execute(() ->
                        completeTransportResponse(
                                reference,
                                method,
                                encodedArguments,
                                propagatedMetadata,
                                deadlineNanos,
                                attempt,
                                result,
                                selected,
                                endpointStats,
                                startedAtNanos,
                                rawResponse,
                                transportError));
            } catch (RejectedExecutionException rejected) {
                // 容量在发起请求前预留。只有执行器关闭或竞争才可能触发这里。
                endpointStats.endCancelled(
                        System.nanoTime() - startedAtNanos);
                result.completeExceptionally(
                        new RpcOverloadedException(
                                "RPC response completion executor unavailable"));
            }
        });
    }

    private void completeTransportResponse(
            ClientReference reference,
            ClientMethodBinding method,
            byte[] encodedArguments,
            Map<String, String> propagatedMetadata,
            long deadlineNanos,
            int attempt,
            CompletableFuture<Object> result,
            ServiceInstance selected,
            EndpointStats endpointStats,
            long startedAtNanos,
            byte[] rawResponse,
            Throwable transportError) {
            long elapsed = System.nanoTime() - startedAtNanos;
            if (result.isDone()) {
                endpointStats.endCancelled(elapsed);
                return;
            }
            if (transportError != null) {
                Throwable failure = unwrap(transportError);
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
                        failure);
                retryOrComplete(
                        reference,
                        method,
                        encodedArguments,
                        propagatedMetadata,
                        deadlineNanos,
                        attempt,
                        result,
                        failure);
                return;
            }

            try {
                Object value = decodeResponse(method, rawResponse);
                endpointStats.endSuccess(elapsed);
                observeClientAttempt(
                        reference,
                        method,
                        selected.endpoint(),
                        attempt,
                        elapsed,
                        RpcStatus.OK,
                        null);
                result.complete(value);
            } catch (RpcRemoteException remoteError) {
                if (isRetryable(remoteError)) {
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
                            remoteError);
                    retryOrComplete(
                            reference,
                            method,
                            encodedArguments,
                            propagatedMetadata,
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
                            remoteError);
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
                        error);
                retryOrComplete(
                        reference,
                        method,
                        encodedArguments,
                        propagatedMetadata,
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
            long deadlineNanos,
            int attempt,
            CompletableFuture<Object> result,
            Throwable failure) {
        if (result.isDone()) {
            return;
        }
        boolean retryable =
                method.idempotent()
                        && isRetryable(failure);
        if (!retryable) {
            result.completeExceptionally(failure);
            return;
        }
        if (attempt >= resilienceOptions.maxAttempts()) {
            observeRetryExhausted(
                    reference,
                    method,
                    RpcRetryExhaustionReason.MAX_ATTEMPTS,
                    failure);
            result.completeExceptionally(failure);
            return;
        }
        if (!retryBudget.tryAcquireRetry()) {
            observeRetryExhausted(
                    reference,
                    method,
                    RpcRetryExhaustionReason.BUDGET,
                    failure);
            result.completeExceptionally(failure);
            return;
        }

        long remainingNanos =
                deadlineNanos - System.nanoTime();
        long delayMillis = retryDelayMillis(attempt);
        if (remainingNanos
                <= TimeUnit.MILLISECONDS.toNanos(delayMillis)) {
            observeRetryExhausted(
                    reference,
                    method,
                    RpcRetryExhaustionReason.DEADLINE,
                    failure);
            result.completeExceptionally(failure);
            return;
        }
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
                    () -> attempt(
                            reference,
                            method,
                            encodedArguments,
                            propagatedMetadata,
                            deadlineNanos,
                            attempt + 1,
                            result),
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
            Throwable error) {
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

    private static RpcStatus statusOf(Throwable error) {
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

    private long retryDelayMillis(int attempt) {
        long base = resilienceOptions.retryBaseBackoff().toMillis();
        long max = resilienceOptions.retryMaxBackoff().toMillis();
        if (base == 0L || max == 0L) {
            return 0L;
        }
        int shift = Math.min(Math.max(attempt - 1, 0), 20);
        long ceiling = base > (Long.MAX_VALUE >> shift)
                ? max
                : Math.min(max, base << shift);
        return ceiling <= 1L
                ? ceiling
                : ThreadLocalRandom.current().nextLong(ceiling + 1L);
    }

    private static boolean isRetryable(Throwable error) {
        if (error instanceof RpcUnavailableException
                || error instanceof RpcOverloadedException) {
            return true;
        }
        if (error instanceof RpcRemoteException remote) {
            return remote.status() == RpcStatus.UNAVAILABLE
                    || remote.status() == RpcStatus.OVERLOADED
                    || remote.status() == RpcStatus.INTERNAL_ERROR;
        }
        return false;
    }

    private static Throwable unwrap(Throwable error) {
        if (error instanceof CompletionException
                && error.getCause() != null) {
            return error.getCause();
        }
        return error;
    }

    /**
     * 依据注册中心最新快照清退已经不存在且无在途请求的端点统计。
     *
     * <p>延迟清退发生在控制面线程，不进入请求热路径。
     */
    void evictUnusedEndpointStats() {
        Set<RpcEndpoint> activeEndpoints = new HashSet<>();
        for (ServiceDirectory directory : directories.values()) {
            for (ServiceInstance instance : directory.snapshot()) {
                activeEndpoints.add(instance.endpoint());
            }
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
                instance.endpoint(),
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

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        // 先结算全部逻辑调用，避免关闭调度器后延迟重试永远无法完成。
        pendingCalls.forEach(call -> call.completeExceptionally(
                new RpcUnavailableException("RPC client is closed")));
        try {
            directories.values().forEach(directory -> {
                try {
                    directory.close();
                } catch (RuntimeException error) {
                    LOGGER.warn(
                            "RPC service directory close failed: errorType={}",
                            error.getClass().getName());
                }
            });
            try {
                transport.close();
            } catch (RuntimeException error) {
                LOGGER.warn(
                        "RPC transport close failed: errorType={}",
                        error.getClass().getName());
            }
        } finally {
            responseCompletionExecutor.shutdownNow();
            deadlineScheduler.shutdownNow();
        }
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
                RpcClientResilienceOptions resilienceOptions) {
            Map<Method, ClientMethodBinding> methods = new HashMap<>();
            Map<Integer, ClientMethodBinding> methodsById = new HashMap<>();
            for (Method method : api.getMethods()) {
                RpcMethodDescriptor descriptor = RpcMethodDescriptor.from(key, method);
                ClientMethodBinding binding = new ClientMethodBinding(
                        descriptor.methodId(),
                        codecs.bind(descriptor, codecId),
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
        private ServiceDiscovery discovery;
        private RpcTransportClient transport;
        private RpcCodecRegistry codecs;
        private LoadBalancer loadBalancer;
        private ProxyFactory proxyFactory;
        private Duration timeout = Duration.ofSeconds(3);
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
         * 设置服务发现控制面。
         *
         * @param value 服务发现控制面
         * @return Consumer Builder
         */
        public Builder serviceDiscovery(ServiceDiscovery value) {
            this.discovery = value;
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
         * 创建 Consumer 运行时。
         *
         * @return Consumer 运行时
         */
        public OtryxRpcClient build() {
            return new OtryxRpcClient(this);
        }
    }
}
