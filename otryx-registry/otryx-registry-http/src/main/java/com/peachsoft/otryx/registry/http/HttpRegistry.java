package com.peachsoft.otryx.registry.http;

import com.peachsoft.otryx.api.ServiceInstance;
import com.peachsoft.otryx.api.ServiceKey;
import com.peachsoft.otryx.observability.RpcObserver;
import com.peachsoft.otryx.observability.RpcRegistryOperation;
import com.peachsoft.otryx.registry.Registry;
import com.peachsoft.otryx.registry.RegistryCapabilities;
import com.peachsoft.otryx.registry.RegistryCapability;
import com.peachsoft.otryx.registry.RegistryListener;
import com.peachsoft.otryx.registry.RegistrySnapshot;
import com.peachsoft.otryx.registry.RegistrySubscription;
import com.peachsoft.otryx.registry.ServiceRegistrar;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Consul/Eureka 共享的有界异步 Registry 控制面及轮询订阅协调器。
 *
 * <p>HTTP 调用从不在 RPC 事件循环执行；没有远端原生 Revision 时，
 * 仅生成本进程递增的快照版本，不声明 REGISTRY REVISION 能力。
 *
 * @Author Ryan
 * @Version 1.0.0
 * @CreateTime 2026/10/10 18:00
 */
public final class HttpRegistry implements Registry, ServiceRegistrar {

    private static final Logger LOGGER = LoggerFactory.getLogger(HttpRegistry.class);
    private static final int CORE_THREADS = 2;
    private static final int MAX_THREADS = 4;
    private static final long IDLE_TIMEOUT_SECONDS = 30L;
    private static final int MAX_PENDING_CONTROL_TASKS = 256;
    private static final long CLOSE_TIMEOUT_SECONDS = 3L;
    private static final long MIN_INTERVAL_MILLIS = 1L;
    private static final RegistryCapabilities CAPABILITIES = RegistryCapabilities.of(
            RegistryCapability.REGISTRATION,
            RegistryCapability.SUBSCRIPTION,
            RegistryCapability.LEASE,
            RegistryCapability.HEALTH,
            RegistryCapability.WEIGHT,
            RegistryCapability.METADATA);

    private final HttpRegistryBackend backend;
    private final RpcObserver observer;
    private final ThreadPoolExecutor workers;
    private final ScheduledExecutorService scheduler;
    private final Duration pollInterval;
    private final Duration heartbeatInterval;
    private final ConcurrentHashMap<String, Registration> registered =
            new ConcurrentHashMap<>();
    private final Set<Subscription> subscriptions = ConcurrentHashMap.newKeySet();
    private final AtomicLong revision = new AtomicLong();
    private final AtomicBoolean closed = new AtomicBoolean();

