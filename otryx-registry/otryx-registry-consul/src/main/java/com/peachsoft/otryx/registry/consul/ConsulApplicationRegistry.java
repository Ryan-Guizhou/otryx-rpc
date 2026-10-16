package com.peachsoft.otryx.registry.consul;

import com.peachsoft.otryx.api.ApplicationInstance;
import com.peachsoft.otryx.api.ApplicationKey;
import com.peachsoft.otryx.observability.RpcObserver;
import com.peachsoft.otryx.observability.RpcRegistryOperation;
import com.peachsoft.otryx.registry.ApplicationDirectorySnapshot;
import com.peachsoft.otryx.registry.ApplicationManifestRepository;
import com.peachsoft.otryx.registry.ApplicationRegistrar;
import com.peachsoft.otryx.registry.ApplicationRegistry;
import com.peachsoft.otryx.registry.ApplicationRegistryListener;
import com.peachsoft.otryx.registry.RegistryCapabilities;
import com.peachsoft.otryx.registry.RegistryCapability;
import com.peachsoft.otryx.registry.RegistrySubscription;
import com.peachsoft.otryx.registry.ServiceMappingRepository;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Consul V2 应用级 Service/TTL + KV Manifest/Mapping 原生控制面。
 *
 * <p>同一应用实例只有一个 Agent Service 与 TTL Check，不为每个接口重复注册。
 * Consumer-only 使用合法的 Address="" 和 Port=0 成员；注册与续约均通过
 * 同一个本地 Agent，故障后 Check 404 会重新注册。发现和订阅只返回
 * passing 成员，任何错误通过异步失败信号暴露，不伪造健康空目录。
 *
 * @Author OpenAI
 * @Version 1.0.0-SNAPSHOT
 * @CreateTime 2026/10/16 14:12
 */
public final class ConsulApplicationRegistry implements ApplicationRegistry, ApplicationRegistrar {

    private static final Logger LOGGER = LoggerFactory.getLogger(ConsulApplicationRegistry.class);
    private static final int MAX_LOCAL_MEMBERS = 64;
    private static final int MAX_SUBSCRIPTIONS = 128;
    private static final int MAX_DIRECTORY_MEMBERS = 256;
    private static final long CLOSE_TIMEOUT_SECONDS = 3L;
    private static final RegistryCapabilities CAPABILITIES = RegistryCapabilities.of(
            RegistryCapability.REGISTRATION, RegistryCapability.SUBSCRIPTION,
            RegistryCapability.HEALTH, RegistryCapability.LEASE,
            RegistryCapability.WEIGHT, RegistryCapability.METADATA);

    private final ConsulV2Http http;
    private final ConsulV2Executor executor = new ConsulV2Executor();
    private final ConsulApplicationManifestRepository manifests;
    private final ConsulServiceMappingRepository mappings;
    private final String namespace;
    private final Duration pollInterval;
    private final Duration heartbeatInterval;
    private final int ttlSeconds;
    private final RpcObserver observer;
    private final ConcurrentMap<String, Registration> registrations = new ConcurrentHashMap<>();
    private final Set<Subscription> subscriptions = ConcurrentHashMap.newKeySet();
    private final AtomicLong revision = new AtomicLong();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final ScheduledFuture<?> heartbeatTask;

    ConsulApplicationRegistry(
            ConsulV2Http http, String namespace, Duration pollInterval,
            Duration heartbeatInterval, int ttlSeconds, int sessionTtlSeconds, RpcObserver observer) {
        this.http = Objects.requireNonNull(http, "http");
        this.namespace = new ApplicationKey(namespace, "registry").namespace();
        this.pollInterval = Objects.requireNonNull(pollInterval, "pollInterval");
        this.heartbeatInterval = Objects.requireNonNull(heartbeatInterval, "heartbeatInterval");
        this.ttlSeconds = ttlSeconds;
        this.observer = observer == null ? RpcObserver.noop() : observer;
        this.manifests = new ConsulApplicationManifestRepository(http, executor, this.namespace);
        this.mappings = new ConsulServiceMappingRepository(http, executor, this.namespace, sessionTtlSeconds);
        heartbeatTask = executor.periodic("consul-v2-service-heartbeat", heartbeatInterval, () -> {
            // The scheduler remains nonblocking; real network work stays on the bounded worker.
            for (Registration registration : registrations.values()) {
                if (registration.active.get()) {
                    renew(registration);
                }
            }
            return null;
        });
    }

    /**
     * 返回与 Registry 生命周期绑定的 Consul KV Manifest Repository。
     *
     * @return 不可变内容寻址 Manifest 仓库
     */
    public ApplicationManifestRepository manifestRepository() {
        requireOpen();
        return manifests;
    }

