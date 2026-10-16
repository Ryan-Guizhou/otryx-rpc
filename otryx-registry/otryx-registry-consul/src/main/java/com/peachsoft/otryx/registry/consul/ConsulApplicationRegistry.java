package com.peachsoft.otryx.registry.consul;

import com.fasterxml.jackson.databind.JsonNode;
import com.peachsoft.otryx.api.ApplicationInstance;
import com.peachsoft.otryx.api.ApplicationKey;
import com.peachsoft.otryx.api.RpcEndpoint;
import com.peachsoft.otryx.observability.RpcObserver;
import com.peachsoft.otryx.registry.ApplicationDirectorySnapshot;
import com.peachsoft.otryx.registry.ApplicationManifestRepository;
import com.peachsoft.otryx.registry.ApplicationMetadataBackend;
import com.peachsoft.otryx.registry.ApplicationRegistrar;
import com.peachsoft.otryx.registry.ApplicationRegistry;
import com.peachsoft.otryx.registry.ApplicationRegistryListener;
import com.peachsoft.otryx.registry.RegistryCapabilities;
import com.peachsoft.otryx.registry.RegistryCapability;
import com.peachsoft.otryx.registry.RegistrySubscription;
import com.peachsoft.otryx.registry.ServiceMappingRepository;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
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
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Consul 原生应用级注册中心，Provider/Consumer/BOTH 共享 Application Membership。
 *
 * <p>Agent TTL 是真实成员健康状态；纯 Consumer 服务无 RPC Address/Port。
 * 独立的 Consul KV 保存不可变 Manifest 与按实例 CAS Fencing 的完整 Mapping。
 * 查询和轮询在容量受限的工作线程执行，应用层 Observer/Listener 不在 EventLoop 执行。
 *
 * <p>Consul 原生 Service Meta 只是路由发现输入，不能充当认证凭证；RPC 方法授权
 * 仍由 Transport mTLS 与服务器侧 Authorizer 决定。
 *
 * @Author OpenAI
 * @Version 1.0.0-SNAPSHOT
 * @CreateTime 2026/10/16 15:00
 */
public final class ConsulApplicationRegistry implements ApplicationRegistry, ApplicationRegistrar, ApplicationMetadataBackend {

    private static final Logger LOGGER = LoggerFactory.getLogger(ConsulApplicationRegistry.class);
    private static final RegistryCapabilities CAPABILITIES = RegistryCapabilities.of(
            RegistryCapability.REGISTRATION, RegistryCapability.SUBSCRIPTION,
            RegistryCapability.LEASE, RegistryCapability.METADATA, RegistryCapability.HEALTH,
            RegistryCapability.WEIGHT);

    private static final int MAX_LOCAL_MEMBERS = 256;
    private static final int MAX_SUBSCRIPTIONS = 128;
    private static final int MAX_DIRECTORY_MEMBERS = 256;
    private static final int CLOSE_BUDGET_SECONDS = 3;
    private static final int MIN_CONSUL_CHECK_TTL_SECONDS = 10;
    private static final String METHOD_GET = "GET";
    private static final String METHOD_PUT = "PUT";
    private static final String PATH_SERVICE_REGISTER = "/v1/agent/service/register?replace-existing-checks=true";
    private static final String PATH_SERVICE_ID = "/v1/agent/service/";
    private static final String PATH_SERVICE_DEREGISTER = "/v1/agent/service/deregister/";
    private static final String PATH_CHECK_PASS = "/v1/agent/check/pass/";
    private static final String PATH_HEALTH_SERVICE = "/v1/health/service/";
    private static final String PREFIX_SERVICE_CHECK = "service:";
    private static final String QUERY_PASSING = "?passing=true";
    private static final String FIELD_ID = "ID";
    private static final String FIELD_NAME = "Name";
    private static final String FIELD_SERVICE = "Service";
    private static final String FIELD_META = "Meta";
    private static final String FIELD_CHECK = "Check";
    private static final String FIELD_TTL = "TTL";
    private static final String FIELD_STATUS = "Status";
    private static final String FIELD_ADDRESS = "Address";
    private static final String FIELD_PORT = "Port";
    private static final String FIELD_DEREGISTER_AFTER = "DeregisterCriticalServiceAfter";
    private static final String META_TOKEN = "otryxV2Token";
    private static final String STATUS_CRITICAL = "critical";
    private static final String DEREGISTER_AFTER = "1m";
    private static final String SECONDS = "s";

