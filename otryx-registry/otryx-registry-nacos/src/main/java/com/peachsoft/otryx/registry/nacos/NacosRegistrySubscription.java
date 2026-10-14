package com.peachsoft.otryx.registry.nacos;

import com.alibaba.nacos.api.naming.NamingService;
import com.alibaba.nacos.api.naming.listener.Event;
import com.alibaba.nacos.api.naming.listener.EventListener;
import com.alibaba.nacos.api.naming.listener.NamingEvent;
import com.alibaba.nacos.api.naming.pojo.Instance;
import com.peachsoft.otryx.api.ServiceInstance;
import com.peachsoft.otryx.api.ServiceKey;
import com.peachsoft.otryx.observability.RpcObserver;
import com.peachsoft.otryx.observability.RpcRegistryOperation;
import com.peachsoft.otryx.registry.RegistryListener;
import com.peachsoft.otryx.registry.RegistrySnapshot;
import com.peachsoft.otryx.registry.RegistrySubscription;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 单个 Nacos 服务订阅的生命周期与有序快照发布器。
 *
 * @Author Ryan
 * @Version 1.0.0
 * @CreateTime 2026/9/28 11:35
 */
final class NacosRegistrySubscription implements RegistrySubscription {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(NacosRegistrySubscription.class);

    private final NamingService namingService;
    private final NacosControlExecutor executor;
    private final ServiceKey serviceKey;
    private final String serviceName;
    private final String group;
    private final String cluster;
    private final RegistryListener listener;
    private final AtomicLong revision;
    private final RpcObserver observer;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean starting = new AtomicBoolean();
    private final AtomicBoolean unhealthy = new AtomicBoolean();
    private final Object eventMonitor = new Object();
    private final Deque<List<Instance>> eventQueue = new ArrayDeque<>();
    private final List<List<Instance>> preInitialEvents = new ArrayList<>();
    private final EventListener eventListener = this::onEvent;
    private boolean initialized;
    private boolean draining;
    private long eventSequence;
    private List<ServiceInstance> lastSnapshot;

    NacosRegistrySubscription(
            NamingService namingService,
            NacosControlExecutor executor,
            ServiceKey serviceKey,
            String group,
            String cluster,
            RegistryListener listener,
            AtomicLong revision,
            RpcObserver observer) {
        this.namingService = Objects.requireNonNull(
                namingService,
                "namingService");
        this.executor = Objects.requireNonNull(
                executor,
                "executor");
        this.serviceKey = Objects.requireNonNull(
                serviceKey,
                "serviceKey");
        this.serviceName = NacosServiceNames.serviceName(serviceKey);
        this.group = Objects.requireNonNull(group, "group");
        this.cluster = Objects.requireNonNull(cluster, "cluster");
        this.listener = Objects.requireNonNull(listener, "listener");
        this.revision = Objects.requireNonNull(revision, "revision");
        this.observer = observer == null
                ? RpcObserver.noop()
                : observer;
    }

    /**
     * 发起单飞初始订阅；失败后由已有的定期 reconcile 驱动重试。
     *
     * <p>同一 EventListener 在失败时先尽力清理旧订阅，避免失败窗口累积监听器。
     */
    void start() {
        if (closed.get() || !starting.compareAndSet(false, true)) {
            return;
        }
        long startedAtNanos =
                observer.enabled() ? System.nanoTime() : 0L;
        executor.submit(
                "subscribe",
                serviceKey,
                () -> {
                    if (closed.get()) {
                        return;
                    }
                    boolean subscribed = false;
                    try {
                        namingService.subscribe(
                                serviceName, group, List.of(cluster), eventListener);
                        subscribed = true;
                        if (closed.get()) {
                            namingService.unsubscribe(
                                    serviceName, group, List.of(cluster), eventListener);
                            return;
                        }
                        List<Instance> initial = namingService.getAllInstances(
                                serviceName, group, List.of(cluster), false);
                        initialize(initial);
                    } catch (Exception error) {
                        if (subscribed) {
                            try {
                                namingService.unsubscribe(
                                        serviceName, group, List.of(cluster), eventListener);
                            } catch (Exception cleanupError) {
                                LOGGER.debug("Nacos failed subscription cleanup: service={}, errorType={}",
                                        serviceKey.canonicalName(),
                                        cleanupError.getClass().getName());
                            }
                        }
                        throw error;
                    }
                }).whenComplete((ignored, error) -> {
                    starting.set(false);
                    if (observer.enabled()) {
                        try {
                            observer.onRegistryOperationCompleted(
                                    "nacos",
                                    RpcRegistryOperation.SUBSCRIBE,
                                    System.nanoTime() - startedAtNanos,
                                    error);
                        } catch (RuntimeException observerError) {
                            LOGGER.debug("Nacos observer callback failed: service={}",
                                    serviceKey.canonicalName());
                        }
                    }
                    if (error != null && !closed.get()) {
                        synchronized (eventMonitor) {
                            preInitialEvents.clear();
                        }
                        signalFailure(error);
                        LOGGER.warn("Nacos subscription initialization failed; will retry. "
                                        + "service={}, errorType={}",
                                serviceKey.canonicalName(), error.getClass().getName());
                    }
                });
    }

    private void onEvent(Event event) {
        if (closed.get() || !(event instanceof NamingEvent namingEvent)) {
            return;
        }
        List<Instance> instances = List.copyOf(
                namingEvent.getInstances() == null
                        ? List.of()
                        : namingEvent.getInstances());
        synchronized (eventMonitor) {
            if (closed.get()) {
                return;
            }
            eventSequence++;
            if (!initialized) {
                // NamingEvent contains a full view; only the latest pre-initial view is needed.
                preInitialEvents.clear();
                preInitialEvents.add(instances);
                return;
            }
            // Coalesce complete views: slow listeners cannot produce an unbounded event queue.
            eventQueue.clear();
            eventQueue.addLast(instances);
            scheduleDrainLocked();
        }
    }