    /**
     * 返回与 Registry 生命周期绑定的 Consul CAS + Session Mapping。
     *
     * @return 具备持久 Generation Fencing 的服务映射仓库
     */
    public ServiceMappingRepository serviceMappingRepository() {
        requireOpen();
        return mappings;
    }

    /** 返回已实现的应用级 Consul 能力，不声称远端全局 Revision。 */
    @Override
    public RegistryCapabilities capabilities() {
        return CAPABILITIES;
    }

    /** 异步注册真实应用成员，并启用后台 TTL 续约。 */
    @Override
    public CompletionStage<Void> register(ApplicationInstance member) {
        Objects.requireNonNull(member, "member");
        requireNamespace(member.application());
        requireOpen();
        String key = identity(member);
        if (!registrations.containsKey(key) && registrations.size() >= MAX_LOCAL_MEMBERS) {
            return CompletableFuture.failedFuture(new IllegalStateException("Consul member capacity exceeded"));
        }
        Registration state = registrations.computeIfAbsent(key, ignored -> new Registration(member));
        return observe(RpcRegistryOperation.REGISTER, executor.submit("consul-v2-register", () -> {
            synchronized (state) {
                requireOpen();
                http.register(ConsulV2MemberCodec.encode(member, state.incarnation, ttlSeconds));
                if (!http.pass(serviceId(state))) {
                    throw new IllegalStateException("Consul TTL check disappeared during registration");
                }
                state.member = member;
                state.active.set(true);
                return null;
            }
        }));
    }

    /** 按成员快照精确注销，不允许较旧角色的注销请求误删新注册状态。 */
    @Override
    public CompletionStage<Void> unregister(ApplicationInstance member) {
        Objects.requireNonNull(member, "member");
        requireNamespace(member.application());
        if (closed.get()) {
            return CompletableFuture.completedFuture(null);
        }
        String key = identity(member);
        Registration state = registrations.get(key);
        if (state == null) {
            return CompletableFuture.completedFuture(null);
        }
        return observe(RpcRegistryOperation.UNREGISTER, executor.submit("consul-v2-unregister", () -> {
            synchronized (state) {
                if (member.equals(state.member)) {
                    state.active.set(false);
                    http.deregister(serviceId(state));
                    registrations.remove(key, state);
                }
                return null;
            }
        }));
    }

    /** 从 Consul passing Service 读取完整 Provider/Consumer 应用目录。 */
    @Override
    public CompletionStage<ApplicationDirectorySnapshot> lookup(ApplicationKey application) {
        requireNamespace(application);
        requireOpen();
        return observe(RpcRegistryOperation.LOOKUP, executor.submit(
                "consul-v2-lookup", () -> snapshot(application)));
    }

    private ApplicationDirectorySnapshot snapshot(ApplicationKey app) throws Exception {
        List<ApplicationInstance> members = new ArrayList<>();
        for (var entry : http.healthy(ConsulV2Keys.serviceName(app))) {
            ApplicationInstance parsed = ConsulV2MemberCodec.decode(app, entry);
            if (parsed != null) {
                members.add(parsed);
                if (members.size() > MAX_DIRECTORY_MEMBERS) {
                    throw new IllegalStateException("Consul application directory capacity exceeded");
                }
            }
        }
        members.sort(Comparator.comparing(ApplicationInstance::instanceId));
        return new ApplicationDirectorySnapshot(app, members, revision.incrementAndGet());
    }

    /** 返回可关闭的轮询订阅句柄；异常回调不会更新最近成功的完整目录。 */
    @Override
    public RegistrySubscription subscribe(ApplicationKey application, ApplicationRegistryListener listener) {
        requireNamespace(application);
        Objects.requireNonNull(listener, "listener");
        requireOpen();
        if (subscriptions.size() >= MAX_SUBSCRIPTIONS) {
            throw new IllegalStateException("Consul application subscription capacity exceeded");
        }
        Subscription sub = new Subscription(application, listener);
        subscriptions.add(sub);
        try {
            sub.task = executor.periodic("consul-v2-subscription", pollInterval, () -> {
                sub.poll();
                return null;
            });
            return sub;
        } catch (RuntimeException error) {
            subscriptions.remove(sub);
            throw error;
        }
    }