    private final ConsulV2Client client;
    private final ConsulV2Executor executor;
    private final ConsulV2Identity identity;
    private final ConsulV2MemberSessions sessions;
    private final Duration heartbeat;
    private final Duration polling;
    private final long ttlSeconds;
    private final RpcObserver observer;
    private final ConsulApplicationManifestRepository manifests;
    private final ConsulServiceMappingRepository mappings;
    private final ConcurrentMap<String, Registration> registered = new ConcurrentHashMap<>();
    private final Set<Subscription> subscriptions = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean heartbeatBusy = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicLong revision = new AtomicLong();
    private final Object mutationMonitor = new Object();
    private final ScheduledFuture<?> heartbeatTask;

    ConsulApplicationRegistry(
            ConsulV2Client client, ConsulV2Executor executor,
            ConsulV2Identity identity, long ttlSeconds, Duration heartbeat,
            Duration polling, RpcObserver observer) {
        this.client = Objects.requireNonNull(client, "client");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.identity = Objects.requireNonNull(identity, "identity");
        this.sessions = new ConsulV2MemberSessions(client, identity, ttlSeconds);
        if (ttlSeconds < MIN_CONSUL_CHECK_TTL_SECONDS || heartbeat == null
                || heartbeat.isNegative() || heartbeat.isZero()
                || heartbeat.compareTo(Duration.ofSeconds(ttlSeconds)) >= 0
                || polling == null || polling.isNegative() || polling.isZero()) {
            throw new IllegalArgumentException("Invalid Consul TTL or control polling interval");
        }
        this.ttlSeconds = ttlSeconds;
        this.heartbeat = heartbeat;
        this.polling = polling;
        this.observer = observer == null ? RpcObserver.noop() : observer;
        this.manifests = new ConsulApplicationManifestRepository(client, executor, identity);
        this.mappings = new ConsulServiceMappingRepository(client, executor, identity);
        this.heartbeatTask = executor.scheduleWithFixedDelay(
                this::scheduleHeartbeat, heartbeat, heartbeat);
    }

    /**
     * 返回和应用成员使用相同 Consul ACL、命名空间和生命周期的 Manifest 仓库。
     *
     * @return 由当前 Registry 生命周期拥有的 Manifest Repository
     */
    public ApplicationManifestRepository manifestRepository() {
        requireOpen();
        return manifests;
    }

    /**
     * 返回同一 Consul KV 域下按实例 Fencing 的服务映射仓库。
     *
     * @return 由当前 Registry 生命周期拥有的 Mapping Repository
     */
    public ServiceMappingRepository serviceMappingRepository() {
        requireOpen();
        return mappings;
    }

    /** Consul 原生 TTL 服务健康和轮询订阅；Revision 不宣称 Consul 原生全局版本。 */
    @Override
    public RegistryCapabilities capabilities() {
        return CAPABILITIES;
    }

