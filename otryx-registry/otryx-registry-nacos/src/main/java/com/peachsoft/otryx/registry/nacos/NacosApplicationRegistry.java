package com.peachsoft.otryx.registry.nacos;

import com.alibaba.nacos.api.config.ConfigService;
import com.alibaba.nacos.api.naming.NamingService;
import com.alibaba.nacos.api.naming.listener.EventListener;
import com.alibaba.nacos.api.naming.pojo.Instance;
import com.peachsoft.otryx.api.ApplicationInstance;
import com.peachsoft.otryx.api.ApplicationKey;
import com.peachsoft.otryx.observability.RpcObserver;
import com.peachsoft.otryx.observability.RpcRegistryOperation;
import com.peachsoft.otryx.registry.ApplicationDirectorySnapshot;
import com.peachsoft.otryx.registry.ApplicationManifestRepository;
import com.peachsoft.otryx.registry.ApplicationMetadataBackend;
import com.peachsoft.otryx.registry.ApplicationRegistrar;
import com.peachsoft.otryx.registry.ApplicationRegistry;
import com.peachsoft.otryx.registry.ApplicationRegistryListener;
import com.peachsoft.otryx.registry.RegistryCapabilities;
import com.peachsoft.otryx.registry.RegistryCapability;
import com.peachsoft.otryx.registry.RegistrySecurityDescriptor;
import com.peachsoft.otryx.registry.RegistrySubscription;
import com.peachsoft.otryx.registry.ServiceMappingRepository;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Nacos Naming/Config V2 双角色应用成员与共享元数据生命周期。
 *
 * <p>Naming 以应用名而非接口服务名注册真实 Provider/Consumer/BOTH。对纯 Consumer，
 * SDK 使用真实进程 IP + port=0（无 RPC Endpoint）承载心跳，物理 cluster 中的
 * 固定身份摘要保证同主机多进程独立；Core 仍要验证角色、Manifest 和 Schema。
 * SDK 可能阻塞的网络调用全部转到有界 NacosControlExecutor，调用热路径不访问 Naming。
 *
 * @Author OpenAI
 * @Version 1.0.0-SNAPSHOT
 * @CreateTime 2026/10/16 13:42
 */
