package com.peachsoft.otryx.registry;

import com.peachsoft.otryx.api.ApplicationInstance;
import com.peachsoft.otryx.api.ApplicationKey;
import com.peachsoft.otryx.api.ApplicationManifest;
import com.peachsoft.otryx.api.ApplicationManifestCodec;
import com.peachsoft.otryx.api.ExportedService;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;

/**
 * 应用级 RPC 服务解析器：Mapping -> Directory -> Manifest -> Schema -> Provider。
 *
 * <p>所有远端 IO 只出现在显式 refresh 控制面入口。current 仅返回已完整
 * 验证的本地不可变路由快照；任一步失败不得发布部分结果或合法空结果。
 * 失败后的 Last-Known-Good 有界，首次未就绪和健康空目录严格区分。
 * 不执行授权：Transport 的真实 Peer 身份和 RPC 访问授权仍须独立验证。
 *
 * @Author OpenAI
 * @Version 1.0.0-SNAPSHOT
 * @CreateTime 2026/10/15 18:30
 */
public final class ApplicationServiceResolver implements AutoCloseable {

    private static final int MAX_SERVICE_KEYS = 512;
    private static final int MAX_REFRESHES = 32;
    private static final int MAX_DIRECTORY_INSTANCES = 1024;
    private static final long REFRESH_TIMEOUT_SECONDS = 30L;

    /** 本地路由目录的可观测状态，不等价于 Registry 后端健康状态。 */
    public enum Status {
        /** 尚未获得完整的成功快照。 */
        NOT_READY,
        /** 最后一次控制面解析成功，包括合法空列表。 */
        HEALTHY,
        /** 远端失败，允许短期使用上次已验证的快照。 */
        LAST_KNOWN_GOOD,
        /** 失败后超过允许陈旧时间，不允许进行调用。 */
        STALE,
        /** 已关闭，不再接受刷新或本地读取。 */
        CLOSED
    }

    private final ServiceMappingCache mappingCache;
    private final ApplicationDiscovery discovery;
    private final ManifestSnapshotCache manifestCache;
    private final long staleNanos;
    private final LongSupplier clock;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicInteger running = new AtomicInteger();
    private final ConcurrentHashMap<LookupKey, Slot> slots = new ConcurrentHashMap<>();

    /**
     * 构造应用服务解析器；缓存及后端由调用者拥有和关闭。
     *
     * @param mappingCache 本地映射控制面缓存
     * @param discovery 应用目录查询 SPI
     * @param manifestCache Manifest 本地缓存
     * @param maxStale 解析失败后允许沿用已验证路由的正时长，必须可转换为纳秒
     */
    public ApplicationServiceResolver(
            ServiceMappingCache mappingCache,
            ApplicationDiscovery discovery,
            ManifestSnapshotCache manifestCache,
            Duration maxStale) {
        this(mappingCache, discovery, manifestCache, maxStale, System::nanoTime);
    }

    /**
     * 可测试的单调时钟构造方法。
     *
     * @param mappingCache 映射缓存
     * @param discovery 应用目录
     * @param manifestCache Manifest 缓存
     * @param maxStale 最长 Last-Known-Good 正时长
     * @param clock 单调纳秒时钟
     */
    public ApplicationServiceResolver(
            ServiceMappingCache mappingCache,
            ApplicationDiscovery discovery,
            ManifestSnapshotCache manifestCache,
            Duration maxStale,
            LongSupplier clock) {
        this.mappingCache = Objects.requireNonNull(mappingCache, "mappingCache");
        this.discovery = Objects.requireNonNull(discovery, "discovery");
        this.manifestCache = Objects.requireNonNull(manifestCache, "manifestCache");
        this.clock = Objects.requireNonNull(clock, "clock");
        Objects.requireNonNull(maxStale, "maxStale");
        if (maxStale.isZero() || maxStale.isNegative()) {
            throw new IllegalArgumentException("maxStale must be positive");
        }
        try {
            this.staleNanos = maxStale.toNanos();
        } catch (ArithmeticException error) {
            throw new IllegalArgumentException("maxStale out of range", error);
        }
    }

    /**
     * 获取无远端 IO 的数据面路由结果，空 Optional 表示不可安全路由。
     *
     * @param key 服务键
     * @param expectedSchema 期望的完整 Schema 指纹
     * @return 最近一次已验证且未过期的 Provider 列表
     */
    public Optional<VerifiedProviderSnapshot> current(ServiceMappingKey key, String expectedSchema) {
        if (status(key, expectedSchema) == Status.HEALTHY
                || status(key, expectedSchema) == Status.LAST_KNOWN_GOOD) {
            return Optional.ofNullable(slots.get(new LookupKey(key, expectedSchema)))
                    .map(slot -> slot.state.snapshot);
        }
        return Optional.empty();
    }

    /**
     * 返回当前快照健康状态，不会请求 Registry 或 Metadata。
     *
     * @param key 服务映射键
     * @param expectedSchema 调用方 Schema 指纹
     * @return 状态枚举
     */
    public Status status(ServiceMappingKey key, String expectedSchema) {
        LookupKey route = new LookupKey(key, expectedSchema);
        if (closed.get()) {
            return Status.CLOSED;
        }
        Slot slot = slots.get(route);
        if (slot == null || slot.state.snapshot == null) {
            return Status.NOT_READY;
        }
        Long failure = slot.state.failureSince;
        if (failure == null) {
            return Status.HEALTHY;
        }
        return clock.getAsLong() - failure < staleNanos ? Status.LAST_KNOWN_GOOD : Status.STALE;
    }

