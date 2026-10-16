package com.peachsoft.otryx.core;

import com.peachsoft.otryx.api.ApplicationInstance;
import com.peachsoft.otryx.api.RpcCompatibilityMetadata;
import com.peachsoft.otryx.api.ServiceInstance;
import com.peachsoft.otryx.api.ServiceKey;
import com.peachsoft.otryx.registry.ApplicationServiceResolver;
import com.peachsoft.otryx.registry.RegistryListener;
import com.peachsoft.otryx.registry.RegistrySnapshot;
import com.peachsoft.otryx.registry.RegistrySubscription;
import com.peachsoft.otryx.registry.ServiceMappingKey;
import com.peachsoft.otryx.registry.VerifiedProviderSnapshot;
import com.peachsoft.otryx.registry.ServiceDiscovery;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;

/**
 * Consumer 服务目录：将 Registry 控制面快照映射为可诊断的本地不可变数据面视图。
 *
 * <p>无变更事件的健康 Watch 不会误判过期；仅收到明确的同步失败信号后
 * 才对 Last-Known-Good 启动允许陈旧时长计时。合法空快照与首次未就绪有区别。
 *
 * @Author Ryan
 * @Version 1.0.0
 * @CreateTime 2026/9/23 10:51
 */
final class ServiceDirectory implements AutoCloseable {

    private static final ServiceInstance[] EMPTY = new ServiceInstance[0];

    enum Status {
        NOT_READY,
        HEALTHY,
        LAST_KNOWN_GOOD,
        STALE,
        CLOSED
    }

    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicReference<DirectorySnapshot> state =
            new AtomicReference<>(new DirectorySnapshot(
                    EMPTY, 0L, false, false, 0L, 0L));
    private final String expectedSchemaFingerprint;
    private final RpcDirectoryFreshnessOptions freshnessOptions;
    private final LongSupplier monotonicClock;
    private final RegistrySubscription subscription;

    ServiceDirectory(ServiceDiscovery discovery, ServiceKey key) {
        this(discovery, key, null);
    }

    ServiceDirectory(
            ServiceDiscovery discovery,
            ServiceKey key,
            String expectedSchemaFingerprint) {
        this(discovery, key, expectedSchemaFingerprint,
                RpcDirectoryFreshnessOptions.UNBOUNDED);
    }

    ServiceDirectory(
            ServiceDiscovery discovery,
            ServiceKey key,
            String expectedSchemaFingerprint,
            RpcDirectoryFreshnessOptions freshnessOptions) {
        this(discovery, key, expectedSchemaFingerprint, freshnessOptions, System::nanoTime);
    }

    ServiceDirectory(
            ServiceDiscovery discovery,
            ServiceKey key,
            String expectedSchemaFingerprint,
            RpcDirectoryFreshnessOptions freshnessOptions,
            LongSupplier monotonicClock) {
        this.expectedSchemaFingerprint = expectedSchemaFingerprint;
        this.freshnessOptions = Objects.requireNonNull(freshnessOptions, "freshnessOptions");
        this.monotonicClock = Objects.requireNonNull(monotonicClock, "monotonicClock");
        this.subscription = Objects.requireNonNull(discovery, "discovery").subscribe(
                Objects.requireNonNull(key, "key"),
                new RegistryListener() {
                    @Override
                    public void onSnapshot(RegistrySnapshot snapshot) {
                        publish(snapshot);
                    }

                    @Override
                    public void onSynchronizationSucceeded() {
                        markHealthy();
                    }

                    @Override
                    public void onSynchronizationFailed(Throwable error) {
                        markUnavailable();
                    }
                });
    }