public final class NacosApplicationRegistry implements ApplicationRegistry, ApplicationRegistrar,
        ApplicationMetadataBackend {

    private static final Logger LOGGER = LoggerFactory.getLogger(NacosApplicationRegistry.class);
    private static final Duration RECONCILE_INTERVAL = Duration.ofSeconds(5);
    private static final Duration CLOSE_TIMEOUT = Duration.ofSeconds(3);
    private static final int MAX_APPLICATIONS = 64;
    private static final int MAX_INSTANCES_PER_APP = 256;
    private static final int MAX_SUBSCRIBERS = 128;
    private static final int MAX_PENDING_APPLICATIONS = 64;
    private static final RegistryCapabilities CAPABILITIES = RegistryCapabilities.of(
            RegistryCapability.REGISTRATION,
            RegistryCapability.SUBSCRIPTION,
            RegistryCapability.HEALTH,
            RegistryCapability.WEIGHT,
            RegistryCapability.CLUSTER,
            RegistryCapability.METADATA,
            RegistryCapability.LEASE,
            RegistryCapability.REVISION);

    private final RegistrySecurityDescriptor descriptor;
    private final NamingService naming;
    private final ConfigService config;
    private final NacosControlExecutor executor = new NacosControlExecutor();
    private final NacosApplicationManifestRepository manifests;
    private final NacosServiceMappingRepository mappings;
    private final String namespace;
    private final String group;
    private final String cluster;
    private final String consumerHost;
    private final RpcObserver observer;
    private final ConcurrentMap<ApplicationKey, Registration> registered = new ConcurrentHashMap<>();
    private final ConcurrentMap<ApplicationKey, CompletableFuture<Void>> pending = new ConcurrentHashMap<>();
    private final Set<Subscription> subscriptions = ConcurrentHashMap.newKeySet();
    private final AtomicLong revision = new AtomicLong();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Object sequenceLock = new Object();
    private final ScheduledFuture<?> reconcileTask;

    NacosApplicationRegistry(
            NamingService naming, ConfigService config, String namespace, String group,
            String cluster, String consumerHost, RpcObserver observer) {
        this(naming, config, namespace, group, cluster, consumerHost, observer, RegistrySecurityDescriptor.unknown());
    }

    NacosApplicationRegistry(
            NamingService naming, ConfigService config, String namespace, String group,
            String cluster, String consumerHost, RpcObserver observer, RegistrySecurityDescriptor descriptor) {
        this.descriptor = Objects.requireNonNull(descriptor, "descriptor");
        this.naming = Objects.requireNonNull(naming, "naming");
        this.config = Objects.requireNonNull(config, "config");
        this.namespace = new ApplicationKey(namespace, "registry").namespace();
        this.group = Objects.requireNonNull(group, "group");
        this.cluster = Objects.requireNonNull(cluster, "cluster");
        this.consumerHost = Objects.requireNonNull(consumerHost, "consumerHost");
        if (!NacosV2InstanceMapper.validHost(consumerHost)) {
            throw new IllegalArgumentException("Invalid Nacos Consumer membership host");
        }
        this.observer = observer == null ? RpcObserver.noop() : observer;
        NacosV2ConfigStore store = new NacosV2ConfigStore(config, group, descriptor);
        manifests = new NacosApplicationManifestRepository(store, executor, this.namespace);
        mappings = new NacosServiceMappingRepository(store, executor, this.namespace);
        reconcileTask = executor.scheduleWithFixedDelay(
                "v2-membership-reconcile", this.namespace,
                RECONCILE_INTERVAL, RECONCILE_INTERVAL, this::reconcileSubscriptions);
    }

    /**
     * 获取同一个 Nacos Config 客户端上的 Manifest 后端。
     *
     * @return 由 Registry 生命周期拥有的 Manifest Repository
     */
    public ApplicationManifestRepository manifestRepository() {
        requireOpen();
        return manifests;
    }

    /**
     * 获取独立的、具持久化 CAS Fencing 的实例级 Mapping 仓库。
     *
     * @return 由 Registry 生命周期拥有的 ServiceMapping Repository
     */
    public ServiceMappingRepository serviceMappingRepository() {
        requireOpen();
        return mappings;
    }

    /** 返回实际创建的 Naming/Config 客户端保护，不为注入的原始 SDK 背书。 */
    @Override
    public RegistrySecurityDescriptor securityDescriptor() {
        return descriptor;
    }

    /** 返回当前 Nacos Naming 能力声明。 */
    @Override
    public RegistryCapabilities capabilities() {
        return CAPABILITIES;
    }

    /**
     * 按 ApplicationKey+instanceId 幂等注册/更新成员；Consumer port=0 不是 RPC 端点。
     *
     * <p>一个 Nacos Naming client 在同应用只能持有一个 member；多实例必须各自
     * 使用独立 Factory 创建的 Naming client，以免 SDK 后续注册覆盖之前的实例。
     */
    @Override
    public CompletionStage<Void> register(ApplicationInstance instance) {
        Objects.requireNonNull(instance, "instance");
        requireNamespace(instance.application());
        requireOpen();
        long start = observer.enabled() ? System.nanoTime() : 0L;
        return observe(serial(instance.application(),
                () -> executor.submit("application-register", instance.application().applicationName(),
                        () -> registerSync(instance))), RpcRegistryOperation.REGISTER, start);
    }

    private Void registerSync(ApplicationInstance member) throws Exception {
        ApplicationKey application = member.application();
        Registration previous = registered.get(application);
        if (previous != null && previous.member().equals(member)) {
            return null;
        }
        if (previous != null && !previous.member().instanceId().equals(member.instanceId())) {
            throw new IllegalStateException("Nacos Naming client already owns a different application member");
        }
        String token = previous == null ? UUID.randomUUID().toString() : previous.token();
        Instance target = NacosV2InstanceMapper.encode(member, cluster, consumerHost, token);
        if (previous != null) {
            naming.deregisterInstance(NacosV2Names.serviceName(application), group, previous.nacos());
            registered.remove(application, previous);
        }
        naming.registerInstance(NacosV2Names.serviceName(application), group, target);
        registered.put(application, new Registration(member, target, token));
        return null;
    }

    /** 带进程所有权校验的幂等注销；旧快照不能注销升级后的成员。 */
    @Override
    public CompletionStage<Void> unregister(ApplicationInstance instance) {
        Objects.requireNonNull(instance, "instance");
        requireNamespace(instance.application());
        if (closed.get()) {
            return CompletableFuture.completedFuture(null);
        }
        long start = observer.enabled() ? System.nanoTime() : 0L;
        return observe(serial(instance.application(),
                () -> executor.submit("application-unregister", instance.application().applicationName(),
                        () -> {
                            Registration existing = registered.get(instance.application());
                            if (existing != null && existing.member().equals(instance)) {
                                naming.deregisterInstance(NacosV2Names.serviceName(instance.application()),
                                        group, existing.nacos());
                                registered.remove(instance.application(), existing);
                            }
                            return null;
                        })), RpcRegistryOperation.UNREGISTER, start);
    }

    /** 非阻塞拉取完整应用目录；健康空目录与 Nacos 错误必须区分。 */
    @Override
    public CompletionStage<ApplicationDirectorySnapshot> lookup(ApplicationKey application) {
        requireNamespace(application);
        requireOpen();
        long start = observer.enabled() ? System.nanoTime() : 0L;
        return observe(executor.submit("application-lookup", application.applicationName(),
                () -> snapshot(application)), RpcRegistryOperation.LOOKUP, start);
    }

    private ApplicationDirectorySnapshot snapshot(ApplicationKey application) throws Exception {
        List<Instance> instances = naming.getAllInstances(NacosV2Names.serviceName(application), group, false);
        if (instances.size() > MAX_INSTANCES_PER_APP * 4) {
            throw new IllegalStateException("Nacos application member response exceeds capacity");
        }
        List<ApplicationInstance> result = new ArrayList<>();
        for (Instance instance : instances) {
            ApplicationInstance member = NacosV2InstanceMapper.decode(application, cluster, instance);
            if (member != null) {
                result.add(member);
                if (result.size() > MAX_INSTANCES_PER_APP) {
                    throw new IllegalStateException("Nacos application directory capacity exceeded");
                }
            }
        }
        result.sort(Comparator.comparing(ApplicationInstance::instanceId));
        return new ApplicationDirectorySnapshot(application, result, revision.incrementAndGet());
    }

    /** 创建并持有显式健康事件的 Naming 应用级订阅。 */
    @Override
    public RegistrySubscription subscribe(ApplicationKey application, ApplicationRegistryListener listener) {
        requireNamespace(application);
        Objects.requireNonNull(listener, "listener");
        requireOpen();
        if (subscriptions.size() >= MAX_SUBSCRIBERS) {
            throw new IllegalStateException("Nacos application subscription capacity exceeded");
        }
        Subscription subscription = new Subscription(application, listener);
        subscriptions.add(subscription);
        subscription.refresh();
        return subscription;
    }

    private CompletionStage<Void> serial(ApplicationKey application, Supplier<CompletionStage<Void>> action) {
        synchronized (sequenceLock) {
            if (!pending.containsKey(application) && pending.size() >= MAX_PENDING_APPLICATIONS) {
                return CompletableFuture.failedFuture(new IllegalStateException("Nacos application queue is full"));
            }
            CompletableFuture<Void> prior = pending.get(application);
            CompletableFuture<Void> base = prior == null
                    ? CompletableFuture.completedFuture(null)
                    : prior.handle((ignored, error) -> null);
            CompletableFuture<Void> next = base.thenCompose(ignored -> action.get()).toCompletableFuture();
            pending.put(application, next);
            next.whenComplete((ignored, error) -> pending.remove(application, next));
            return next;
        }
    }

    private void reconcileSubscriptions() {
        if (closed.get()) {
            return;
        }
        subscriptions.forEach(Subscription::refresh);
    }

    private <T> CompletionStage<T> observe(
            CompletionStage<T> stage, RpcRegistryOperation operation, long start) {
        if (observer.enabled()) {
            stage.whenComplete((ignored, error) -> {
                try {
                    observer.onRegistryOperationCompleted("nacos", operation,
                            System.nanoTime() - start, error);
                } catch (RuntimeException failure) {
                    LOGGER.debug("Nacos V2 observer rejected callback");
                }
            });
        }
        return stage;
    }

    private void requireNamespace(ApplicationKey application) {
        Objects.requireNonNull(application, "application");
        if (!namespace.equals(application.namespace())) {
            throw new IllegalArgumentException("Nacos application namespace mismatch");
        }
    }

    private void requireOpen() {
        if (closed.get()) {
            throw new IllegalStateException("Nacos application registry is closed");
        }
    }

    /**
     * 关闭订阅、撤销本进程 Naming 成员并释放 SDK 的连接与后台线程。
     * 本操作可能等待有界 SDK shutdown，不能从 RPC EventLoop 调用。
     */
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        reconcileTask.cancel(false);
        subscriptions.forEach(Subscription::close);
        subscriptions.clear();
        try {
            executor.submit("application-shutdown", namespace, () -> {
                for (Map.Entry<ApplicationKey, Registration> entry : registered.entrySet()) {
                    try {
                        naming.deregisterInstance(NacosV2Names.serviceName(entry.getKey()),
                                group, entry.getValue().nacos());
                    } catch (Exception failure) {
                        LOGGER.warn("Nacos V2 membership cleanup failed: errorType={}",
                                failure.getClass().getSimpleName());
                    }
                }
                registered.clear();
                naming.shutDown();
                config.shutDown();
                return null;
            }).orTimeout(CLOSE_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS).join();
        } catch (RuntimeException error) {
            LOGGER.warn("Nacos V2 shutdown incomplete: errorType={}", error.getClass().getSimpleName());
            try {
                naming.shutDown();
                config.shutDown();
            } catch (Exception closeError) {
                LOGGER.warn("Nacos V2 forced close failed: errorType={}",
                        closeError.getClass().getSimpleName());
            }
        } finally {
            executor.close();
        }
    }

    private final class Subscription implements RegistrySubscription {

        private final ApplicationKey application;
        private final ApplicationRegistryListener listener;
        private final EventListener events = ignored -> refresh();
        private final AtomicBoolean stopped = new AtomicBoolean();
        private final AtomicBoolean refreshing = new AtomicBoolean();
        private final AtomicBoolean dirty = new AtomicBoolean();
        private final AtomicBoolean subscribed = new AtomicBoolean();
        private final AtomicBoolean unhealthy = new AtomicBoolean();
        private volatile List<ApplicationInstance> delivered;

        private Subscription(ApplicationKey application, ApplicationRegistryListener listener) {
            this.application = application;
            this.listener = listener;
        }

        private void refresh() {
            if (stopped.get() || closed.get()) {
                return;
            }
            dirty.set(true);
            if (!refreshing.compareAndSet(false, true)) {
                return;
            }
            executor.submit("application-subscribe", application.applicationName(), () -> {
                while (dirty.getAndSet(false) && !stopped.get() && !closed.get()) {
                    try {
                        if (!subscribed.get()) {
                            naming.subscribe(NacosV2Names.serviceName(application), group, events);
                            subscribed.set(true);
                        }
                        ApplicationDirectorySnapshot fresh = snapshot(application);
                        if (!stopped.get()) {
                            if (!fresh.instances().equals(delivered)) {
                                listener.onSnapshot(fresh);
                                delivered = fresh.instances();
                            }
                            listener.onSynchronizationSucceeded();
                            unhealthy.set(false);
                        }
                    } catch (Exception error) {
                        if (unhealthy.compareAndSet(false, true) && !stopped.get()) {
                            try {
                                listener.onSynchronizationFailed(NacosFailures.normalize(error));
                            } catch (RuntimeException callbackFailure) {
                                LOGGER.debug("Nacos V2 subscription listener rejected failure");
                            }
                        }
                        break;
                    }
                }
                return null;
            }).whenComplete((ignored, error) -> {
                refreshing.set(false);
                if (error != null && !stopped.get() && unhealthy.compareAndSet(false, true)) {
                    try {
                        listener.onSynchronizationFailed(NacosFailures.normalize(error));
                    } catch (RuntimeException ignoredError) {
                        LOGGER.debug("Nacos V2 subscription callback rejected executor failure");
                    }
                }
                if (dirty.get() && !stopped.get() && !closed.get()) {
                    refresh();
                }
            });
        }

        /** 关闭后即不再投递事件；Naming unsubscribe 在隔离的控制线程执行。 */
        @Override
        public void close() {
            if (!stopped.compareAndSet(false, true)) {
                return;
            }
            subscriptions.remove(this);
            if (subscribed.get()) {
                executor.submit("application-unsubscribe", application.applicationName(), () -> {
                    naming.unsubscribe(NacosV2Names.serviceName(application), group, events);
                    return null;
                });
            }
        }
    }

    private record Registration(ApplicationInstance member, Instance nacos, String token) {
    }
}