    private void initialize(List<Instance> initial) {
        synchronized (eventMonitor) {
            if (closed.get()) {
                return;
            }
            publish(initial);
            initialized = true;
            signalHealthy();
            eventQueue.addAll(preInitialEvents);
            preInitialEvents.clear();
            scheduleDrainLocked();
        }
    }

    private void scheduleDrainLocked() {
        if (draining || eventQueue.isEmpty() || closed.get()) {
            return;
        }
        draining = true;
        executor.submit("subscription-event", serviceKey,
                (NacosControlExecutor.CheckedRunnable) this::drainEvents)
                .whenComplete((ignored, error) -> {
                    if (error != null && !closed.get()) {
                        synchronized (eventMonitor) {
                            draining = false;
                            eventQueue.clear();
                        }
                        signalFailure(error);
                    }
                });
    }

    private void drainEvents() {
        while (!closed.get()) {
            List<Instance> next;
            synchronized (eventMonitor) {
                next = eventQueue.pollFirst();
                if (next == null) {
                    draining = false;
                    return;
                }
            }
            publish(next);
            signalHealthy();
        }
        synchronized (eventMonitor) {
            eventQueue.clear();
            draining = false;
        }
    }

    private void publish(List<Instance> instances) {
        publishNormalized(normalize(instances));
    }

    private synchronized void publishNormalized(
            List<ServiceInstance> normalized) {
        if (closed.get()
                || normalized.equals(lastSnapshot)) {
            return;
        }
        List<ServiceInstance> next = List.copyOf(normalized);
        listener.onSnapshot(new RegistrySnapshot(next, revision.incrementAndGet()));
        lastSnapshot = next;
    }

    private List<ServiceInstance> normalize(List<Instance> instances) {
        return instances.stream()
                .map(instance ->
                        NacosInstanceMapper.fromNacos(
                                serviceKey,
                                instance))
                .filter(Objects::nonNull)
                .sorted(Comparator
                        .comparing((ServiceInstance value) ->
                                value.endpoint().host())
                        .thenComparingInt(value ->
                                value.endpoint().port())
                        .thenComparing(ServiceInstance::instanceId))
                .distinct()
                .toList();
    }

    /**
     * 使用受控远端查询确认健康；旧查询不能覆盖更新的 NamingEvent。
     *
     * <p>未初始化时只重试订阅，启动单飞保证不会重复排队阻塞式 SDK 请求。
     */
    void reconcile() {
        long before;
        synchronized (eventMonitor) {
            if (closed.get()) {
                return;
            }
            if (!initialized) {
                start();
                return;
            }
            if (draining || !eventQueue.isEmpty()) {
                return;
            }
            before = eventSequence;
        }
        try {
            List<Instance> instances = namingService.getAllInstances(
                    serviceName, group, List.of(cluster), false);
            synchronized (eventMonitor) {
                if (closed.get() || !initialized || draining
                        || !eventQueue.isEmpty() || eventSequence != before) {
                    return;
                }
                publish(instances);
                // A remote success without a view change still confirms a healthy subscription.
                signalHealthy();
            }
        } catch (Exception error) {
            signalFailure(error);
            LOGGER.debug("Nacos subscription reconcile failed: service={}, errorType={}",
                    serviceKey.canonicalName(), error.getClass().getName());
        }
    }

    private void signalHealthy() {
        if (!closed.get()) {
            unhealthy.set(false);
            try {
                listener.onSynchronizationSucceeded();
            } catch (RuntimeException observerError) {
                LOGGER.debug("Nacos health listener failed: service={}",
                        serviceKey.canonicalName());
            }
        }
    }

    private void signalFailure(Throwable failure) {
        if (!closed.get() && unhealthy.compareAndSet(false, true)) {
            try {
                listener.onSynchronizationFailed(failure);
            } catch (RuntimeException observerError) {
                LOGGER.debug("Nacos failure listener failed: service={}",
                        serviceKey.canonicalName());
            }
        }
    }

    boolean matches(ServiceKey key) {
        return serviceKey.equals(key);
    }

    void onLocalUnregistered(
            ServiceInstance instance) {
        if (closed.get()) {
            return;
        }
        synchronized (eventMonitor) {
            if (!initialized) {
                return;
            }
            List<ServiceInstance> current =
                    snapshotCopy();
            List<ServiceInstance> updated =
                    current.stream()
                            .filter(value ->
                                    !value.endpoint()
                                            .equals(
                                                    instance.endpoint()))
                            .toList();
            publishNormalized(updated);
        }
    }

    private synchronized List<ServiceInstance> snapshotCopy() {
        return lastSnapshot == null
                ? List.of()
                : List.copyOf(lastSnapshot);
    }

    CompletableFuture<Void> closeAsync() {
        if (!closed.compareAndSet(false, true)) {
            return CompletableFuture.completedFuture(null);
        }
        synchronized (eventMonitor) {
            eventQueue.clear();
            preInitialEvents.clear();
        }
        return executor.submit(
                "unsubscribe",
                serviceKey,
                () -> namingService.unsubscribe(
                        serviceName,
                        group,
                        List.of(cluster),
                        eventListener));
    }

    @Override
    public void close() {
        closeAsync().whenComplete((ignored, error) -> {
            if (error != null) {
                LOGGER.warn(
                        "Nacos unsubscribe failed: service={}, errorType={}",
                        serviceKey.canonicalName(),
                        error.getClass().getName());
            }
        });
    }
}
