package com.peachsoft.otryx.registry.etcd;

import com.peachsoft.otryx.api.ApplicationInstance;
import com.peachsoft.otryx.api.ApplicationKey;
import com.peachsoft.otryx.api.ApplicationManifest;
import com.peachsoft.otryx.api.RpcEndpoint;
import com.peachsoft.otryx.observability.RpcObserver;
import com.peachsoft.otryx.observability.RpcRegistryOperation;
import com.peachsoft.otryx.observability.RpcRegistryRecoveryAction;
import com.peachsoft.otryx.registry.ApplicationDirectorySnapshot;
import com.peachsoft.otryx.registry.ApplicationManifestRepository;
import com.peachsoft.otryx.registry.ApplicationMetadataBackend;
import com.peachsoft.otryx.registry.ApplicationRegistrar;
import com.peachsoft.otryx.registry.ApplicationRegistry;
import com.peachsoft.otryx.registry.ApplicationRegistryListener;
import com.peachsoft.otryx.registry.RegistryCapabilities;
import com.peachsoft.otryx.registry.RegistryCapability;
import com.peachsoft.otryx.registry.RegistrySecurityDescriptor;
import com.peachsoft.otryx.registry.RegistrySecurityException;
import com.peachsoft.otryx.registry.RegistrySecurityFailure;
import com.peachsoft.otryx.registry.RegistrySubscription;
import com.peachsoft.otryx.registry.ServiceMappingRepository;
import io.etcd.jetcd.ByteSequence;
import io.etcd.jetcd.Client;
import io.etcd.jetcd.KeyValue;
import io.etcd.jetcd.Watch;
import io.etcd.jetcd.kv.GetResponse;
import io.etcd.jetcd.lease.LeaseKeepAliveResponse;
import io.etcd.jetcd.op.Cmp;
import io.etcd.jetcd.op.CmpTarget;
import io.etcd.jetcd.op.Op;
import io.etcd.jetcd.options.DeleteOption;
import io.etcd.jetcd.options.GetOption;
import io.etcd.jetcd.options.LeaseOption;
import io.etcd.jetcd.options.PutOption;
import io.etcd.jetcd.options.WatchOption;
import io.etcd.jetcd.support.CloseableClient;
import io.grpc.stub.StreamObserver;
import java.nio.charset.StandardCharsets;
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
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Etcd V2 应用级成员注册中心，同时持有共享的 Manifest 与 Mapping 仓库生命周期。
 *
 * <p>Provider、Consumer、BOTH 均按 ApplicationKey+instanceId 建立一份租约成员；
 * 纯 Consumer 不发布 RPC Endpoint。用 Etcd CAS + 随机进程所有权 Token 防止旧
 * 注册请求或注销误写。后台 Watch 先 Range 再从 revision+1 订阅，断线/压缩后
 * 重新全量同步并显式报告健康故障。所有网络操作为异步，Observer 与用户监听器
 * 不在 RPC EventLoop 执行；调用方仍须设置自己的 Deadline。
 *
 * @Author OpenAI
 * @Version 1.0.0-SNAPSHOT
 * @CreateTime 2026/10/16 10:33
 */