    /** 发布应用成员前先通过 Consul Session KV Acquire 原子声明身份所有权。 */
    @Override
    public CompletionStage<Void> register(ApplicationInstance instance) {
        Objects.requireNonNull(instance, "instance");
        identity.requireNamespace(instance.application());
        requireRoutable(instance);
        requireOpen();
        String id = identity.serviceId(instance);
        return executor.submit("member-register", () -> {
            synchronized (mutationMonitor) {
                if (!registered.containsKey(id) && registered.size() >= MAX_LOCAL_MEMBERS) {
                    throw new IllegalStateException("Consul local member capacity exhausted");
                }
                Registration previous = registered.get(id);
                String token = previous == null ? UUID.randomUUID().toString() : previous.token();
                String session;
                if (previous == null) {
                    session = sessions.createAndAcquire(instance, token);
                } else {
                    if (!sessions.renewAndVerify(previous.member(), previous.session(), token)) {
                        throw new IllegalStateException("Consul member Session ownership was lost");
                    }
                    session = previous.session();
                }
                try {
                    registerOwned(instance, token, session);
                    registered.put(id, new Registration(instance, token, session));
                } catch (Exception failure) {
                    if (previous == null) {
                        try {
                            sessions.destroy(session);
                        } catch (Exception cleanupError) {
                            failure.addSuppressed(cleanupError);
                        }
                    }
                    throw failure;
                }
                return null;
            }
        });
    }

    /** 只允许持有当前 Session 的进程注销，旧角色快照与旧代请求不会删除新成员。 */
    @Override
    public CompletionStage<Void> unregister(ApplicationInstance instance) {
        Objects.requireNonNull(instance, "instance");
        identity.requireNamespace(instance.application());
        if (closed.get()) {
            return CompletableFuture.completedFuture(null);
        }
        String id = identity.serviceId(instance);
        return executor.submit("member-unregister", () -> {
            synchronized (mutationMonitor) {
                Registration local = registered.get(id);
                if (local != null && local.member().equals(instance)) {
                    removeIfOwned(id, local.token());
                    registered.remove(id, local);
                    sessions.destroy(local.session());
                }
                return null;
            }
        });
    }

    /** 查询真实 Passing 的完整应用目录，包含无端点 Consumer 成员。 */
    @Override
    public CompletionStage<ApplicationDirectorySnapshot> lookup(ApplicationKey application) {
        identity.requireNamespace(application);
        requireOpen();
        return executor.submit("member-lookup", () -> snapshot(application));
    }

    /** 受限的本地轮询订阅；故障时发出健康失败事件，不伪造空快照。 */
    @Override
    public RegistrySubscription subscribe(ApplicationKey application, ApplicationRegistryListener listener) {
        identity.requireNamespace(application);
        Objects.requireNonNull(listener, "listener");
        requireOpen();
        if (subscriptions.size() >= MAX_SUBSCRIPTIONS) {
            throw new IllegalStateException("Consul subscription capacity exceeded");
        }
        Subscription subscription = new Subscription(application, listener);
        subscriptions.add(subscription);
        try {
            subscription.start();
        } catch (RuntimeException error) {
            subscriptions.remove(subscription);
            throw error;
        }
        return subscription;
    }

    private ApplicationDirectorySnapshot snapshot(ApplicationKey application) throws Exception {
        ConsulV2Client.JsonResult response = client.json(
                METHOD_GET, PATH_HEALTH_SERVICE + identity.serviceName(application) + QUERY_PASSING, null);
        List<ApplicationInstance> result = new ArrayList<>();
        if (!response.missing()) {
            JsonNode rows = response.body();
            if (rows == null || !rows.isArray() || rows.size() > MAX_DIRECTORY_MEMBERS) {
                throw new IllegalStateException("Consul application directory exceeds capacity");
            }
            for (JsonNode row : rows) {
                ApplicationInstance member = identity.fromHealth(application, row);
                if (member != null) {
                    result.add(member);
                }
            }
        }
        result.sort(Comparator.comparing(ApplicationInstance::instanceId));
        return new ApplicationDirectorySnapshot(application, result, revision.incrementAndGet());
    }