    /** TTL 心跳单飞；Agent 404 重新发布完整服务定义。 */
    private void renew(Registration state) {
        if (!state.renewing.compareAndSet(false, true)) {
            return;
        }
        executor.submit("consul-v2-ttl-renew", () -> {
            synchronized (state) {
                if (state.active.get() && !closed.get() && !http.pass(serviceId(state))) {
                    http.register(ConsulV2MemberCodec.encode(state.member, state.incarnation, ttlSeconds));
                    if (!http.pass(serviceId(state))) {
                        throw new IllegalStateException("Consul TTL check was not restored");
                    }
                }
                return null;
            }
        }).whenComplete((ignored, error) -> {
            state.renewing.set(false);
            if (error != null && !closed.get()) {
                LOGGER.warn("Consul V2 member renewal failed: errorType={}",
                        error.getClass().getSimpleName());
            }
        });
    }

    private <T> CompletionStage<T> observe(RpcRegistryOperation operation, CompletionStage<T> future) {
        if (!observer.enabled()) {
            return future;
        }
        long start = System.nanoTime();
        future.whenComplete((ignored, error) -> {
            try {
                observer.onRegistryOperationCompleted("consul", operation, System.nanoTime() - start, error);
            } catch (RuntimeException callbackFailure) {
                LOGGER.debug("Consul V2 observer callback failed");
            }
        });
        return future;
    }

    private String serviceId(Registration state) {
        return ConsulV2Keys.serviceId(state.member.application(), state.member.instanceId(), state.incarnation);
    }

    private static String identity(ApplicationInstance member) {
        return member.application().namespace() + '\u0000' + member.application().applicationName()
                + '\u0000' + member.instanceId();
    }

    private void requireNamespace(ApplicationKey app) {
        Objects.requireNonNull(app, "application");
        if (!namespace.equals(app.namespace())) {
            throw new IllegalArgumentException("Consul application namespace mismatch");
        }
    }

    private void requireOpen() {
        if (closed.get()) {
            throw new IllegalStateException("Consul application registry is closed");
        }
    }

    /**
     * 停止调度、尝试注销本进程所有 TTL 服务，并清理 Mapping Sessions 和 HTTP 客户端。
     *
     * <p>close 可能阻塞短暂等待控制面 IO，仅可由非 EventLoop 的应用关闭线程调用。
     */
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        executor.cancel(heartbeatTask);
        subscriptions.forEach(Subscription::close);
        subscriptions.clear();
        CompletableFuture<?>[] cleanup = registrations.values().stream()
                .map(state -> executor.submit("consul-v2-shutdown", () -> {
                    synchronized (state) {
                        state.active.set(false);
                        http.deregister(serviceId(state));
                        return null;
                    }
                })).toArray(CompletableFuture[]::new);
        try {
            CompletableFuture.allOf(cleanup)
                    .orTimeout(CLOSE_TIMEOUT_SECONDS, TimeUnit.SECONDS).join();
        } catch (RuntimeException failure) {
            LOGGER.warn("Consul V2 Agent cleanup incomplete: errorType={}",
                    failure.getClass().getSimpleName());
        } finally {
            registrations.clear();
            mappings.close();
            executor.close();
            http.close();
        }
    }

    private static final class Registration {
        private final String incarnation = UUID.randomUUID().toString();
        private final AtomicBoolean active = new AtomicBoolean();
        private final AtomicBoolean renewing = new AtomicBoolean();
        private volatile ApplicationInstance member;

        private Registration(ApplicationInstance member) {
            this.member = member;
        }
    }

    private final class Subscription implements RegistrySubscription {
        private final ApplicationKey application;
        private final ApplicationRegistryListener listener;
        private final AtomicBoolean stopped = new AtomicBoolean();
        private final AtomicBoolean unhealthy = new AtomicBoolean();
        private volatile ScheduledFuture<?> task;
        private volatile List<ApplicationInstance> lastSnapshot;

        private Subscription(ApplicationKey application, ApplicationRegistryListener listener) {
            this.application = application;
            this.listener = listener;
        }

        private void poll() {
            if (stopped.get() || closed.get()) {
                return;
            }
            try {
                ApplicationDirectorySnapshot result = snapshot(application);
                if (!stopped.get()) {
                    if (!result.instances().equals(lastSnapshot)) {
                        listener.onSnapshot(result);
                        lastSnapshot = result.instances();
                    }
                    listener.onSynchronizationSucceeded();
                    unhealthy.set(false);
                }
            } catch (Exception error) {
                if (unhealthy.compareAndSet(false, true) && !stopped.get()) {
                    try {
                        listener.onSynchronizationFailed(error);
                    } catch (RuntimeException callbackFailure) {
                        LOGGER.debug("Consul V2 subscription rejected failure notification");
                    }
                }
            }
        }

        @Override
        public void close() {
            if (stopped.compareAndSet(false, true)) {
                subscriptions.remove(this);
                executor.cancel(task);
            }
        }
    }
}
