package com.peachsoft.otryx.registry.etcd;

import io.etcd.jetcd.ByteSequence;
import io.etcd.jetcd.Client;
import io.etcd.jetcd.common.exception.ErrorCode;
import io.etcd.jetcd.common.exception.EtcdException;
import io.etcd.jetcd.support.CloseableClient;
import io.etcd.jetcd.Watch;
import io.etcd.jetcd.kv.GetResponse;
import io.etcd.jetcd.lease.LeaseKeepAliveResponse;
import io.etcd.jetcd.options.GetOption;
import io.etcd.jetcd.options.LeaseOption;
import io.etcd.jetcd.options.PutOption;
import io.etcd.jetcd.options.WatchOption;
import io.grpc.stub.StreamObserver;
import com.peachsoft.otryx.api.RpcEndpoint;
import com.peachsoft.otryx.api.ServiceInstance;
import com.peachsoft.otryx.api.ServiceKey;
import com.peachsoft.otryx.observability.RpcObserver;
import com.peachsoft.otryx.observability.RpcRegistryOperation;
import com.peachsoft.otryx.observability.RpcRegistryRecoveryAction;
import com.peachsoft.otryx.registry.Registry;
import com.peachsoft.otryx.registry.RegistryCapabilities;
import com.peachsoft.otryx.registry.RegistryCapability;
import com.peachsoft.otryx.registry.RegistryListener;
import com.peachsoft.otryx.registry.RegistrySnapshot;
import com.peachsoft.otryx.registry.RegistrySubscription;
import com.peachsoft.otryx.registry.ServiceRegistrar;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 基于 Etcd Lease 与 Watch 的注册中心实现。
 *
 * <p>Etcd 只位于控制面。Consumer 的请求热路径读取 Core 中的本地服务目录，不访问 Etcd。
 *
 * @Author Ryan
 * @Version 1.0.0
 * @CreateTime 2026/9/23 10:51
 */
final class EtcdRegistry implements Registry, ServiceRegistrar {
    private static final Logger LOGGER = LoggerFactory.getLogger(EtcdRegistry.class);
    private static final String DEFAULT_ROOT = "/otryx/";
    static final String DEFAULT_NAMESPACE = "default";
    private static final String NAMESPACE_ROOT = "ns/";
    private static final String ESCAPE_PERCENT = "%";
    private static final String ENCODED_PERCENT = "%25";
    private static final String ESCAPE_PIPE = "|";
    private static final String ENCODED_PIPE = "%7C";
    private static final long RECOVERY_BASE_DELAY_MILLIS = 200L;
    private static final long RECOVERY_MAX_DELAY_MILLIS = 30_000L;
    private static final RegistryCapabilities CAPABILITIES = RegistryCapabilities.of(
            RegistryCapability.REGISTRATION,
            RegistryCapability.SUBSCRIPTION,
            RegistryCapability.REVISION,
            RegistryCapability.LEASE,
            RegistryCapability.WEIGHT,
            RegistryCapability.METADATA);

    private final Client client;
    private final long leaseTtlSeconds;
    private final RpcObserver observer;
    private final String root;
    private final Object leaseMonitor = new Object();
    private final ConcurrentMap<String, ServiceInstance> activeRegistrations =
            new ConcurrentHashMap<>();
    private final AtomicBoolean leaseRecoveryScheduled = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Set<EtcdSubscription> subscriptions = ConcurrentHashMap.newKeySet();
    private volatile CompletableFuture<Long> leaseFuture;
    private volatile CloseableClient keepAliveHandle;
    private volatile long activeLeaseId;

    EtcdRegistry(
            String[] endpoints,
            long leaseTtlSeconds,
            String namespace) {
        this(
                endpoints,
                leaseTtlSeconds,
                namespace,
                RpcObserver.noop());
    }

    EtcdRegistry(
            String[] endpoints,
            long leaseTtlSeconds,
            String namespace,
            RpcObserver observer) {
        this(
                Client.builder().endpoints(endpoints).build(),
                leaseTtlSeconds,
                namespace,
                observer);
    }

    EtcdRegistry(
            Client client,
            long leaseTtlSeconds,
            String namespace) {
        this(
                client,
                leaseTtlSeconds,
                namespace,
                RpcObserver.noop());
    }