    /**
     * 在控制面刷新完整解析链。单个业务键的并发刷新共享同一待完成任务。
     *
     * @param key 服务映射键
     * @param expectedSchema 调用方 Schema 指纹
     * @return 整体成功的 Provider 快照，任何子步骤失败则异常完成
     */
    public CompletionStage<VerifiedProviderSnapshot> refresh(ServiceMappingKey key, String expectedSchema) {
        LookupKey route = new LookupKey(key, expectedSchema);
        if (closed.get()) {
            return CompletableFuture.failedFuture(new IllegalStateException("Resolver closed"));
        }
        Slot slot = slots.get(route);
        if (slot == null) {
            synchronized (slots) {
                slot = slots.get(route);
                if (slot == null) {
                    if (slots.size() >= MAX_SERVICE_KEYS) {
                        return CompletableFuture.failedFuture(new IllegalStateException("Route capacity exceeded"));
                    }
                    slot = new Slot();
                    slots.put(route, slot);
                }
            }
        }
        CompletableFuture<VerifiedProviderSnapshot> target;
        synchronized (slot) {
            if (slot.pending != null) {
                return slot.pending;
            }
            if (running.incrementAndGet() > MAX_REFRESHES) {
                running.decrementAndGet();
                return CompletableFuture.failedFuture(new IllegalStateException("Too many discovery refreshes"));
            }
            target = new CompletableFuture<>();
            slot.pending = target;
            Slot selected = slot;
            target.whenComplete((snapshot, error) -> {
                synchronized (selected) {
                    if (!closed.get()) {
                        if (error == null) {
                            selected.state = new State(snapshot, null);
                        } else {
                            Long since = selected.state.failureSince;
                            selected.state = new State(selected.state.snapshot,
                                    since == null ? clock.getAsLong() : since);
                        }
                    }
                    if (selected.pending == target) {
                        selected.pending = null;
                    }
                    running.decrementAndGet();
                }
            });
        }
        CompletionStage<VerifiedProviderSnapshot> work;
        try {
            work = build(key, expectedSchema);
        } catch (Throwable error) {
            target.completeExceptionally(error);
            return target;
        }
        work.whenComplete((snapshot, error) -> {
            if (error != null) {
                target.completeExceptionally(error);
            } else {
                target.complete(snapshot);
            }
        });
        if (!target.isDone()) {
            CompletableFuture.delayedExecutor(REFRESH_TIMEOUT_SECONDS, TimeUnit.SECONDS).execute(() ->
                    target.completeExceptionally(new TimeoutException("Application discovery refresh timeout")));
        }
        return target;
    }

    private CompletionStage<VerifiedProviderSnapshot> build(ServiceMappingKey key, String expectedSchema) {
        return mappingCache.refresh(key).thenCompose(mapping -> {
            CompletionStage<List<ApplicationInstance>> result =
                    CompletableFuture.completedFuture(new ArrayList<>());
            AtomicInteger inspected = new AtomicInteger();
            for (ApplicationKey application : mapping.applications()) {
                result = result.thenCompose(found -> {
                    CompletionStage<ApplicationDirectorySnapshot> directory =
                            Objects.requireNonNull(discovery.lookup(application), "Application lookup");
                    return directory.thenCompose(snapshot -> {
                        if (!application.equals(snapshot.application())) {
                            throw new IllegalArgumentException("Application directory mismatch");
                        }
                        if (inspected.addAndGet(snapshot.instances().size()) > MAX_DIRECTORY_INSTANCES) {
                            throw new IllegalStateException("Provider discovery capacity exceeded");
                        }
                        CompletionStage<List<ApplicationInstance>> validated =
                                CompletableFuture.completedFuture(found);
                        for (ApplicationInstance instance : snapshot.instances()) {
                            if (!instance.routableProvider()
                                    || VerifiedProviderSnapshot.invalidEndpoint(instance.rpcEndpoint())) {
                                continue;
                            }
                            validated = validated.thenCompose(routes ->
                                    manifestCache.lookup(application, instance.manifestRevision())
                                            .thenApply(optional -> {
                                                ApplicationManifest manifest = optional.orElseThrow(() ->
                                                        new IllegalStateException("Missing application manifest"));
                                                if (containsCompatibleService(manifest, key, expectedSchema)) {
                                                    routes.add(instance);
                                                }
                                                return routes;
                                            }));
                        }
                        return validated;
                    });
                });
            }
            return result.thenApply(found -> new VerifiedProviderSnapshot(
                    key, expectedSchema, found, mapping.revision()));
        });
    }

    private static boolean containsCompatibleService(
            ApplicationManifest manifest, ServiceMappingKey key, String expectedSchema) {
        if (manifest.wireProtocolVersion() != ApplicationManifestCodec.WIRE_PROTOCOL_VERSION) {
            return false;
        }
        for (ExportedService exported : manifest.services()) {
            if (exported.serviceKey().equals(key.serviceKey())
                    && exported.schemaFingerprint().equals(expectedSchema)) {
                return true;
            }
        }
        return false;
    }

    /** 阻断新刷新，取消本地 pending Future；不关闭外部注入的缓存与 Discovery。 */
    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            slots.values().forEach(slot -> {
                CompletableFuture<VerifiedProviderSnapshot> pending;
                synchronized (slot) {
                    pending = slot.pending;
                }
                if (pending != null) {
                    pending.completeExceptionally(new IllegalStateException("Resolver closed"));
                }
            });
            slots.clear();
        }
    }

    private record LookupKey(ServiceMappingKey key, String schema) {
        private LookupKey {
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(schema, "schema");
            if (!schema.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("Invalid schema fingerprint");
            }
        }
    }

    private static final class Slot {
        private volatile State state = new State(null, null);
        private CompletableFuture<VerifiedProviderSnapshot> pending;
    }

    private record State(VerifiedProviderSnapshot snapshot, Long failureSince) {
    }
}