public final class EtcdApplicationRegistry implements ApplicationRegistry, ApplicationRegistrar,
        ApplicationMetadataBackend {

    private static final Logger LOGGER = LoggerFactory.getLogger(EtcdApplicationRegistry.class);
    private static final int MAX_APPLICATION_MEMBERS = 256;
    private static final int MAX_ACTIVE_MEMBERS = 512;
    private static final int MAX_SUBSCRIPTIONS = 128;
    private static final int MAX_MEMBER_BYTES_TOTAL = 4 * 1024 * 1024;
    private static final int MAX_CAS_RETRIES = 12;
    private static final long RETRY_BASE_MILLIS = 200L;
    private static final long RETRY_MAX_MILLIS = 15_000L;
    private static final long LEASE_GRANT_TIMEOUT_SECONDS = 5L;
    private static final RegistryCapabilities CAPABILITIES = RegistryCapabilities.of(
            RegistryCapability.REGISTRATION,
            RegistryCapability.SUBSCRIPTION,
            RegistryCapability.REVISION,
            RegistryCapability.LEASE,
            RegistryCapability.WEIGHT,
            RegistryCapability.METADATA,
            RegistryCapability.HEALTH);

    private final RegistrySecurityDescriptor descriptor;
    private final Client client;
    private final EtcdV2Paths paths;
    private final long leaseTtlSeconds;
    private final ScheduledExecutorService controlExecutor;
    private final RpcObserver observer;
    private final EtcdApplicationManifestRepository manifests;
    private final EtcdServiceMappingRepository mappings;
    private final Object leaseMonitor = new Object();
    private final Object mutationMonitor = new Object();
    private final ConcurrentMap<String, MemberRegistration> registrations = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, CompletableFuture<Void>> mutations = new ConcurrentHashMap<>();
    private final Set<Subscription> subscriptions = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean recoveryScheduled = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private volatile CompletableFuture<Long> leaseFuture;
    private volatile long activeLeaseId;
    private volatile CloseableClient keepAliveHandle;

    /**
     * 创建拥有专用 Jetcd Client 的完整 V2 应用级注册中心。
     *
     * @param endpoints Etcd 服务地址，必须明确可连接
     * @param leaseTtlSeconds 实例租约 TTL 秒数，至少为 1
     * @param namespace 应用命名空间，不得为空
     */
    public EtcdApplicationRegistry(String[] endpoints, long leaseTtlSeconds, String namespace) {
        this(Client.builder().endpoints(endpoints).build(), leaseTtlSeconds, namespace, RpcObserver.noop());
    }

    EtcdApplicationRegistry(Client client, long leaseTtlSeconds, String namespace, RpcObserver observer) {
        this(client, leaseTtlSeconds, namespace, observer, RegistrySecurityDescriptor.unknown());
    }

    EtcdApplicationRegistry(
            Client client, long leaseTtlSeconds, String namespace, RpcObserver observer,
            RegistrySecurityDescriptor descriptor) {
        this.descriptor = Objects.requireNonNull(descriptor, "descriptor");
        this.client = Objects.requireNonNull(client, "client");
        if (leaseTtlSeconds < 1 || leaseTtlSeconds > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Invalid Etcd lease TTL");
        }
        this.paths = new EtcdV2Paths(namespace);
        this.leaseTtlSeconds = leaseTtlSeconds;
        this.observer = observer == null ? RpcObserver.noop() : observer;
        this.controlExecutor = Executors.newScheduledThreadPool(2, runnable -> {
            Thread thread = new Thread(runnable, "otryx-etcd-v2-control");
            thread.setDaemon(true);
            return thread;
        });
        this.manifests = new EtcdApplicationManifestRepository(client, paths, descriptor);
        this.mappings = new EtcdServiceMappingRepository(client, paths, descriptor);
    }

    /**
     * 返回与实例成员共享 Jetcd Client 的不可变 Manifest 存储。
     *
     * @return 共享生命周期的 Manifest Repository
     */
    public ApplicationManifestRepository manifestRepository() {
        requireOpen();
        return manifests;
    }

    /**
     * 返回与实例成员共享 Etcd Namespace 的分布式 Mapping 存储。
     *
     * @return 共享生命周期的 Mapping Repository
     */
    public ServiceMappingRepository serviceMappingRepository() {
        requireOpen();
        return mappings;
    }

    /** 仅 Factory 安装真实客户端后的来源被描述；原始 SDK 构造保持 UNKNOWN。 */
    @Override
    public RegistrySecurityDescriptor securityDescriptor() {
        return descriptor;
    }

    /** 返回真实 Lease/Subscription/Revision 能力。 */
    @Override
    public RegistryCapabilities capabilities() {
        return CAPABILITIES;
    }

    /**
     * 注册或更新同一个进程成员，同一实例承载多个接口时不会重复写 Etcd 成员键。
     *
     * <p>同名且由其他活跃进程持有的成员拒绝抢占，必须等待其租约撤销或过期。
     */
    @Override
    public CompletionStage<Void> register(ApplicationInstance instance) {
        Objects.requireNonNull(instance, "instance");
        paths.requireNamespace(instance.application());
        requireRoutableAddress(instance);
        if (closed.get()) {
            return unavailable();
        }
        String path = paths.memberKey(instance.application(), instance.instanceId());
        if (!registrations.containsKey(path) && registrations.size() >= MAX_ACTIVE_MEMBERS) {
            return CompletableFuture.failedFuture(new IllegalStateException("Etcd member budget exceeded"));
        }
        long started = observer.enabled() ? System.nanoTime() : 0L;
        return observe(serial(path, () -> ensureLease().thenCompose(lease ->
                writeMember(path, instance, lease, 0))), RpcRegistryOperation.REGISTER, started);
    }

    /**
     * 仅当前实例值及随机进程 Token 一致时注销。
     *
     * <p>迟到的注销不能删除应用角色已升级的成员，更不能删除另一进程接管的成员。
     */
    @Override
    public CompletionStage<Void> unregister(ApplicationInstance instance) {
        Objects.requireNonNull(instance, "instance");
        paths.requireNamespace(instance.application());
        if (closed.get()) {
            return CompletableFuture.completedFuture(null);
        }
        String path = paths.memberKey(instance.application(), instance.instanceId());
        long started = observer.enabled() ? System.nanoTime() : 0L;
        return observe(serial(path, () -> removeMember(path, instance, 0)),
                RpcRegistryOperation.UNREGISTER, started);
    }

    /** 查询应用级完整成员目录，包括没有 RPC Endpoint 的真实 Consumer。 */
    @Override
    public CompletionStage<ApplicationDirectorySnapshot> lookup(ApplicationKey application) {
        Objects.requireNonNull(application, "application");
        paths.requireNamespace(application);
        if (closed.get()) {
            return CompletableFuture.failedFuture(new IllegalStateException("Etcd registry is closed"));
        }
        long started = observer.enabled() ? System.nanoTime() : 0L;
        CompletionStage<ApplicationDirectorySnapshot> stage = client.getKVClient().get(
                utf8(paths.memberPrefix(application)),
                GetOption.builder().isPrefix(true).withLimit(MAX_APPLICATION_MEMBERS + 1).build())
                .thenApply(response -> toSnapshot(application, response));
        return observe(stage, RpcRegistryOperation.LOOKUP, started);
    }

    private ApplicationDirectorySnapshot toSnapshot(ApplicationKey application, GetResponse response) {
        if (response.getCount() > MAX_APPLICATION_MEMBERS
                || response.getKvs().size() > MAX_APPLICATION_MEMBERS) {
            throw new RegistrySecurityException(RegistrySecurityFailure.RESPONSE_LIMIT_EXCEEDED);
        }
        int bytes = 0;
        List<ApplicationInstance> members = new ArrayList<>();
        for (KeyValue kv : response.getKvs()) {
            byte[] payload = kv.getValue().getBytes();
            bytes += payload.length;
            if (bytes > MAX_MEMBER_BYTES_TOTAL) {
                throw new RegistrySecurityException(RegistrySecurityFailure.RESPONSE_LIMIT_EXCEEDED);
            }
            EtcdV2Codec.MemberRecord record = EtcdV2Codec.decodeMember(payload);
            ApplicationInstance member = record.instance();
            if (!application.equals(member.application())
                    || !utf8(paths.memberKey(application, member.instanceId())).equals(kv.getKey())) {
                throw new RegistrySecurityException(RegistrySecurityFailure.METADATA_REFERENCE_MISMATCH);
            }
            requireRoutableAddress(member);
            members.add(member);
        }
        members.sort(Comparator.comparing(ApplicationInstance::instanceId));
        return new ApplicationDirectorySnapshot(application, members, response.getHeader().getRevision());
    }

    /** 建立独立 Watch 订阅，初始合法空目录也通知成功，不因错误发送假空目录。 */
    @Override
    public RegistrySubscription subscribe(ApplicationKey application, ApplicationRegistryListener listener) {
        Objects.requireNonNull(listener, "listener");
        paths.requireNamespace(application);
        requireOpen();
        if (subscriptions.size() >= MAX_SUBSCRIPTIONS) {
            throw new IllegalStateException("Etcd subscription capacity exceeded");
        }
        Subscription subscription = new Subscription(application, listener);
        subscriptions.add(subscription);
        subscription.refresh();
        return subscription;
    }

    /**
     * 从历史修订版恢复 Watch 的测试入口，用于验证 Etcd Compaction 无缝再同步。
     */
    RegistrySubscription subscribeFromRevision(
            ApplicationKey application, ApplicationRegistryListener listener, long revision) {
        if (revision < 1) {
            throw new IllegalArgumentException("Watch revision must be positive");
        }
        paths.requireNamespace(application);
        requireOpen();
        if (subscriptions.size() >= MAX_SUBSCRIPTIONS) {
            throw new IllegalStateException("Etcd subscription capacity exceeded");
        }
        Subscription subscription = new Subscription(application, listener);
        subscriptions.add(subscription);
        subscription.openWatch(revision, subscription.generation.get());
        return subscription;
    }

    private CompletionStage<Void> serial(String path, Supplier<CompletionStage<Void>> action) {
        synchronized (mutationMonitor) {
            if (!mutations.containsKey(path) && mutations.size() >= MAX_ACTIVE_MEMBERS) {
                return CompletableFuture.failedFuture(new IllegalStateException("Etcd pending member budget exceeded"));
            }
            CompletableFuture<Void> previous = mutations.get(path);
            CompletableFuture<Void> base = previous == null
                    ? CompletableFuture.completedFuture(null)
                    : previous.handle((ignored, error) -> null);
            CompletableFuture<Void> next = base.thenCompose(ignored -> action.get()).toCompletableFuture();
            mutations.put(path, next);
            next.whenComplete((ignored, error) -> mutations.remove(path, next));
            return next;
        }
    }

    private CompletionStage<Void> writeMember(
            String path, ApplicationInstance member, long leaseId, int attempts) {
        if (attempts >= MAX_CAS_RETRIES) {
            return CompletableFuture.failedFuture(new IllegalStateException("Etcd member CAS exhausted"));
        }
        if (closed.get()) {
            return unavailable();
        }
        MemberRegistration local = registrations.get(path);
        String token = local == null ? UUID.randomUUID().toString() : local.token();
        byte[] payload = EtcdV2Codec.encodeMember(member, token);
        ByteSequence key = utf8(path);
        return client.getKVClient().get(key).thenCompose(response -> {
            boolean absent = response.getKvs().isEmpty();
            long previousRevision = absent ? 0 : response.getKvs().getFirst().getModRevision();
            if (!absent) {
                EtcdV2Codec.MemberRecord existing = EtcdV2Codec.decodeMember(
                        response.getKvs().getFirst().getValue().getBytes());
                if (!token.equals(existing.token())
                        || !path.equals(paths.memberKey(
                                existing.instance().application(), existing.instance().instanceId()))) {
                    return CompletableFuture.failedFuture(
                            new IllegalStateException("Etcd application instance is owned by another process"));
                }
            }
            Cmp compare = absent
                    ? new Cmp(key, Cmp.Op.EQUAL, CmpTarget.version(0))
                    : new Cmp(key, Cmp.Op.EQUAL, CmpTarget.modRevision(previousRevision));
            PutOption options = PutOption.builder().withLeaseId(leaseId).build();
            return client.getKVClient().txn().If(compare)
                    .Then(Op.put(key, ByteSequence.from(payload), options))
                    .commit()
                    .thenCompose(result -> {
                        if (!result.isSucceeded()) {
                            return writeMember(path, member, leaseId, attempts + 1);
                        }
                        if (closed.get()) {
                            return unavailable();
                        }
                        registrations.put(path, new MemberRegistration(member, token));
                        return CompletableFuture.completedFuture(null);
                    });
        });
    }

    private CompletionStage<Void> removeMember(String path, ApplicationInstance expected, int attempts) {
        MemberRegistration local = registrations.get(path);
        if (local == null || !local.instance().equals(expected)) {
            return CompletableFuture.completedFuture(null);
        }
        if (attempts >= MAX_CAS_RETRIES) {
            return CompletableFuture.failedFuture(new IllegalStateException("Etcd unregister CAS exhausted"));
        }
        ByteSequence key = utf8(path);
        return client.getKVClient().get(key).thenCompose(response -> {
            if (response.getKvs().isEmpty()) {
                registrations.remove(path, local);
                return CompletableFuture.completedFuture(null);
            }
            KeyValue kv = response.getKvs().getFirst();
            EtcdV2Codec.MemberRecord stored = EtcdV2Codec.decodeMember(kv.getValue().getBytes());
            if (!stored.token().equals(local.token()) || !stored.instance().equals(expected)) {
                registrations.remove(path, local);
                return CompletableFuture.completedFuture(null);
            }
            return client.getKVClient().txn()
                    .If(new Cmp(key, Cmp.Op.EQUAL, CmpTarget.modRevision(kv.getModRevision())))
                    .Then(Op.delete(key, DeleteOption.DEFAULT))
                    .commit()
                    .thenCompose(result -> {
                        if (!result.isSucceeded()) {
                            return removeMember(path, expected, attempts + 1);
                        }
                        registrations.remove(path, local);
                        return CompletableFuture.completedFuture(null);
                    });
        });
    }

    private CompletableFuture<Long> ensureLease() {
        synchronized (leaseMonitor) {
            if (closed.get()) {
                return CompletableFuture.failedFuture(new IllegalStateException("Etcd registry is closed"));
            }
            if (leaseFuture != null) {
                return leaseFuture;
            }
            CompletableFuture<Long> result = new CompletableFuture<>();
            leaseFuture = result;
            client.getLeaseClient()
                    .grant(leaseTtlSeconds,
                            Math.min(LEASE_GRANT_TIMEOUT_SECONDS, leaseTtlSeconds),
                            TimeUnit.SECONDS)
                    .whenComplete((response, error) -> {
                        if (error != null || closed.get()) {
                            synchronized (leaseMonitor) {
                                if (leaseFuture == result) {
                                    leaseFuture = null;
                                }
                            }
                            result.completeExceptionally(error == null
                                    ? new IllegalStateException("Etcd registry closed") : error);
                            return;
                        }
                        long lease = response.getID();
                        try {
                            CloseableClient handle = client.getLeaseClient().keepAlive(
                                    lease, new StreamObserver<LeaseKeepAliveResponse>() {
                                        @Override
                                        public void onNext(LeaseKeepAliveResponse ignored) {
                                            // Heartbeat confirmed by Etcd; normal operation does not log.
                                        }

                                        @Override
                                        public void onError(Throwable failure) {
                                            onLeaseLost(lease);
                                        }

                                        @Override
                                        public void onCompleted() {
                                            onLeaseLost(lease);
                                        }
                                    });
                            synchronized (leaseMonitor) {
                                if (closed.get()) {
                                    handle.close();
                                    result.completeExceptionally(new IllegalStateException("Etcd registry closed"));
                                    return;
                                }
                                keepAliveHandle = handle;
                                activeLeaseId = lease;
                            }
                            result.complete(lease);
                            probeLease(lease);
                        } catch (RuntimeException failure) {
                            synchronized (leaseMonitor) {
                                if (leaseFuture == result) {
                                    leaseFuture = null;
                                }
                            }
                            result.completeExceptionally(failure);
                        }
                    });
            return result;
        }
    }

    private void probeLease(long lease) {
        if (closed.get()) {
            return;
        }
        controlExecutor.schedule(() -> {
            if (closed.get() || activeLeaseId != lease) {
                return;
            }
            client.getLeaseClient().timeToLive(lease, LeaseOption.DEFAULT)
                    .orTimeout(Math.max(1L, leaseTtlSeconds), TimeUnit.SECONDS)
                    .whenComplete((ttl, error) -> {
                        if (closed.get() || activeLeaseId != lease) {
                            return;
                        }
                        if (error != null || ttl.getTTL() <= 0) {
                            onLeaseLost(lease);
                        } else {
                            probeLease(lease);
                        }
                    });
        }, Math.max(1, leaseTtlSeconds / 2), TimeUnit.SECONDS);
    }

    private void onLeaseLost(long lease) {
        synchronized (leaseMonitor) {
            if (closed.get() || activeLeaseId != lease) {
                return;
            }
            activeLeaseId = 0;
            leaseFuture = null;
            CloseableClient previous = keepAliveHandle;
            keepAliveHandle = null;
            if (previous != null) {
                previous.close();
            }
        }
        LOGGER.warn("Etcd application membership lease lost; recovery scheduled");
        scheduleRecovery(0);
    }

    private void scheduleRecovery(int attempt) {
        if (closed.get() || registrations.isEmpty()
                || !recoveryScheduled.compareAndSet(false, true)) {
            return;
        }
        long delay = Math.min(RETRY_MAX_MILLIS, RETRY_BASE_MILLIS << Math.min(attempt, 6));
        controlExecutor.schedule(() -> {
            recoveryScheduled.set(false);
            if (closed.get() || registrations.isEmpty()) {
                return;
            }
            long recoveryStartedNanos = observer.enabled() ? System.nanoTime() : 0L;
            ensureLease().thenCompose(lease -> {
                CompletionStage<Void> stage = CompletableFuture.completedFuture(null);
                for (Map.Entry<String, MemberRegistration> entry : List.copyOf(registrations.entrySet())) {
                    stage = stage.thenCompose(ignored -> serial(entry.getKey(), () ->
                            writeMember(entry.getKey(), entry.getValue().instance(), lease, 0)));
                }
                return stage;
            }).whenComplete((ignored, error) -> {
                if (observer.enabled()) {
                    try {
                        observer.onRegistryRecoveryCompleted("etcd",
                                RpcRegistryRecoveryAction.REGISTRATION_RECOVERED,
                                System.nanoTime() - recoveryStartedNanos, error);
                    } catch (RuntimeException observerFailure) {
                        LOGGER.debug("Etcd recovery observer callback failed");
                    }
                }
                if (error != null && !closed.get()) {
                    LOGGER.warn("Etcd application membership recovery failed: errorType={}",
                            error.getClass().getSimpleName());
                    scheduleRecovery(attempt + 1);
                }
            });
        }, delay, TimeUnit.MILLISECONDS);
    }

    /**
     * 使用有界低基数事件观测 Etcd V2 控制面，不写入身份或元数据敏感内容。
     */
    private <T> CompletionStage<T> observe(
            CompletionStage<T> source, RpcRegistryOperation operation, long startedAtNanos) {
        CompletionStage<T> stage = EtcdFailures.safe(source);
        if (observer.enabled()) {
            stage.whenComplete((ignored, error) -> {
                try {
                    observer.onRegistryOperationCompleted(
                            "etcd", operation, System.nanoTime() - startedAtNanos, error);
                } catch (RuntimeException observerFailure) {
                    LOGGER.debug("Etcd application observer callback failed");
                }
            });
        }
        return stage;
    }

    private static void requireRoutableAddress(ApplicationInstance member) {
        if (!member.routableProvider()) {
            return;
        }
        ApplicationManifest.requireRevision(member.manifestRevision());
        RpcEndpoint endpoint = member.rpcEndpoint();
        if (endpoint == null || endpoint.port() <= 0
                || RpcEndpoint.UNSPECIFIED_IPV4_HOST.equals(endpoint.host())
                || RpcEndpoint.UNSPECIFIED_IPV6_HOST.equals(endpoint.host())
                || RpcEndpoint.UNSPECIFIED_IPV6_BRACKETED_HOST.equals(endpoint.host())) {
            throw new IllegalArgumentException("Etcd Provider must publish a real RPC endpoint");
        }
    }

    private void requireOpen() {
        if (closed.get()) {
            throw new IllegalStateException("Etcd registry is closed");
        }
    }

    private static CompletionStage<Void> unavailable() {
        return CompletableFuture.failedFuture(new IllegalStateException("Etcd registry is closed"));
    }

    private static ByteSequence utf8(String text) {
        return ByteSequence.from(text, StandardCharsets.UTF_8);
    }

    private void dispatch(Runnable runnable) {
        if (closed.get()) {
            return;
        }
        try {
            controlExecutor.execute(() -> {
                if (!closed.get()) {
                    runnable.run();
                }
            });
        } catch (RuntimeException error) {
            if (!closed.get()) {
                LOGGER.warn("Etcd control task rejected: errorType={}", error.getClass().getSimpleName());
            }
        }
    }

    /** 关闭订阅并撤销本进程租约，其他实例/共享 Manifest/Mapping 不受影响。 */
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        subscriptions.forEach(Subscription::close);
        subscriptions.clear();
        registrations.clear();
        synchronized (leaseMonitor) {
            CloseableClient previous = keepAliveHandle;
            keepAliveHandle = null;
            if (previous != null) {
                previous.close();
            }
        }
        controlExecutor.shutdownNow();
        long lease = activeLeaseId;
        activeLeaseId = 0;
        if (lease > 0) {
            client.getLeaseClient().revoke(lease)
                    .orTimeout(3, TimeUnit.SECONDS)
                    .whenComplete((ignored, error) -> client.close());
        } else {
            client.close();
        }
    }

    private final class Subscription implements RegistrySubscription {

        private final ApplicationKey application;
        private final ApplicationRegistryListener listener;
        private final AtomicBoolean terminated = new AtomicBoolean();
        private final AtomicBoolean refreshing = new AtomicBoolean();
        private final AtomicBoolean dirty = new AtomicBoolean();
        private final AtomicBoolean restartScheduled = new AtomicBoolean();
        private final AtomicLong generation = new AtomicLong();
        private final AtomicInteger failures = new AtomicInteger();
        private volatile Watch.Watcher watcher;
        private volatile long lastDelivered = -1;

        private Subscription(ApplicationKey application, ApplicationRegistryListener listener) {
            this.application = application;
            this.listener = listener;
        }

        private void refresh() {
            if (terminated.get() || closed.get()) {
                return;
            }
            dirty.set(true);
            if (!refreshing.compareAndSet(false, true)) {
                return;
            }
            dispatch(this::readLatest);
        }

        private void readLatest() {
            if (terminated.get() || closed.get()) {
                refreshing.set(false);
                return;
            }
            dirty.set(false);
            long token = generation.get();
            lookup(application).whenComplete((snapshot, error) -> dispatch(() -> {
                if (terminated.get() || token != generation.get()) {
                    refreshing.set(false);
                    return;
                }
                if (error != null) {
                    refreshing.set(false);
                    restart(error);
                    return;
                }
                try {
                    if (snapshot.revision() > lastDelivered) {
                        listener.onSnapshot(snapshot);
                        lastDelivered = snapshot.revision();
                    }
                    listener.onSynchronizationSucceeded();
                } catch (RuntimeException listenerError) {
                    refreshing.set(false);
                    restart(listenerError);
                    return;
                }
                failures.set(0);
                openWatch(snapshot.revision() + 1, token);
                if (dirty.get()) {
                    readLatest();
                } else {
                    refreshing.set(false);
                    if (dirty.get() && refreshing.compareAndSet(false, true)) {
                        readLatest();
                    }
                }
            }));
        }

        private void openWatch(long revision, long token) {
            if (terminated.get() || closed.get() || generation.get() != token) {
                return;
            }
            WatchOption option = WatchOption.builder().isPrefix(true).withRevision(revision).build();
            Watch.Watcher opened;
            try {
                opened = client.getWatchClient().watch(
                        utf8(paths.memberPrefix(application)),
                        option,
                        Watch.listener(
                                response -> refresh(),
                                error -> dispatch(() -> restart(error))));
            } catch (RuntimeException error) {
                restart(error);
                return;
            }
            if (terminated.get() || generation.get() != token) {
                opened.close();
                return;
            }
            Watch.Watcher previous = watcher;
            watcher = opened;
            if (previous != null) {
                previous.close();
            }
        }

        private void restart(Throwable cause) {
            if (terminated.get() || closed.get()
                    || !restartScheduled.compareAndSet(false, true)) {
                return;
            }
            generation.incrementAndGet();
            refreshing.set(false);
            Watch.Watcher previous = watcher;
            watcher = null;
            if (previous != null) {
                previous.close();
            }
            try {
                listener.onSynchronizationFailed(EtcdFailures.normalize(cause));
            } catch (RuntimeException listenerError) {
                LOGGER.warn("Etcd application listener rejected failure notification");
            }
            int attempt = failures.getAndIncrement();
            long delay = Math.min(RETRY_MAX_MILLIS, RETRY_BASE_MILLIS << Math.min(attempt, 6));
            controlExecutor.schedule(() -> {
                restartScheduled.set(false);
                refresh();
            }, delay, TimeUnit.MILLISECONDS);
        }

        @Override
        public void close() {
            if (!terminated.compareAndSet(false, true)) {
                return;
            }
            generation.incrementAndGet();
            Watch.Watcher previous = watcher;
            watcher = null;
            if (previous != null) {
                previous.close();
            }
            subscriptions.remove(this);
        }
    }

    private record MemberRegistration(ApplicationInstance instance, String token) {
    }
}
