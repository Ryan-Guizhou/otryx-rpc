package com.peachsoft.otryx.core;

import com.peachsoft.otryx.api.RpcCompatibilityMetadata;
import com.peachsoft.otryx.api.ServiceInstance;
import com.peachsoft.otryx.api.ServiceKey;
import com.peachsoft.otryx.registry.RegistryListener;
import com.peachsoft.otryx.registry.RegistrySnapshot;
import com.peachsoft.otryx.registry.RegistrySubscription;
import com.peachsoft.otryx.registry.ServiceDiscovery;
import java.util.Objects;
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
