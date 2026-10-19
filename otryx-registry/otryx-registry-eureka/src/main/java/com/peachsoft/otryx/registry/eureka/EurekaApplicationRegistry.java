package com.peachsoft.otryx.registry.eureka;

import com.fasterxml.jackson.databind.JsonNode;
import com.peachsoft.otryx.api.ApplicationInstance;
import com.peachsoft.otryx.api.ApplicationKey;
import com.peachsoft.otryx.registry.ApplicationDirectorySnapshot;
import com.peachsoft.otryx.registry.ApplicationManifestRepository;
import com.peachsoft.otryx.registry.ApplicationMetadataBackend;
import com.peachsoft.otryx.registry.ApplicationRegistrar;
import com.peachsoft.otryx.registry.ApplicationRegistry;
import com.peachsoft.otryx.registry.ApplicationRegistryListener;
import com.peachsoft.otryx.registry.ChannelProtection;
import com.peachsoft.otryx.registry.RegistryCapabilities;
import com.peachsoft.otryx.registry.RegistryCapability;
import com.peachsoft.otryx.registry.RegistryChannel;
import com.peachsoft.otryx.registry.RegistryFailureSanitizer;
import com.peachsoft.otryx.registry.RegistrySecurityDescriptor;
import com.peachsoft.otryx.registry.RegistrySecurityException;
import com.peachsoft.otryx.registry.RegistrySecurityFailure;
import com.peachsoft.otryx.registry.RegistrySubscription;
import com.peachsoft.otryx.registry.ServiceMappingCandidateSource;
import com.peachsoft.otryx.registry.ServiceMappingKey;
import com.peachsoft.otryx.registry.ServiceMappingOwner;
import com.peachsoft.otryx.registry.ServiceMappingRepository;
import com.peachsoft.otryx.registry.ServiceMappingSnapshot;
import com.peachsoft.otryx.registry.http.HttpRegistryClient;
import java.net.HttpURLConnection;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
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
 * 原生 Eureka 应用成员 + 外置强一致元数据仓库的单生命周期控制面。
 *
 * <p>只有 Eureka 发布 Provider、Consumer 或 BOTH 的真实成员；Provider
 * 端点只取自状态 UP、原生 Port enabled 且通过 V2 角色归属校验的 InstanceInfo。
 * Consumer 也登记成员，但端口为原生禁用状态，不可能进入 RPC Provider 路由。
 * 不查询原有 ServiceKey Registry，也不会向 Metadata Registry 双重登记成员。
 *
 * <p>外置 CAS Mapping 只提供候选集合，必须经 Eureka 真实成员交集后返回，
 * 最终由 Core 验证 Manifest 与 Schema。所有阻塞 HTTP 调用在有界控制面
 * Worker 中执行；订阅失败通知与健康空目录语义严格分离。
 *
 * @Author OpenAI
 * @Version 1.0.0-SNAPSHOT
 * @CreateTime 2026/10/16 18:09
 */