    private void registerOwned(ApplicationInstance member, String token, String session) throws Exception {
        // 原生 Agent Service ID 无 CAS；必须先证明当前进程持有权威 KV Session。
        if (!sessions.owns(member, session, token)) {
            throw new IllegalStateException("Consul member Session claim was superseded");
        }
        String id = identity.serviceId(member);
        Map<String, Object> body = new HashMap<>();
        body.put(FIELD_ID, id);
        body.put(FIELD_NAME, identity.serviceName(member.application()));
        body.put(FIELD_META, identity.metadata(member, token));
        body.put(FIELD_CHECK, Map.of(
                FIELD_TTL, ttlSeconds + SECONDS,
                FIELD_DEREGISTER_AFTER, DEREGISTER_AFTER,
                FIELD_STATUS, STATUS_CRITICAL));
        if (member.routableProvider()) {
            body.put(FIELD_ADDRESS, member.rpcEndpoint().host());
            body.put(FIELD_PORT, member.rpcEndpoint().port());
        }
        client.json(METHOD_PUT, PATH_SERVICE_REGISTER, body);
        pass(id);
    }

    private void pass(String id) throws Exception {
        ConsulV2Client.JsonResult result = client.json(
                METHOD_PUT, PATH_CHECK_PASS + encode(PREFIX_SERVICE_CHECK + id), null);
        if (result.missing()) {
            throw new IllegalStateException("Consul TTL health check is missing");
        }
    }

    private void removeIfOwned(String id, String token) throws Exception {
        ConsulV2Client.JsonResult current = client.json(METHOD_GET, PATH_SERVICE_ID + encode(id), null);
        if (!current.missing()) {
            if (current.body() == null
                    || !token.equals(current.body().path(FIELD_META).path(META_TOKEN).asText())) {
                return;
            }
            client.json(METHOD_PUT, PATH_SERVICE_DEREGISTER + encode(id), null);
        }
    }

    private void scheduleHeartbeat() {
        if (closed.get() || !heartbeatBusy.compareAndSet(false, true)) {
            return;
        }
        executor.submit("member-heartbeat", () -> {
            try {
                for (Map.Entry<String, Registration> entry : registered.entrySet()) {
                    if (closed.get()) {
                        break;
                    }
                    try {
                        heartbeatMember(entry.getKey(), entry.getValue());
                    } catch (Exception failure) {
                        LOGGER.warn("Consul member heartbeat failed. errorType={}",
                                failure.getClass().getSimpleName());
                    }
                }
            } finally {
                heartbeatBusy.set(false);
            }
            return null;
        }).whenComplete((ignored, error) -> heartbeatBusy.set(false));
    }

    private void heartbeatMember(String id, Registration previous) throws Exception {
        synchronized (mutationMonitor) {
            if (registered.get(id) != previous || closed.get()) {
                return;
            }
            if (!sessions.renewAndVerify(previous.member(), previous.session(), previous.token())) {
                // 会话已失效时，先使本进程旧 Agent 成员不可路由，再安全重新声明。
                removeIfOwned(id, previous.token());
                String newSession = sessions.createAndAcquire(previous.member(), previous.token());
                try {
                    registerOwned(previous.member(), previous.token(), newSession);
                    registered.put(id, new Registration(previous.member(), previous.token(), newSession));
                } catch (Exception failure) {
                    try {
                        sessions.destroy(newSession);
                    } catch (Exception cleanupError) {
                        failure.addSuppressed(cleanupError);
                    }
                    throw failure;
                }
                return;
            }
            // 即使 Session 仍有效，也不可替其他进程刷新被覆盖的 Service Check。
            ConsulV2Client.JsonResult current = client.json(METHOD_GET, PATH_SERVICE_ID + encode(id), null);
            if (!current.missing()) {
                if (current.body() == null
                        || !previous.token().equals(current.body().path(FIELD_META).path(META_TOKEN).asText())) {
                    throw new IllegalStateException("Consul Agent member ownership changed");
                }
            }
            ConsulV2Client.JsonResult passed = client.json(
                    METHOD_PUT, PATH_CHECK_PASS + encode(PREFIX_SERVICE_CHECK + id), null);
            if (passed.missing() || current.missing()) {
                registerOwned(previous.member(), previous.token(), previous.session());
            }
        }
    }

