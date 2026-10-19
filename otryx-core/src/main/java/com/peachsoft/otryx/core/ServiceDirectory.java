package com.peachsoft.otryx.core;

import com.peachsoft.otryx.api.ApplicationInstance;
import com.peachsoft.otryx.api.ServiceInstance;
import com.peachsoft.otryx.registry.ApplicationServiceResolver;
import com.peachsoft.otryx.registry.RegistrySubscription;
import com.peachsoft.otryx.registry.RegistrySecurityException;
import com.peachsoft.otryx.registry.ServiceMappingKey;
import com.peachsoft.otryx.registry.VerifiedProviderSnapshot;
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
    private static final long REFRESH_INTERVAL_SECONDS = 1L;

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
                    null, EMPTY, 0L, false, false, 0L, 0L));
    private final ApplicationServiceResolver resolver;
    private final ServiceMappingKey key;
    private final String expectedSchema;
    private final RpcDirectoryFreshnessOptions freshnessOptions;
    private final LongSupplier monotonicClock;
    private final RegistrySubscription subscription;

    /**
     * 绑定应用级发现，远端刷新仅由共享控制面调度器启动。
     *
     * <p>本地快照仍用 ServiceInstance 作为 DataPlane LB 的中立 Endpoint 载体；
     * 只有已验证的 Provider/BOTH 成员能映射为服务实例。
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
        this(resolver, key, expectedSchema, freshnessOptions, scheduler, System::nanoTime);
    }

    ServiceDirectory(
            ApplicationServiceResolver resolver,
            ServiceMappingKey key,
            String expectedSchema,
            RpcDirectoryFreshnessOptions freshnessOptions,
            ScheduledExecutorService scheduler,
            LongSupplier monotonicClock) {
        this.resolver = Objects.requireNonNull(resolver, "resolver");
        this.key = Objects.requireNonNull(key, "key");
        this.expectedSchema = Objects.requireNonNull(expectedSchema, "expectedSchema");
        Objects.requireNonNull(scheduler, "scheduler");
        this.freshnessOptions = Objects.requireNonNull(freshnessOptions, "freshnessOptions");
        this.monotonicClock = Objects.requireNonNull(monotonicClock, "monotonicClock");
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
                            } catch (RegistrySecurityException rejected) {
                                markUnavailable();
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
        }, 0L, REFRESH_INTERVAL_SECONDS, TimeUnit.SECONDS);
        this.subscription = () -> task.cancel(false);
    }

    private void publishVerified(ServiceMappingKey key, VerifiedProviderSnapshot candidate) {
        if (!resolver.isSnapshotUsable(key, expectedSchema, candidate)) {
            return;
        }
        long now = monotonicClock.getAsLong();
        ServiceInstance[] endpoints = candidate.providers().stream()
                .map(instance -> verifiedEndpoint(key, instance))
                .toArray(ServiceInstance[]::new);
        state.updateAndGet(current -> {
            if (closed.get() || !resolver.isSnapshotUsable(key, expectedSchema, candidate)
                    || candidate.mappingRevision() < current.revision()) {
                return current;
            }
            return new DirectorySnapshot(candidate, endpoints, candidate.mappingRevision(), true, false, now, 0L);
        });
    }

    private static ServiceInstance verifiedEndpoint(ServiceMappingKey key, ApplicationInstance instance) {
        String id = instance.application().namespace() + "/"
                + instance.application().applicationName() + "/" + instance.instanceId();
        return new ServiceInstance(id, instance.application(), key.serviceKey(),
                instance.rpcEndpoint(), instance.weight(), Map.of());
    }

    private void markUnavailable() {
        long now = monotonicClock.getAsLong();
        state.updateAndGet(current -> {
            if (closed.get() || !current.initialized() || current.unhealthy()) {
                return current;
            }
            return new DirectorySnapshot(current.candidate(), current.instances(), current.revision(),
                    true, true, current.lastSuccessfulSyncNanos(), now);
        });
    }

    Status status() {
        return selection().status();
    }

    ServiceInstance[] snapshot() {
        return selection().instances();
    }

    Selection selection() {
        if (closed.get()) {
            return new Selection(null, EMPTY, Status.CLOSED);
        }
        DirectorySnapshot current = state.get();
        if (!resolver.isSnapshotUsable(key, expectedSchema, current.candidate()) || !current.initialized()) {
            return new Selection(current.candidate(), EMPTY, Status.NOT_READY);
        }
        Status status = status(current);
        return new Selection(current.candidate(), status == Status.STALE ? EMPTY : current.instances(), status);
    }

    boolean isUsable(Selection selected) {
        if (closed.get()) {
            return false;
        }
        // 必须校验选中端点来自的原始候选；新候选不能为旧数组补发信任凭据。
        if (!resolver.isSnapshotUsable(key, expectedSchema, selected.candidate())) {
            return false;
        }
        DirectorySnapshot current = state.get();
        return current.candidate() == selected.candidate() && status(current) != Status.STALE;
    }

    private Status status(DirectorySnapshot current) {
        if (!current.initialized()) {
            return Status.NOT_READY;
        }
        if (!current.unhealthy()) {
            return Status.HEALTHY;
        }
        return monotonicClock.getAsLong() - current.unhealthySinceNanos()
                < freshnessOptions.maxStaleness().toNanos() ? Status.LAST_KNOWN_GOOD : Status.STALE;
    }

    record Selection(VerifiedProviderSnapshot candidate, ServiceInstance[] instances, Status status) {
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
            VerifiedProviderSnapshot candidate,
            ServiceInstance[] instances,
            long revision,
            boolean initialized,
            boolean unhealthy,
            long lastSuccessfulSyncNanos,
            long unhealthySinceNanos) {
    }
}