public final class EurekaApplicationRegistry implements ApplicationRegistry, ApplicationRegistrar,
        ApplicationMetadataBackend {

    private static final Logger LOGGER = LoggerFactory.getLogger(EurekaApplicationRegistry.class);
    private static final RegistryCapabilities CAPABILITIES = RegistryCapabilities.of(
            RegistryCapability.REGISTRATION, RegistryCapability.SUBSCRIPTION,
            RegistryCapability.LEASE, RegistryCapability.HEALTH, RegistryCapability.METADATA,
            RegistryCapability.WEIGHT);
    private static final int MAX_MEMBERS = 256;
    private static final int MAX_SUBSCRIPTIONS = 128;
    private static final int MAX_DIRECTORY = 256;
    private static final int CLOSE_SECONDS = 3;
    private static final String STATUS_UNAVAILABLE = "Eureka V2 is closed";

    private final RegistrySecurityDescriptor memberProtection;
    private final HttpRegistryClient client;
    private final EurekaV2Executor executor;
    private final EurekaV2Identity identity;
    private final ApplicationRegistry metadataBackend;
    private final ApplicationManifestRepository manifests;
    private final ServiceMappingRepository mappings;
    private final ServiceMappingCandidateSource candidates;
    private final ServiceMappingRepository verifiedMappings = new EurekaMappingView();
    private final String memberHost;
    private final int leaseSeconds;
    private final int renewalSeconds;
    private final Duration polling;
    private final ConcurrentMap<String, Registration> registered = new ConcurrentHashMap<>();
    private final Set<Subscription> subscriptions = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean renewing = new AtomicBoolean();
    private final AtomicLong localRevision = new AtomicLong();
    private final Object registrationsLock = new Object();
    private final ScheduledFuture<?> heartbeatTask;

    EurekaApplicationRegistry(
            HttpRegistryClient client, EurekaV2Executor executor, EurekaV2Identity identity,
            ApplicationRegistry metadataBackend, ApplicationManifestRepository manifests,
            ServiceMappingRepository mappings, ServiceMappingCandidateSource candidates,
            String memberHost, Duration lease, Duration renewal, Duration polling) {
        this(client, executor, identity, metadataBackend, manifests, mappings, candidates,
                memberHost, lease, renewal, polling, RegistrySecurityDescriptor.unknown());
    }

    EurekaApplicationRegistry(
            HttpRegistryClient client, EurekaV2Executor executor, EurekaV2Identity identity,
            ApplicationRegistry metadataBackend, ApplicationManifestRepository manifests,
            ServiceMappingRepository mappings, ServiceMappingCandidateSource candidates,
            String memberHost, Duration lease, Duration renewal, Duration polling,
            RegistrySecurityDescriptor memberProtection) {
        this.memberProtection = Objects.requireNonNull(memberProtection, "memberProtection");
        this.client = Objects.requireNonNull(client, "client");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.identity = Objects.requireNonNull(identity, "identity");
        this.metadataBackend = Objects.requireNonNull(metadataBackend, "metadataBackend");
        this.manifests = Objects.requireNonNull(manifests, "manifests");
        this.mappings = Objects.requireNonNull(mappings, "mappings");
        this.candidates = Objects.requireNonNull(candidates, "candidates");
        this.memberHost = Objects.requireNonNull(memberHost, "memberHost");
        this.leaseSeconds = Math.toIntExact(Objects.requireNonNull(lease, "lease").toSeconds());
        this.renewalSeconds = Math.toIntExact(Objects.requireNonNull(renewal, "renewal").toSeconds());
        this.polling = Objects.requireNonNull(polling, "polling");
        heartbeatTask = executor.scheduleWithFixedDelay(this::scheduleRenew,
                renewal, renewal);
    }

    /**
     * 返回外置可靠、内容寻址的 Manifest。
     *
     * @return 外置 Metadata Backend 的 Manifest Repository
     */
    public ApplicationManifestRepository manifestRepository() {
        requireOpen();
        return manifests;
    }

    /**
     * 返回由 Eureka Member Health 过滤候选应用的 Mapping Repository。
     *
     * @return 带实际 Eureka 健康校验的 Mapping Repository
     */
    public ServiceMappingRepository serviceMappingRepository() {
        requireOpen();
        return verifiedMappings;
    }

    /** 描述实际 Eureka Member HTTP 链，不继承外置 Metadata 的认证事实。 */
    @Override
    public RegistrySecurityDescriptor securityDescriptor() {
        return memberProtection;
    }

    /** Eureka 没有全局原生 KV Revision，不能声明 Revision 能力。 */
    @Override
    public RegistryCapabilities capabilities() {
        return CAPABILITIES;
    }

    /** 注册实际 ApplicationKey，不会创建虚拟接口级实例。 */
    @Override
    public CompletionStage<Void> register(ApplicationInstance member) {
        Objects.requireNonNull(member, "member");
        identity.requireNamespace(member.application());
        requireOpen();
        String key = identity.localKey(member);
        return executor.submit("member-register", () -> {
            synchronized (registrationsLock) {
                if (!registered.containsKey(key) && registered.size() >= MAX_MEMBERS) {
                    throw new IllegalStateException("Eureka local membership capacity exceeded");
                }
                Registration previous = registered.get(key);
                String token = previous == null ? UUID.randomUUID().toString() : previous.token();
                registerNative(member, token);
                registered.put(key, new Registration(member, token));
                return null;
            }
        });
    }

    /** 只注销本进程仍持有的同一 Member 快照，不会删除重启后的新 Token。 */
    @Override
    public CompletionStage<Void> unregister(ApplicationInstance member) {
        Objects.requireNonNull(member, "member");
        identity.requireNamespace(member.application());
        if (closed.get()) {
            return CompletableFuture.completedFuture(null);
        }
        String key = identity.localKey(member);
        return executor.submit("member-unregister", () -> {
            synchronized (registrationsLock) {
                Registration existing = registered.get(key);
                if (existing != null && existing.member().equals(member)) {
                    deregisterNative(existing);
                    registered.remove(key, existing);
                }
                return null;
            }
        });
    }

    /** 仅返回确实 UP 且通过角色、元数据与端点校验的完整应用成员目录。 */
    @Override
    public CompletionStage<ApplicationDirectorySnapshot> lookup(ApplicationKey application) {
        identity.requireNamespace(application);
        requireOpen();
        return executor.submit("member-lookup", () -> snapshot(application));
    }

    /** 本地有界 Polling 订阅；故障通知不得替代健康空目录。 */
    @Override
    public RegistrySubscription subscribe(ApplicationKey application, ApplicationRegistryListener listener) {
        identity.requireNamespace(application);
        Objects.requireNonNull(listener, "listener");
        requireOpen();
        if (subscriptions.size() >= MAX_SUBSCRIPTIONS) {
            throw new IllegalStateException("Eureka subscription capacity exhausted");
        }
        Subscription result = new Subscription(application, listener);
        subscriptions.add(result);
        try {
            result.start();
        } catch (RuntimeException failure) {
            subscriptions.remove(result);
            throw failure;
        }
        return result;
    }

    private ApplicationDirectorySnapshot snapshot(ApplicationKey application) throws Exception {
        String app = identity.appName(application);
        HttpRegistryClient.Response response = client.request(
                HttpRegistryClient.METHOD_GET, EurekaProtocol.PATH_APPLICATIONS_PREFIX + encode(app), null);
        List<ApplicationInstance> members = new ArrayList<>();
        if (response.status() != HttpURLConnection.HTTP_NOT_FOUND) {
            requireSuccessful(response);
            JsonNode node = response.json().path(EurekaProtocol.FIELD_APPLICATION);
            if (!node.isObject()) {
                throw new RegistrySecurityException(RegistrySecurityFailure.METADATA_INTEGRITY_REJECTED);
            }
            JsonNode raw = node.path(EurekaProtocol.FIELD_INSTANCE);
            if (raw.isArray()) {
                if (raw.size() > MAX_DIRECTORY) {
                    throw new RegistrySecurityException(RegistrySecurityFailure.RESPONSE_LIMIT_EXCEEDED);
                }
                for (JsonNode child : raw) {
                    addMember(application, child, members);
                }
            } else if (raw.isObject()) {
                addMember(application, raw, members);
            } else {
                throw new RegistrySecurityException(RegistrySecurityFailure.METADATA_INTEGRITY_REJECTED);
            }
        }
        members.sort(Comparator.comparing(ApplicationInstance::instanceId));
        return new ApplicationDirectorySnapshot(application, members, localRevision.incrementAndGet());
    }

    private void addMember(ApplicationKey application, JsonNode source, List<ApplicationInstance> target) {
        ApplicationInstance member = identity.fromInstance(application, source);
        if (member != null) {
            target.add(member);
            if (target.size() > MAX_DIRECTORY) {
                throw new RegistrySecurityException(RegistrySecurityFailure.RESPONSE_LIMIT_EXCEEDED);
            }
        }
    }

    private void registerNative(ApplicationInstance member, String token) throws Exception {
        String app = identity.appName(member.application());
        HttpRegistryClient.Response response = client.request(
                HttpRegistryClient.METHOD_POST, EurekaProtocol.PATH_APPLICATIONS_PREFIX + encode(app),
                identity.nativeRegistration(member, token, memberHost, renewalSeconds, leaseSeconds));
        requireSuccessful(response);
    }

    private void renewNative(Registration member) throws Exception {
        ApplicationInstance applicationMember = member.member();
        String app = identity.appName(applicationMember.application());
        String id = identity.nativeInstanceId(applicationMember, member.token());
        HttpRegistryClient.Response response = client.request(
                HttpRegistryClient.METHOD_PUT,
                EurekaProtocol.PATH_APPLICATIONS_PREFIX + encode(app) + '/' + encode(id), null);
        if (response.status() == HttpURLConnection.HTTP_NOT_FOUND) {
            registerNative(applicationMember, member.token());
        } else {
            requireSuccessful(response);
        }
    }

    private void deregisterNative(Registration member) throws Exception {
        String app = identity.appName(member.member().application());
        String id = identity.nativeInstanceId(member.member(), member.token());
        HttpRegistryClient.Response response = client.request(
                HttpRegistryClient.METHOD_DELETE,
                EurekaProtocol.PATH_APPLICATIONS_PREFIX + encode(app) + '/' + encode(id), null);
        if (response.status() != HttpURLConnection.HTTP_NOT_FOUND) {
            requireSuccessful(response);
        }
    }

    private void scheduleRenew() {
        if (closed.get() || !renewing.compareAndSet(false, true)) {
            return;
        }
        executor.submit("member-renew", () -> {
            try {
                for (Map.Entry<String, Registration> entry : registered.entrySet()) {
                    if (closed.get()) {
                        break;
                    }
                    synchronized (registrationsLock) {
                        if (registered.get(entry.getKey()) != entry.getValue()) {
                            continue;
                        }
                        try {
                            renewNative(entry.getValue());
                        } catch (Exception failure) {
                            LOGGER.warn("Eureka lease renewal failed. errorType={}",
                                    failure.getClass().getSimpleName());
                        }
                    }
                }
            } finally {
                renewing.set(false);
            }
            return null;
        }).whenComplete((unused, failure) -> renewing.set(false));
    }

    private static String encode(String item) {
        return URLEncoder.encode(item, StandardCharsets.UTF_8);
    }

    private static void requireSuccessful(HttpRegistryClient.Response response) {
        if (!response.successful()) {
            throw new IllegalStateException(
                    "Eureka member control plane failed: HTTP status=" + response.status());
        }
    }

    private void requireOpen() {
        if (closed.get()) {
            throw new IllegalStateException(STATUS_UNAVAILABLE);
        }
    }

    /** 有界停止注册续期并注销本进程的 Native Instance，释放两个 Backend 的资源。 */
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        heartbeatTask.cancel(true);
        List.copyOf(subscriptions).forEach(Subscription::close);
        List<CompletableFuture<Void>> cleanup = new ArrayList<>();
        for (Registration registration : List.copyOf(registered.values())) {
            cleanup.add(executor.submit("member-close", () -> {
                synchronized (registrationsLock) {
                    deregisterNative(registration);
                    return null;
                }
            }));
        }
        try {
            CompletableFuture.allOf(cleanup.toArray(CompletableFuture[]::new))
                    .get(CLOSE_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (Exception failure) {
            LOGGER.warn("Eureka member cleanup exceeded budget. errorType={}",
                    failure.getClass().getSimpleName());
        } finally {
            registered.clear();
            executor.close();
            client.close();
            metadataBackend.close();
        }
    }

    private record Registration(ApplicationInstance member, String token) {
    }

    private final class EurekaMappingView implements ServiceMappingRepository {

        /** Mapping 查询实际消费 Metadata 和 Eureka 健康两条链，保留每个来源。 */
        @Override
        public RegistrySecurityDescriptor securityDescriptor() {
            List<ChannelProtection> sources = new ArrayList<>(RegistrySecurityDescriptor
                    .from(mappings, RegistryChannel.MAPPING).channels().get(RegistryChannel.MAPPING));
            sources.addAll(RegistrySecurityDescriptor.from(EurekaApplicationRegistry.this, RegistryChannel.MEMBERSHIP)
                    .channels().get(RegistryChannel.MEMBERSHIP));
            return new RegistrySecurityDescriptor(Map.of(RegistryChannel.MAPPING, sources));
        }

        @Override
        public CompletionStage<ServiceMappingOwner> claim(ApplicationKey application, String instanceId) {
            identity.requireNamespace(application);
            requireOpen();
            return mappings.claim(application, instanceId);
        }

        @Override
        public CompletionStage<Void> publish(ServiceMappingOwner owner,
                Set<com.peachsoft.otryx.api.ServiceKey> services) {
            identity.requireNamespace(owner.application());
            requireOpen();
            return mappings.publish(owner, services);
        }

        @Override
        public CompletionStage<Void> revoke(ServiceMappingOwner owner) {
            identity.requireNamespace(owner.application());
            requireOpen();
            return mappings.revoke(owner);
        }

        /** Candidate 数据只交付给真实 Eureka UP Provider 的应用集合。 */
        @Override
        public CompletionStage<ServiceMappingSnapshot> lookup(ServiceMappingKey key) {
            Objects.requireNonNull(key, "key");
            identity.requireNamespace(new ApplicationKey(key.namespace(), "mapping"));
            requireOpen();
            return candidates.lookupCandidateOwners(key).thenCompose(candidate -> {
                if (candidate == null) {
                    throw new RegistrySecurityException(RegistrySecurityFailure.METADATA_INTEGRITY_REJECTED);
                }
                if (!candidate.key().equals(key)) {
                    throw new RegistrySecurityException(RegistrySecurityFailure.METADATA_REFERENCE_MISMATCH);
                }
                Set<ApplicationKey> healthy = new HashSet<>();
                CompletionStage<Void> results = CompletableFuture.completedFuture(null);
                for (Map.Entry<ApplicationKey, Set<String>> owner : candidate.ownerInstances().entrySet()) {
                    ApplicationKey app = owner.getKey();
                    Set<String> ownerInstanceIds = owner.getValue();
                    results = results.thenCompose(ignored ->
                            EurekaApplicationRegistry.this.lookup(app)
                                    .thenAccept(directory -> {
                                        if (directory.instances().stream().anyMatch(member ->
                                                member.routableProvider()
                                                        && ownerInstanceIds.contains(member.instanceId()))) {
                                            healthy.add(app);
                                        }
                                    }));
                }
                return results.thenApply(ignored ->
                        new ServiceMappingSnapshot(key, healthy, candidate.revision()));
            });
        }

    }

    private final class Subscription implements RegistrySubscription {
        private final ApplicationKey application;
        private final ApplicationRegistryListener listener;
        private final AtomicBoolean stopped = new AtomicBoolean();
        private final AtomicBoolean inFlight = new AtomicBoolean();
        private volatile ScheduledFuture<?> scheduled;
        private volatile List<ApplicationInstance> previous;

        private Subscription(ApplicationKey application, ApplicationRegistryListener listener) {
            this.application = application;
            this.listener = listener;
        }

        private void start() {
            scheduled = executor.scheduleWithFixedDelay(this::refresh, Duration.ZERO, polling);
        }

        private void refresh() {
            if (closed.get() || stopped.get() || !inFlight.compareAndSet(false, true)) {
                return;
            }
            executor.submit("member-subscription", () -> {
                try {
                    ApplicationDirectorySnapshot next = snapshot(application);
                    if (!stopped.get()) {
                        if (!next.instances().equals(previous)) {
                            previous = next.instances();
                            listener.onSnapshot(next);
                        }
                        listener.onSynchronizationSucceeded();
                    }
                } catch (Throwable failure) {
                    if (!stopped.get()) {
                        try {
                            listener.onSynchronizationFailed(RegistryFailureSanitizer.sanitize(failure));
                        } catch (RuntimeException callbackFailure) {
                            LOGGER.warn("Eureka registry listener failed. errorType={}",
                                    callbackFailure.getClass().getSimpleName());
                        }
                    }
                } finally {
                    inFlight.set(false);
                }
                return null;
            }).whenComplete((ignored, failure) -> inFlight.set(false));
        }

        @Override
        public void close() {
            if (stopped.compareAndSet(false, true)) {
                ScheduledFuture<?> handle = scheduled;
                if (handle != null) {
                    handle.cancel(false);
                }
                subscriptions.remove(this);
            }
        }
    }
}