    EtcdRegistry(
            Client client,
            long leaseTtlSeconds,
            String namespace,
            RpcObserver observer) {
        this.client = Objects.requireNonNull(client, "client");
        this.leaseTtlSeconds = leaseTtlSeconds;
        this.observer = observer == null
                ? RpcObserver.noop()
                : observer;
        this.root = DEFAULT_NAMESPACE.equals(namespace)
                ? DEFAULT_ROOT
                : DEFAULT_ROOT + NAMESPACE_ROOT + encode(namespace) + '/';
    }

    @Override
    public RegistryCapabilities capabilities() {
        return CAPABILITIES;
    }

    @Override
    public CompletionStage<Void> register(ServiceInstance instance) {
        Objects.requireNonNull(instance, "instance");
        if (closed.get()) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("Etcd registry is closed"));
        }
        String registrationKey = key(instance);
        long startedAtNanos =
                observer.enabled() ? System.nanoTime() : 0L;
        CompletionStage<Void> stage = ensureLease()
                .thenCompose(leaseId -> client.getKVClient().put(
                        bytes(registrationKey),
                        bytes(serialize(instance)),
                        PutOption.builder().withLeaseId(leaseId).build()))
                .thenRun(() ->
                        activeRegistrations.put(registrationKey, instance));
        observeOperation(
                stage,
                RpcRegistryOperation.REGISTER,
                startedAtNanos);
        return stage;
    }

    @Override
    public CompletionStage<Void> unregister(ServiceInstance instance) {
        Objects.requireNonNull(instance, "instance");
        String registrationKey = key(instance);
        activeRegistrations.remove(registrationKey);
        if (closed.get()) {
            return CompletableFuture.completedFuture(null);
        }
        long startedAtNanos =
                observer.enabled() ? System.nanoTime() : 0L;
        CompletionStage<Void> stage = client.getKVClient()
                .delete(bytes(registrationKey))
                .thenApply(ignored -> null);
        observeOperation(
                stage,
                RpcRegistryOperation.UNREGISTER,
                startedAtNanos);
        return stage;
    }

    @Override
    public CompletionStage<RegistrySnapshot> lookup(ServiceKey key) {
        long startedAtNanos =
                observer.enabled() ? System.nanoTime() : 0L;
        CompletionStage<RegistrySnapshot> stage = range(key);
        observeOperation(
                stage,
                RpcRegistryOperation.LOOKUP,
                startedAtNanos);
        return stage;
    }

    @Override
    public RegistrySubscription subscribe(
            ServiceKey key,
            RegistryListener listener) {
        if (closed.get()) {
            throw new IllegalStateException("Etcd registry is closed");
        }
        EtcdSubscription subscription =
                new EtcdSubscription(key, listener);
        subscriptions.add(subscription);
        subscription.start();
        return subscription;
    }

    /**
     * 从指定历史 Revision 建立 Watch。
     *
     * <p>该包级入口仅用于确定性验证 Etcd compaction 恢复路径：
     * 当历史 Revision 已被压缩时，Watch 会先收到 CompactedException，
     * 随后复用正常恢复逻辑执行 Range + 新 Watch。
     *
     * @param key 服务键
     * @param listener 快照监听器
     * @param revision Watch 起始 Revision，必须大于 0
     * @return 可关闭订阅
     */
    RegistrySubscription subscribeFromRevision(
            ServiceKey key,
            RegistryListener listener,
            long revision) {
        if (revision <= 0L) {
            throw new IllegalArgumentException(
                    "revision must be positive");
        }
        if (closed.get()) {
            throw new IllegalStateException("Etcd registry is closed");
        }
        EtcdSubscription subscription =
                new EtcdSubscription(key, listener);
        subscriptions.add(subscription);
        subscription.openWatch(revision, subscription.generation.get());
        return subscription;
    }

    private CompletionStage<RegistrySnapshot> range(ServiceKey key) {
        return client.getKVClient()
                .get(bytes(prefix(key)), GetOption.builder().isPrefix(true).build())
                .thenApply(response -> toSnapshot(key, response));
    }

    private static RegistrySnapshot toSnapshot(ServiceKey key, GetResponse response) {
        List<ServiceInstance> instances = response.getKvs().stream()
                .map(kv -> deserialize(key, kv.getValue().toString(StandardCharsets.UTF_8)))
                .toList();
        return new RegistrySnapshot(instances, response.getHeader().getRevision());
    }

    private CompletionStage<Long> ensureLease() {
        CompletableFuture<Long> current = leaseFuture;
        if (current != null) {
            return current;
        }
        synchronized (leaseMonitor) {
            if (leaseFuture != null) {
                return leaseFuture;
            }
            CompletableFuture<Long> created = new CompletableFuture<>();
            leaseFuture = created;
            long grantTimeoutSeconds =
                    Math.max(1L, Math.min(leaseTtlSeconds, 5L));
            client.getLeaseClient()
                    .grant(
                            leaseTtlSeconds,
                            grantTimeoutSeconds,
                            TimeUnit.SECONDS)
                    .whenComplete((response, error) -> {
                if (error != null) {
                    synchronized (leaseMonitor) {
                        leaseFuture = null;
                    }
                    created.completeExceptionally(error);
                    return;
                }
                long leaseId = response.getID();
                activeLeaseId = leaseId;
                keepAliveHandle = client.getLeaseClient().keepAlive(leaseId, new StreamObserver<>() {
                    @Override
                    public void onNext(LeaseKeepAliveResponse value) {
                        // Lease health is intentionally silent on the normal path.
                    }

                    @Override
                    public void onError(Throwable error) {
                        LOGGER.error(
                                "Etcd lease keepalive failed: leaseId={}, errorType={}",
                                leaseId,
                                error.getClass().getName());
                        handleLeaseLoss(leaseId);
                    }

                    @Override
                    public void onCompleted() {
                        LOGGER.warn(
                                "Etcd lease keepalive completed for leaseId={}",
                                leaseId);
                        handleLeaseLoss(leaseId);
                    }
                });
                created.complete(leaseId);
                scheduleLeaseHealthProbe(leaseId);
            });
            return created;
        }
    }

    private void handleLeaseLoss(long leaseId) {
        synchronized (leaseMonitor) {
            if (closed.get()
                    || activeLeaseId != leaseId) {
                return;
            }
            leaseFuture = null;
            activeLeaseId = 0L;
            if (keepAliveHandle != null) {
                CloseableClient current = keepAliveHandle;
                keepAliveHandle = null;
                current.close();
            }
        }
        scheduleLeaseRecovery(0);
    }

    private void invalidateLease() {
        synchronized (leaseMonitor) {
            leaseFuture = null;
            activeLeaseId = 0L;
            if (keepAliveHandle != null) {
                CloseableClient current = keepAliveHandle;
                keepAliveHandle = null;
                current.close();
            }
        }
    }

    private void scheduleLeaseHealthProbe(long leaseId) {
        if (closed.get() || leaseId <= 0L) {
            return;
        }
        long delaySeconds = Math.max(
                1L,
                leaseTtlSeconds / 2L);
        Executor executor = CompletableFuture.delayedExecutor(
                delaySeconds,
                TimeUnit.SECONDS);
        CompletableFuture.runAsync(
                () -> probeLeaseHealth(leaseId),
                executor);
    }

    private void probeLeaseHealth(long leaseId) {
        if (closed.get() || activeLeaseId != leaseId) {
            return;
        }
        client.getLeaseClient()
                .timeToLive(leaseId, LeaseOption.DEFAULT)
                .orTimeout(
                        Math.max(1L, leaseTtlSeconds),
                        TimeUnit.SECONDS)
                .whenComplete((response, error) -> {
                    if (closed.get()
                            || activeLeaseId != leaseId) {
                        return;
                    }
                    if (error != null) {
                        if (isLeaseNotFound(error)) {
                            LOGGER.warn(
                                    "Etcd lease no longer exists. Recovering registrations. leaseId={}",
                                    leaseId);
                            handleLeaseLoss(leaseId);
                            return;
                        }
                        LOGGER.debug(
                                "Etcd lease health probe failed; keeping lease until next probe. "
                                        + "leaseId={}, errorType={}",
                                leaseId,
                                error.getClass().getName());
                        scheduleLeaseHealthProbe(leaseId);
                        return;
                    }
                    if (response.getTTL() <= 0L) {
                        LOGGER.warn(
                                "Etcd lease expired. Recovering registrations. leaseId={}",
                                leaseId);
                        handleLeaseLoss(leaseId);
                        return;
                    }
                    scheduleLeaseHealthProbe(leaseId);
                });
    }

    private static boolean isLeaseNotFound(Throwable error) {
        Throwable current = error;
        while (current instanceof CompletionException
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current instanceof EtcdException etcdError
                && etcdError.getErrorCode() == ErrorCode.NOT_FOUND;
    }

    private void scheduleLeaseRecovery(int attempt) {
        if (closed.get()
                || activeRegistrations.isEmpty()
                || !leaseRecoveryScheduled.compareAndSet(false, true)) {
            return;
        }
        long delayMillis = recoveryDelayMillis(attempt);
        Executor executor = CompletableFuture.delayedExecutor(
                delayMillis,
                TimeUnit.MILLISECONDS);
        CompletableFuture.runAsync(() ->
                        recoverRegistrations(attempt),
                executor);
    }

    private void recoverRegistrations(int attempt) {
        long recoveryStartedAtNanos =
                observer.enabled() ? System.nanoTime() : 0L;
        if (closed.get() || activeRegistrations.isEmpty()) {
            leaseRecoveryScheduled.set(false);
            return;
        }

        List<ServiceInstance> snapshot =
                List.copyOf(activeRegistrations.values());
        ensureLease()
                .thenCompose(leaseId -> {
                    CompletableFuture<?>[] writes = snapshot.stream()
                            .map(instance -> client.getKVClient().put(
                                    bytes(key(instance)),
                                    bytes(serialize(instance)),
                                    PutOption.builder()
                                            .withLeaseId(leaseId)
                                            .build()))
                            .map(CompletionStage::toCompletableFuture)
                            .toArray(CompletableFuture[]::new);
                    return CompletableFuture.allOf(writes);
                })
                .whenComplete((ignored, error) -> {
                    leaseRecoveryScheduled.set(false);
                    if (closed.get()) {
                        return;
                    }
                    if (observer.enabled()) {
                        observer.onRegistryRecoveryCompleted(
                                "etcd",
                                RpcRegistryRecoveryAction.REGISTRATION_RECOVERED,
                                System.nanoTime()
                                        - recoveryStartedAtNanos,
                                error);
                    }
                    if (error != null) {
                        LOGGER.warn(
                                "Failed to recover Etcd registrations; retrying. errorType={}",
                                error.getClass().getName());
                        invalidateLease();
                        scheduleLeaseRecovery(attempt + 1);
                    } else {
                        LOGGER.info(
                                "Recovered {} Etcd RPC registrations",
                                snapshot.size());
                    }
                });
    }

    private static long recoveryDelayMillis(int attempt) {
        int shift = Math.min(Math.max(attempt, 0), 7);
        long ceiling = Math.min(
                RECOVERY_MAX_DELAY_MILLIS,
                RECOVERY_BASE_DELAY_MILLIS << shift);
        long floor = Math.max(1L, ceiling / 2L);
        return ThreadLocalRandom.current().nextLong(
                floor,
                ceiling + 1L);
    }

    private final class EtcdSubscription implements RegistrySubscription {
        private final ServiceKey serviceKey;
        private final RegistryListener listener;
        private final AtomicBoolean closed = new AtomicBoolean();
        private final AtomicBoolean restartScheduled = new AtomicBoolean();
        private final AtomicBoolean refreshing = new AtomicBoolean();
        private final AtomicLong generation = new AtomicLong();
        private final AtomicLong refreshRequests = new AtomicLong();
        private volatile Watch.Watcher watcher;
        private volatile int restartAttempt;
        private volatile boolean recovering;
        private volatile long recoveryStartedAtNanos;

        private EtcdSubscription(ServiceKey serviceKey, RegistryListener listener) {
            this.serviceKey = Objects.requireNonNull(serviceKey, "serviceKey");
            this.listener = Objects.requireNonNull(listener, "listener");
        }

        private void start() {
            if (closed.get() || EtcdRegistry.this.closed.get()) {
                return;
            }
            long token = generation.get();
            long subscribeStartedAtNanos =
                    observer.enabled() ? System.nanoTime() : 0L;
            CompletionStage<RegistrySnapshot> initial;
            try {
                initial = range(serviceKey);
            } catch (RuntimeException error) {
                scheduleRestart(error, token);
                return;
            }
            initial.whenComplete((snapshot, error) -> {
                if (closed.get() || EtcdRegistry.this.closed.get()
                        || generation.get() != token) {
                    return;
                }
                if (error != null) {
                    if (observer.enabled() && !recovering) {
                        observer.onRegistryOperationCompleted(
                                "etcd",
                                RpcRegistryOperation.SUBSCRIBE,
                                System.nanoTime() - subscribeStartedAtNanos,
                                error);
                    }
                    LOGGER.warn(
                            "Failed to load Etcd snapshot; retrying. service={}, errorType={}",
                            serviceKey.canonicalName(),
                            error.getClass().getName());
                    scheduleRestart(error, token);
                    return;
                }
                listener.onSnapshot(snapshot);
                listener.onSynchronizationSucceeded();
                if (observer.enabled()) {
                    if (recovering) {
                        observer.onRegistryRecoveryCompleted(
                                "etcd",
                                RpcRegistryRecoveryAction.SUBSCRIPTION_RECOVERED,
                                System.nanoTime() - recoveryStartedAtNanos,
                                null);
                    } else {
                        observer.onRegistryOperationCompleted(
                                "etcd",
                                RpcRegistryOperation.SUBSCRIBE,
                                System.nanoTime() - subscribeStartedAtNanos,
                                null);
                    }
                }
                recovering = false;
                restartAttempt = 0;
                openWatch(snapshot.revision() + 1L, token);
            });
        }

        private void openWatch(long revision, long token) {
            if (closed.get() || EtcdRegistry.this.closed.get()
                    || generation.get() != token) {
                return;
            }
            WatchOption option = WatchOption.builder()
                    .isPrefix(true)
                    .withRevision(revision)
                    .build();
            Watch.Watcher opened;
            try {
                opened = client.getWatchClient().watch(
                        bytes(prefix(serviceKey)),
                        option,
                        Watch.listener(
                                response -> scheduleRefresh(token),
                                error -> {
                                    if (!closed.get() && generation.get() == token) {
                                        LOGGER.warn(
                                                "Etcd watch failed; resubscribing. service={}, errorType={}",
                                                serviceKey.canonicalName(),
                                                error.getClass().getName());
                                        scheduleRestart(error, token);
                                    }
                                }));
            } catch (RuntimeException error) {
                scheduleRestart(error, token);
                return;
            }
            if (closed.get() || generation.get() != token) {
                opened.close();
                return;
            }
            Watch.Watcher previous = watcher;
            watcher = opened;
            if (previous != null) {
                previous.close();
            }
            if (closed.get() || generation.get() != token) {
                closeWatcher();
            }
        }

        private void scheduleRefresh(long token) {
            if (closed.get() || generation.get() != token) {
                return;
            }
            refreshRequests.incrementAndGet();
            if (refreshing.compareAndSet(false, true)) {
                readLatest(token);
            }
        }

        private void readLatest(long token) {
            if (closed.get() || generation.get() != token) {
                return;
            }
            long requested = refreshRequests.get();
            CompletionStage<RegistrySnapshot> update;
            try {
                update = range(serviceKey);
            } catch (RuntimeException error) {
                refreshing.set(false);
                scheduleRestart(error, token);
                return;
            }
            update.whenComplete((snapshot, error) -> {
                if (closed.get() || generation.get() != token) {
                    return;
                }
                if (error != null) {
                    refreshing.set(false);
                    scheduleRestart(error, token);
                    return;
                }
                try {
                    listener.onSnapshot(snapshot);
                    listener.onSynchronizationSucceeded();
                } catch (RuntimeException listenerError) {
                    refreshing.set(false);
                    scheduleRestart(listenerError, token);
                    return;
                }
                if (refreshRequests.get() != requested) {
                    readLatest(token);
                    return;
                }
                refreshing.set(false);
                if (refreshRequests.get() != requested
                        && refreshing.compareAndSet(false, true)) {
                    readLatest(token);
                }
            });
        }

        private void scheduleRestart(Throwable error, long token) {
            if (closed.get() || EtcdRegistry.this.closed.get()
                    || generation.get() != token
                    || !restartScheduled.compareAndSet(false, true)) {
                return;
            }
            generation.incrementAndGet();
            refreshing.set(false);
            try {
                listener.onSynchronizationFailed(error);
            } catch (RuntimeException listenerError) {
                LOGGER.debug("Etcd listener rejected unhealthy notification. service={}",
                        serviceKey.canonicalName());
            }
            closeWatcher();
            if (!recovering) {
                recovering = true;
                recoveryStartedAtNanos =
                        observer.enabled() ? System.nanoTime() : 0L;
            }
            int attempt = restartAttempt++;
            long delayMillis = recoveryDelayMillis(attempt);
            Executor executor = CompletableFuture.delayedExecutor(
                    delayMillis, TimeUnit.MILLISECONDS);
            CompletableFuture.runAsync(() -> {
                restartScheduled.set(false);
                if (!closed.get() && !EtcdRegistry.this.closed.get()) {
                    start();
                }
            }, executor);
        }

        private void closeWatcher() {
            Watch.Watcher current = watcher;
            watcher = null;
            if (current != null) {
                current.close();
            }
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                generation.incrementAndGet();
                closeWatcher();
                subscriptions.remove(this);
            }
        }
    }

    private <T> void observeOperation(
            CompletionStage<T> stage,
            RpcRegistryOperation operation,
            long startedAtNanos) {
        if (!observer.enabled()) {
            return;
        }
        stage.whenComplete((ignored, error) ->
                observer.onRegistryOperationCompleted(
                        "etcd",
                        operation,
                        System.nanoTime() - startedAtNanos,
                        error));
    }

    private String prefix(ServiceKey key) {
        return root
                + encode(key.serviceName()) + '/'
                + encode(key.version()) + '/'
                + encode(key.group()) + '/';
    }

    private String key(ServiceInstance instance) {
        return prefix(instance.serviceKey())
                + encode(instance.instanceId());
    }

    private static String serialize(ServiceInstance instance) {
        return escape(instance.instanceId()) + '|'
                + escape(instance.endpoint().host()) + '|'
                + instance.endpoint().port() + '|'
                + instance.weight() + '|'
                + serializeMetadata(instance.metadata());
    }

    private static ServiceInstance deserialize(ServiceKey key, String value) {
        String[] parts = value.split("\\|", -1);
        if (parts.length != 5) {
            throw new IllegalArgumentException("Malformed service instance value");
        }
        return new ServiceInstance(
                unescape(parts[0]),
                key,
                new RpcEndpoint(unescape(parts[1]), Integer.parseInt(parts[2])),
                Integer.parseInt(parts[3]),
                deserializeMetadata(parts[4]));
    }

    private static String serializeMetadata(Map<String, String> metadata) {
        return metadata.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> encode(entry.getKey()) + '=' + encode(entry.getValue()))
                .reduce((left, right) -> left + '&' + right)
                .orElse("");
    }

    private static Map<String, String> deserializeMetadata(String value) {
        if (value.isEmpty()) {
            return Map.of();
        }
        Map<String, String> metadata = new LinkedHashMap<>();
        for (String entry : value.split("&")) {
            int separator = entry.indexOf('=');
            if (separator <= 0) {
                throw new IllegalArgumentException("Malformed service metadata");
            }
            String key = decode(entry.substring(0, separator));
            String itemValue = decode(entry.substring(separator + 1));
            metadata.put(key, itemValue);
        }
        return Map.copyOf(metadata);
    }

    private static String encode(String value) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String decode(String value) {
        return new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
    }

    private static String escape(String value) {
        return value.replace(ESCAPE_PERCENT, ENCODED_PERCENT)
                .replace(ESCAPE_PIPE, ENCODED_PIPE);
    }

    private static String unescape(String value) {
        return value.replace(ENCODED_PIPE, ESCAPE_PIPE)
                .replace(ENCODED_PERCENT, ESCAPE_PERCENT);
    }

    private static ByteSequence bytes(String value) {
        return ByteSequence.from(value, StandardCharsets.UTF_8);
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        subscriptions.forEach(EtcdSubscription::close);
        subscriptions.clear();
        activeRegistrations.clear();
        invalidateLease();
        client.close();
    }
}