    /**
     * 创建 HTTP 控制面并限制工作线程与任务排队长度。
     *
     * @param backend Consul 或 Eureka 适配器
     * @param pollInterval 订阅视图完整校对周期
     * @param heartbeatInterval Provider 健康续约周期
     * @param observer 监控 Observer
     */
    public HttpRegistry(
            HttpRegistryBackend backend, Duration pollInterval,
            Duration heartbeatInterval, RpcObserver observer) {
        this.backend = Objects.requireNonNull(backend, "backend");
        this.pollInterval = positive(pollInterval, "pollInterval");
        this.heartbeatInterval = positive(heartbeatInterval, "heartbeatInterval");
        this.observer = observer == null ? RpcObserver.noop() : observer;
        AtomicLong sequence = new AtomicLong();
        this.workers = new ThreadPoolExecutor(
                CORE_THREADS, MAX_THREADS, IDLE_TIMEOUT_SECONDS, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(MAX_PENDING_CONTROL_TASKS),
                task -> {
                    Thread thread = new Thread(task,
                            "otryx-" + backend.type() + "-control-"
                                    + sequence.incrementAndGet());
                    thread.setDaemon(true);
                    return thread;
                }, new ThreadPoolExecutor.AbortPolicy());
        this.scheduler = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task,
                    "otryx-" + backend.type() + "-schedule");
            thread.setDaemon(true);
            return thread;
        });
    }

    private static Duration positive(Duration value, String name) {
        if (value == null || value.isZero() || value.isNegative()
                || value.toMillis() < MIN_INTERVAL_MILLIS) {
            throw new IllegalArgumentException(name + " must be at least one millisecond");
        }
        return value;
    }

    /**
     * 返回相应配置或运行状态。
     *
     * @return 声明经适配器实现的真实能力，不含远端 Revision
     */
    @Override
    public RegistryCapabilities capabilities() {
        return CAPABILITIES;
    }

    /**
     * 异步注册并开始自动续约。
     *
     * @param instance Provider
     * @return 注册结果
     */
    @Override
    public CompletionStage<Void> register(ServiceInstance instance) {
        requireOpen();
        Objects.requireNonNull(instance, "instance");
        HttpRegistryIdentity.requireRoutable(instance);
        String id = registrationKey(instance);
        Registration state = registered.computeIfAbsent(id, ignored -> new Registration());
        return observe(RpcRegistryOperation.REGISTER,
                CompletableFuture.runAsync(() -> {
                    synchronized (state) {
                        requireOpen();
                        try {
                            backend.register(instance);
                            state.instance = instance;
                            state.active = true;
                            if (state.future == null) {
                                long millis = heartbeatInterval.toMillis();
                                state.future = scheduler.scheduleWithFixedDelay(
                                        () -> renew(state), millis, millis,
                                        TimeUnit.MILLISECONDS);
                            }
                        } catch (Exception error) {
                            throw failure("register", error);
                        }
                    }
                }, workers));
    }

    /**
     * 异步注销且停止健康续约，避免失效节点继续刷新租约。
     *
     * @param instance Provider
     * @return 注销完成信号
     */
    @Override
    public CompletionStage<Void> unregister(ServiceInstance instance) {
        Objects.requireNonNull(instance, "instance");
        if (closed.get()) {
            return CompletableFuture.completedFuture(null);
        }
        String id = registrationKey(instance);
        Registration state = registered.get(id);
        return observe(RpcRegistryOperation.UNREGISTER,
                CompletableFuture.runAsync(() -> {
                    if (state != null) {
                        synchronized (state) {
                            state.active = false;
                            if (state.future != null) {
                                state.future.cancel(false);
                                state.future = null;
                            }
                            try {
                                backend.unregister(instance);
                                registered.remove(id, state);
                            } catch (Exception error) {
                                throw failure("unregister", error);
                            }
                        }
                    } else {
                        try {
                            backend.unregister(instance);
                        } catch (Exception error) {
                            throw failure("unregister", error);
                        }
                    }
                    subscriptions.forEach(sub -> sub.pollNow(instance.serviceKey()));
                }, workers));
    }

    /**
     * 由有界控制面工作线程获取权威快照。
     *
     * @param key 完整服务键
     * @return 远端健康实例快照
     */
    @Override
    public CompletionStage<RegistrySnapshot> lookup(ServiceKey key) {
        requireOpen();
        Objects.requireNonNull(key, "key");
        return observe(RpcRegistryOperation.LOOKUP,
                CompletableFuture.supplyAsync(() -> new RegistrySnapshot(
                        fetch(key), revision.incrementAndGet()), workers));
    }

    /**
     * 创建轮询校对订阅，首次快照（包括空视图）异步发布。
     *
     * @param key 完整服务键
     * @param listener 快照监听器
     * @return 可重复关闭的订阅句柄
     */
    @Override
    public RegistrySubscription subscribe(ServiceKey key, RegistryListener listener) {
        requireOpen();
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(listener, "listener");
        Subscription sub = new Subscription(key, listener);
        subscriptions.add(sub);
        sub.future = scheduler.scheduleWithFixedDelay(sub::poll,
                0L, pollInterval.toMillis(), TimeUnit.MILLISECONDS);
        return sub;
    }

    private List<ServiceInstance> fetch(ServiceKey key) {
        try {
            return backend.lookup(key).stream()
                    .filter(Objects::nonNull)
                    .sorted(Comparator.comparing((ServiceInstance value) ->
                            value.endpoint().host())
                            .thenComparingInt(value -> value.endpoint().port())
                            .thenComparing(ServiceInstance::instanceId))
                    .distinct()
                    .toList();
        } catch (Exception error) {
            throw failure("lookup", error);
        }
    }

    private static String registrationKey(ServiceInstance instance) {
        return instance.serviceKey().canonicalName() + "\u0000" + instance.instanceId();
    }

    private void renew(Registration state) {
        if (closed.get() || !state.active || !state.renewing.compareAndSet(false, true)) {
            return;
        }
        try {
            workers.execute(() -> {
                try {
                    synchronized (state) {
                        if (state.active && !closed.get()) {
                            backend.renew(state.instance);
                        }
                    }
                } catch (Exception error) {
                    LOGGER.warn("Registry renewal failed: type={}, errorType={}",
                            backend.type(), error.getClass().getName());
                } finally {
                    state.renewing.set(false);
                }
            });
        } catch (RejectedExecutionException error) {
            state.renewing.set(false);
            LOGGER.warn("Registry renewal rejected: type={}", backend.type());
        }
    }

    private <T> CompletionStage<T> observe(
            RpcRegistryOperation operation, CompletionStage<T> stage) {
        if (observer.enabled()) {
            long start = System.nanoTime();
            stage.whenComplete((ignored, error) -> {
                try {
                    observer.onRegistryOperationCompleted(
                            backend.type(), operation, System.nanoTime() - start, error);
                } catch (RuntimeException observerError) {
                    LOGGER.debug("Registry observer failed: type={}", backend.type());
                }
            });
        }
        return stage;
    }

    private static RuntimeException failure(String operation, Exception error) {
        return new IllegalStateException("Registry control operation failed: " + operation,
                error);
    }

    private void requireOpen() {
        if (closed.get()) {
            throw new IllegalStateException("HTTP registry is closed");
        }
    }

    /** 取消轮询与续约，尝试注销剩余 Provider 并有界清理线程。 */
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        scheduler.shutdownNow();
        subscriptions.forEach(Subscription::close);
        subscriptions.clear();
        CompletableFuture<?>[] cleanup = registered.values().stream()
                .map(registration -> CompletableFuture.runAsync(() -> {
                    synchronized (registration) {
                        if (registration.active) {
                            registration.active = false;
                            try {
                                backend.unregister(registration.instance);
                            } catch (Exception error) {
                                LOGGER.warn("Registry shutdown unregister failed: type={}",
                                        backend.type());
                            }
                        }
                    }
                }, workers)).toArray(CompletableFuture[]::new);
        try {
            CompletableFuture.allOf(cleanup).orTimeout(CLOSE_TIMEOUT_SECONDS, TimeUnit.SECONDS).join();
        } catch (RuntimeException error) {
            LOGGER.warn("Registry shutdown timed out: type={}", backend.type());
        }
        registered.clear();
        workers.shutdownNow();
        backend.close();
    }

    private static final class Registration {
        private final AtomicBoolean renewing = new AtomicBoolean();
        private volatile ServiceInstance instance;
        private volatile boolean active;
        private volatile ScheduledFuture<?> future;
    }

    private final class Subscription implements RegistrySubscription {
        private final ServiceKey key;
        private final RegistryListener listener;
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private final AtomicBoolean pending = new AtomicBoolean();
        private volatile ScheduledFuture<?> future;
        private List<ServiceInstance> last;

        private Subscription(ServiceKey key, RegistryListener listener) {
            this.key = key;
            this.listener = listener;
        }

        private void pollNow(ServiceKey changed) {
            if (key.equals(changed)) {
                poll();
            }
        }

        private void poll() {
            if (cancelled.get() || closed.get() || !pending.compareAndSet(false, true)) {
                return;
            }
            try {
                workers.execute(() -> {
                    try {
                        List<ServiceInstance> current = fetch(key);
                        synchronized (this) {
                            if (!cancelled.get() && !closed.get()
                                    && !current.equals(last)) {
                                last = List.copyOf(current);
                                listener.onSnapshot(new RegistrySnapshot(
                                        current, revision.incrementAndGet()));
                            }
                        }
                    } catch (RuntimeException error) {
                        LOGGER.debug("Registry subscription refresh failed: type={}, service={}",
                                backend.type(), key.canonicalName());
                    } finally {
                        pending.set(false);
                    }
                });
            } catch (RejectedExecutionException error) {
                pending.set(false);
                LOGGER.warn("Registry subscription refresh rejected: type={}", backend.type());
            }
        }

        /** 关闭订阅并释放轮询任务。 */
        @Override
        public void close() {
            if (cancelled.compareAndSet(false, true)) {
                if (future != null) {
                    future.cancel(false);
                }
                subscriptions.remove(this);
            }
        }
    }
}