    /**
     * 绑定 V2 应用级发现，远端刷新仅由共享控制面调度器启动。
     *
     * <p>本地快照仍用 ServiceInstance 作为 DataPlane LB 的中立 Endpoint 载体；
     * 不查询接口级 Registry，也不会把纯 Consumer 映射为服务实例。
     *
     * @param resolver 已验证 Manifest/Schema 的应用服务解析器
     * @param key 带命名空间的业务服务键
     * @param expectedSchema Consumer 所需 Schema Fingerprint
     * @param freshnessOptions 目录断联后的 LKG 预算
     * @param scheduler 属于 Client 的共享有界控制面调度器
     */
    ServiceDirectory(
            ApplicationServiceResolver resolver,
            ServiceMappingKey key,
            String expectedSchema,
            RpcDirectoryFreshnessOptions freshnessOptions,
            ScheduledExecutorService scheduler) {
        Objects.requireNonNull(resolver, "resolver");
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(expectedSchema, "expectedSchema");
        Objects.requireNonNull(scheduler, "scheduler");
        this.expectedSchemaFingerprint = null;
        this.freshnessOptions = Objects.requireNonNull(freshnessOptions, "freshnessOptions");
        this.monotonicClock = System::nanoTime;
        AtomicBoolean refreshing = new AtomicBoolean();
        ScheduledFuture<?> task = scheduler.scheduleWithFixedDelay(() -> {
            if (closed.get() || !refreshing.compareAndSet(false, true)) {
                return;
            }
            try {
                resolver.refresh(key, expectedSchema).whenComplete((snapshot, error) -> {
                    try {
                        scheduler.execute(() -> {
                            try {
                                if (error == null && snapshot != null) {
                                    publishVerified(key, snapshot);
                                } else {
                                    markUnavailable();
                                }
                            } finally {
                                refreshing.set(false);
                            }
                        });
                    } catch (RejectedExecutionException rejected) {
                        refreshing.set(false);
                        markUnavailable();
                    }
                });
            } catch (RuntimeException error) {
                refreshing.set(false);
                markUnavailable();
            }
        }, 0L, 1L, TimeUnit.SECONDS);
        this.subscription = () -> task.cancel(false);
    }

    private void publishVerified(ServiceMappingKey key, VerifiedProviderSnapshot snapshot) {
        List<ServiceInstance> nodes = snapshot.providers().stream()
                .map(instance -> verifiedEndpoint(key, instance))
                .toList();
        publish(new RegistrySnapshot(nodes, snapshot.mappingRevision()));
    }

    private static ServiceInstance verifiedEndpoint(ServiceMappingKey key, ApplicationInstance instance) {
        String id = instance.application().namespace() + "/"
                + instance.application().applicationName() + "/" + instance.instanceId();
        return new ServiceInstance(id, key.serviceKey(),
                instance.rpcEndpoint(), instance.weight(), Map.of());
    }

    private void publish(RegistrySnapshot candidate) {
        Objects.requireNonNull(candidate, "candidate");
        long now = monotonicClock.getAsLong();
        state.updateAndGet(current -> {
            if (closed.get() || candidate.revision() < current.revision()) {
                return current;
            }
            ServiceInstance[] compatible = candidate.instances().stream()
                    .filter(this::compatible)
                    .toArray(ServiceInstance[]::new);
            return new DirectorySnapshot(compatible, candidate.revision(),
                    true, false, now, 0L);
        });
    }

    private void markHealthy() {
        long now = monotonicClock.getAsLong();
        state.updateAndGet(current -> {
            if (closed.get() || !current.initialized()) {
                return current;
            }
            return new DirectorySnapshot(current.instances(), current.revision(),
                    true, false, now, 0L);
        });
    }

    private void markUnavailable() {
        long now = monotonicClock.getAsLong();
        state.updateAndGet(current -> {
            if (closed.get() || !current.initialized() || current.unhealthy()) {
                return current;
            }
            return new DirectorySnapshot(current.instances(), current.revision(),
                    true, true, current.lastSuccessfulSyncNanos(), now);
        });
    }

    private boolean compatible(ServiceInstance instance) {
        if (expectedSchemaFingerprint == null) {
            return true;
        }
        return RpcCompatibilityMetadata.compatibility(
                instance, expectedSchemaFingerprint)
                == RpcCompatibilityMetadata.Compatibility.COMPATIBLE;
    }

    Status status() {
        if (closed.get()) {
            return Status.CLOSED;
        }
        DirectorySnapshot current = state.get();
        if (!current.initialized()) {
            return Status.NOT_READY;
        }
        if (!current.unhealthy()) {
            return Status.HEALTHY;
        }
        if (!freshnessOptions.limited()
                || monotonicClock.getAsLong() - current.unhealthySinceNanos()
                        < freshnessOptions.maxStaleness().toNanos()) {
            return Status.LAST_KNOWN_GOOD;
        }
        return Status.STALE;
    }

    ServiceInstance[] snapshot() {
        if (closed.get()) {
            return EMPTY;
        }
        DirectorySnapshot current = state.get();
        if (!current.initialized()) {
            return EMPTY;
        }
        if (!current.unhealthy() || !freshnessOptions.limited()
                || monotonicClock.getAsLong() - current.unhealthySinceNanos()
                        < freshnessOptions.maxStaleness().toNanos()) {
            return current.instances();
        }
        return EMPTY;
    }

    long lastSuccessfulSyncNanos() {
        return state.get().lastSuccessfulSyncNanos();
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            subscription.close();
        }
    }

    private record DirectorySnapshot(
            ServiceInstance[] instances,
            long revision,
            boolean initialized,
            boolean unhealthy,
            long lastSuccessfulSyncNanos,
            long unhealthySinceNanos) {
    }
}