    private static void requireRoutable(ApplicationInstance instance) {
        if (instance.routableProvider()) {
            RpcEndpoint endpoint = instance.rpcEndpoint();
            if (endpoint.port() < 1 || endpoint.host().isBlank()
                    || ConsulV2Identity.isUnspecified(endpoint.host())) {
                throw new IllegalArgumentException("Consul Provider RPC endpoint is not routable");
            }
        }
    }

    private void requireOpen() {
        if (closed.get()) {
            throw new IllegalStateException("Consul application registry is closed");
        }
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    /**
     * 在有限关闭预算内注销本进程成员，剩余成员由 Consul TTL 自动失效。
     * 不删除共享 Manifest/Mapping 或其他实例记录。
     */
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        heartbeatTask.cancel(true);
        for (Subscription subscription : List.copyOf(subscriptions)) {
            subscription.close();
        }
        List<CompletableFuture<Void>> cleanup = new ArrayList<>();
        registered.forEach((id, item) ->
                cleanup.add(executor.submit("member-close", () -> {
                    synchronized (mutationMonitor) {
                        try {
                            removeIfOwned(id, item.token());
                        } finally {
                            sessions.destroy(item.session());
                        }
                        return null;
                    }
                })));
        try {
            CompletableFuture.allOf(cleanup.toArray(CompletableFuture[]::new))
                    .get(CLOSE_BUDGET_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            LOGGER.warn("Consul close was interrupted.");
        } catch (Exception failure) {
            LOGGER.warn("Consul close exceeded cleanup budget. errorType={}",
                    failure.getClass().getSimpleName());
        } finally {
            registered.clear();
            executor.close();
            client.close();
        }
    }

    private record Registration(ApplicationInstance member, String token, String session) {
    }

    private final class Subscription implements RegistrySubscription {
        private final ApplicationKey application;
        private final ApplicationRegistryListener listener;
        private final AtomicBoolean stopped = new AtomicBoolean();
        private final AtomicBoolean pollingBusy = new AtomicBoolean();
        private volatile ScheduledFuture<?> scheduled;
        private volatile List<ApplicationInstance> lastSnapshot;

        private Subscription(ApplicationKey application, ApplicationRegistryListener listener) {
            this.application = application;
            this.listener = listener;
        }

        private void start() {
            scheduled = executor.scheduleWithFixedDelay(this::refresh,
                    Duration.ZERO, polling);
        }

        private void refresh() {
            if (stopped.get() || closed.get() || !pollingBusy.compareAndSet(false, true)) {
                return;
            }
            executor.submit("member-subscription", () -> {
                try {
                    ApplicationDirectorySnapshot current = snapshot(application);
                    if (!stopped.get()) {
                        if (!current.instances().equals(lastSnapshot)) {
                            lastSnapshot = current.instances();
                            listener.onSnapshot(current);
                        }
                        listener.onSynchronizationSucceeded();
                    }
                } catch (Throwable failure) {
                    if (!stopped.get()) {
                        try {
                            listener.onSynchronizationFailed(failure);
                        } catch (RuntimeException callbackError) {
                            LOGGER.warn("Consul health callback rejected an error. errorType={}",
                                    callbackError.getClass().getSimpleName());
                        }
                    }
                } finally {
                    pollingBusy.set(false);
                }
                return null;
            }).whenComplete((ignored, error) -> pollingBusy.set(false));
        }

        @Override
        public void close() {
            if (stopped.compareAndSet(false, true)) {
                if (scheduled != null) {
                    scheduled.cancel(false);
                }
                subscriptions.remove(this);
            }
        }
    }
}
