package com.peachsoft.otryx.registry;

import com.peachsoft.otryx.api.ApplicationInstance;
import com.peachsoft.otryx.api.ApplicationKey;
import com.peachsoft.otryx.api.ApplicationManifest;
import com.peachsoft.otryx.api.ApplicationManifestCodec;
import com.peachsoft.otryx.api.ExportedService;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * 应用级 RPC 服务解析器：Mapping -> Directory -> Manifest -> Schema -> Provider。
 *
 * <p>远端 IO 仅发生在显式 refresh；任一安全拒绝立即使本 Resolver 的旧路由失效。
 * 每个服务须在新 epoch 内完整验证后独立恢复。普通暂时断网仍保留有限 LKG。
 * 路由候选不提供 RPC 身份或授权，实际 Transport 和 Authorizer 必须独立验证。
 *
 * @Author OpenAI
 * @Version 1.0.0-SNAPSHOT
 * @CreateTime 2026/10/15 18:30
 */
public final class ApplicationServiceResolver implements AutoCloseable, RegistrySecuritySource {
    private static final int MAX_SERVICE_KEYS = 512;
    private static final int MAX_REFRESHES = 32;
    private static final int MAX_DIRECTORY_INSTANCES = 1024;
    private static final long REFRESH_TIMEOUT_SECONDS = 30L;
    private static final long UNVERIFIED_EPOCH = -1L;

    /** 本地路由目录状态，不等价于 Registry 后端健康状态。 */
    public enum Status {
        /** 尚未获得完整的成功快照。 */
        NOT_READY,
        /** 最后一次完整解析成功，包括合法空列表。 */
        HEALTHY,
        /** 普通网络失败，允许短期使用已验证快照。 */
        LAST_KNOWN_GOOD,
        /** 普通失败超过允许陈旧时间。 */
        STALE,
        /** 当前安全 epoch 尚未完整验证本服务，旧候选不可使用。 */
        SECURITY_REJECTED,
        /** 已关闭，不再接受刷新或本地读取。 */
        CLOSED
    }

    private final ServiceMappingCache mappingCache;
    private final ApplicationDiscovery discovery;
    private final ManifestSnapshotCache manifestCache;
    private final long staleNanos;
    private final LongSupplier clock;
    private final Object securityLock = new Object();
    private final Object authority = new Object();
    private final Map<LookupKey, Slot> slots = new HashMap<>();
    private SecurityEpoch securityEpoch = new SecurityEpoch(0L, true, null);
    private boolean closed;
    private int running;

    /**
     * 构造应用服务解析器；缓存及后端由调用者拥有和关闭。
     *
     * @param mappingCache 本地映射缓存
     * @param discovery 实际应用目录 SPI
     * @param manifestCache Manifest 本地缓存
     * @param maxStale 普通失败后允许沿用可信路由的正时长
     */
    public ApplicationServiceResolver(ServiceMappingCache mappingCache, ApplicationDiscovery discovery,
                                      ManifestSnapshotCache manifestCache, Duration maxStale) {
        this(mappingCache, discovery, manifestCache, maxStale, System::nanoTime);
    }

    /**
     * 注入单调时钟；安全失效不依赖刷新计时器或 LKG 期限。
     *
     * @param mappingCache 映射缓存
     * @param discovery 应用目录
     * @param manifestCache Manifest 缓存
     * @param maxStale 最长 LKG 正时长
     * @param clock 单调纳秒时钟
     */
    public ApplicationServiceResolver(ServiceMappingCache mappingCache, ApplicationDiscovery discovery,
                                      ManifestSnapshotCache manifestCache, Duration maxStale, LongSupplier clock) {
        this.mappingCache = Objects.requireNonNull(mappingCache, "mappingCache");
        this.discovery = Objects.requireNonNull(discovery, "discovery");
        this.manifestCache = Objects.requireNonNull(manifestCache, "manifestCache");
        this.clock = Objects.requireNonNull(clock, "clock");
        Objects.requireNonNull(maxStale, "maxStale");
        if (maxStale.isZero() || maxStale.isNegative()) {
            throw new IllegalArgumentException("maxStale must be positive");
        }
        try {
            staleNanos = maxStale.toNanos();
        } catch (ArithmeticException rejected) {
            throw new IllegalArgumentException("maxStale out of range");
        }
    }

    /** 从实际 Discovery 和缓存持有的 Repository 聚合全部参与来源。 */
    @Override
    public RegistrySecurityDescriptor securityDescriptor() {
        return RegistrySecurityDescriptor.combine(
                RegistrySecurityDescriptor.from(discovery, RegistryChannel.MEMBERSHIP),
                manifestCache.securityDescriptor(), mappingCache.securityDescriptor());
    }

    /**
     * 无 IO 读取当前完整且仍可用的路由；安全失效返回空 Optional。
     *
     * @param key 服务键
     * @param expectedSchema 期望完整 Schema 指纹
     * @return 已验证的本地 Provider 快照
     */
    public Optional<VerifiedProviderSnapshot> current(ServiceMappingKey key, String expectedSchema) {
        LookupKey route = new LookupKey(key, expectedSchema);
        synchronized (securityLock) {
            Slot slot = slots.get(route);
            Status status = statusLocked(slot);
            return status == Status.HEALTHY || status == Status.LAST_KNOWN_GOOD
                    ? Optional.of(slot.state.snapshot()) : Optional.empty();
        }
    }

    /**
     * 返回本地状态；不创建 Slot、刷新或请求远端。
     *
     * @param key 服务映射键
     * @param expectedSchema 调用方 Schema 指纹
     * @return 当前一致状态
     */
    public Status status(ServiceMappingKey key, String expectedSchema) {
        LookupKey route = new LookupKey(key, expectedSchema);
        synchronized (securityLock) {
            return statusLocked(slots.get(route));
        }
    }

    /**
     * 无 IO 复核同一候选的私有来源、路由凭据和安全 epoch。
     *
     * <p>普通未就绪、同 epoch 被替换、LKG 过期或关闭返回 false；安全失效抛稳定异常，
     * 不得泛化为可重试的不可达。null 候选仍先检查当前服务安全状态。旧 epoch 检查
     * 早于当前对象身份比较，即使服务已恢复，新验证也不会使旧候选重新获得信任。
     *
     * @param key 非 null 服务映射键
     * @param expectedSchema 合法完整 Schema 指纹
     * @param candidate 可为 null；非 null 时必须是本 Resolver 完整验证后发布的同一对象
     * @return 候选当前可用时返回 true
     * @throws RegistrySecurityException 来源、路由或当前安全 epoch 验证失败
     */
    public boolean isSnapshotUsable(
            ServiceMappingKey key, String expectedSchema, VerifiedProviderSnapshot candidate) {
        LookupKey route = new LookupKey(key, expectedSchema);
        synchronized (securityLock) {
            if (closed) {
                return false;
            }
            Slot slot = slots.get(route);
            if (candidate != null) {
                if (!key.equals(candidate.key()) || !expectedSchema.equals(candidate.schemaFingerprint())) {
                    throw new RegistrySecurityException(RegistrySecurityFailure.METADATA_REFERENCE_MISMATCH);
                }
                if (slot == null || !candidate.hasAuthority(authority, slot.routeProof)) {
                    throw new RegistrySecurityException(RegistrySecurityFailure.METADATA_INTEGRITY_REJECTED);
                }
                if (candidate.verifiedEpoch() != securityEpoch.value()) {
                    throw currentSecurityFailure();
                }
            }
            Status status = statusLocked(slot);
            if (status == Status.SECURITY_REJECTED) {
                throw currentSecurityFailure();
            }
            return candidate != null && slot != null && slot.state.snapshot() == candidate
                    && (status == Status.HEALTHY || status == Status.LAST_KNOWN_GOOD);
        }
    }

    private Status statusLocked(Slot slot) {
        if (closed) {
            return Status.CLOSED;
        }
        if (securityEpoch.reason() != null && (!securityEpoch.invalidationReady()
                || slot == null || slot.state.verifiedEpoch() != securityEpoch.value())) {
            return Status.SECURITY_REJECTED;
        }
        if (slot == null || slot.state.snapshot() == null) {
            return Status.NOT_READY;
        }
        Long failure = slot.state.failureSince();
        return failure == null ? Status.HEALTHY
                : clock.getAsLong() - failure < staleNanos ? Status.LAST_KNOWN_GOOD : Status.STALE;
    }

    /**
     * 控制面单飞刷新完整解析链；成功必须在同一安全 epoch 的受保护提交中发布。
     *
     * @param key 服务映射键
     * @param expectedSchema 调用方 Schema 指纹
     * @return 完整快照或异常；取消先释放本地 pending 配额再通知调用方
     */
    public CompletionStage<VerifiedProviderSnapshot> refresh(ServiceMappingKey key, String expectedSchema) {
        LookupKey route = new LookupKey(key, expectedSchema);
        RefreshFuture target;
        synchronized (securityLock) {
            if (closed) {
                return CompletableFuture.failedFuture(new IllegalStateException("Resolver closed"));
            }
            if (!securityEpoch.invalidationReady()) {
                return CompletableFuture.failedFuture(currentSecurityFailure());
            }
            Slot slot = slots.get(route);
            if (slot == null) {
                if (slots.size() >= MAX_SERVICE_KEYS) {
                    return CompletableFuture.failedFuture(new IllegalStateException("Route capacity exceeded"));
                }
                slot = new Slot();
                slots.put(route, slot);
            }
            if (slot.pending != null) {
                return slot.pending;
            }
            if (running >= MAX_REFRESHES) {
                return CompletableFuture.failedFuture(new IllegalStateException("Too many discovery refreshes"));
            }
            target = new RefreshFuture(route, slot, securityEpoch.value());
            slot.pending = target;
            running++;
        }
        target.orTimeout(REFRESH_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        CompletionStage<VerifiedProviderSnapshot> work;
        try {
            work = build(target);
        } catch (Throwable failure) {
            finish(target, null, failure);
            return target;
        }
        work.whenComplete((snapshot, failure) -> finish(target, snapshot, failure));
        return target;
    }

    private boolean finish(RefreshFuture target, VerifiedProviderSnapshot snapshot, Throwable error) {
        Throwable failure = RegistryFailures.unwrap(error);
        VerifiedProviderSnapshot verified = null;
        List<RefreshFuture> invalidated = null;
        SecurityEpoch rejectedEpoch = null;
        synchronized (securityLock) {
            if (target.slot.pending != target) {
                return false;
            }
            if (closed) {
                failure = new IllegalStateException("Resolver closed");
            } else if (target.epoch != securityEpoch.value()) {
                failure = currentSecurityFailure();
            } else if (failure == null && snapshot == null) {
                failure = new RegistrySecurityException(RegistrySecurityFailure.METADATA_INTEGRITY_REJECTED);
            }
            if (!closed && target.epoch == securityEpoch.value()
                    && failure instanceof RegistrySecurityException rejected) {
                rejectedEpoch = new SecurityEpoch(securityEpoch.value() + 1L, false, rejected.reason());
                securityEpoch = rejectedEpoch;
                invalidated = slots.values().stream().map(slot -> slot.pending)
                        .filter(Objects::nonNull).toList();
                slots.values().forEach(slot -> slot.pending = null);
                running = 0;
            } else {
                if (!closed && failure == null) {
                    verified = new VerifiedProviderSnapshot(snapshot.key(), snapshot.schemaFingerprint(),
                            snapshot.providers(), snapshot.mappingRevision(), authority, target.epoch,
                            target.slot.routeProof);
                    target.slot.state = new State(verified, null, target.epoch);
                } else if (!closed) {
                    State previous = target.slot.state;
                    target.slot.state = new State(previous.snapshot(),
                            previous.failureSince() == null ? clock.getAsLong() : previous.failureSince(),
                            previous.verifiedEpoch());
                }
                target.slot.pending = null;
                running--;
            }
        }
        if (invalidated != null) {
            completeSecurityInvalidation(rejectedEpoch, invalidated);
            return true;
        }
        return target.publish(verified, failure);
    }

    private void completeSecurityInvalidation(SecurityEpoch rejected, List<RefreshFuture> invalidated) {
        // The epoch already blocks every old route before either cache starts clearing.
        manifestCache.invalidateForSecurity(rejected.reason());
        mappingCache.invalidateForSecurity(rejected.reason());
        synchronized (securityLock) {
            if (securityEpoch == rejected) {
                securityEpoch = new SecurityEpoch(rejected.value(), true, rejected.reason());
            }
        }
        invalidated.forEach(target ->
                target.publish(null, new RegistrySecurityException(rejected.reason())));
    }

    private RegistrySecurityException currentSecurityFailure() {
        RegistrySecurityFailure reason = securityEpoch.reason();
        return new RegistrySecurityException(reason == null
                ? RegistrySecurityFailure.METADATA_INTEGRITY_REJECTED : reason);
    }

    private void requireActive(RefreshFuture target) {
        synchronized (securityLock) {
            if (closed) {
                throw new IllegalStateException("Resolver closed");
            }
            if (target.epoch != securityEpoch.value() || !securityEpoch.invalidationReady()) {
                throw currentSecurityFailure();
            }
            if (target.slot.pending != target) {
                throw new CancellationException("Application discovery refresh no longer active");
            }
        }
    }

    private CompletionStage<VerifiedProviderSnapshot> build(RefreshFuture target) {
        ServiceMappingKey key = target.route.key();
        String expectedSchema = target.route.schema();
        requireActive(target);
        return mappingCache.refresh(key).thenCompose(mapping -> {
            requireActive(target);
            CompletionStage<List<ApplicationInstance>> result =
                    CompletableFuture.completedFuture(new ArrayList<>());
            int[] inspected = {0};
            for (ApplicationKey application : mapping.applications()) {
                result = result.thenCompose(found -> {
                    requireActive(target);
                    CompletionStage<ApplicationDirectorySnapshot> directory = discovery.lookup(application);
                    if (directory == null) {
                        throw new RegistrySecurityException(RegistrySecurityFailure.METADATA_INTEGRITY_REJECTED);
                    }
                    return directory.thenCompose(snapshot -> {
                        requireActive(target);
                        if (snapshot == null) {
                            throw new RegistrySecurityException(RegistrySecurityFailure.METADATA_INTEGRITY_REJECTED);
                        }
                        if (!application.equals(snapshot.application())) {
                            throw new RegistrySecurityException(RegistrySecurityFailure.METADATA_REFERENCE_MISMATCH);
                        }
                        inspected[0] += snapshot.instances().size();
                        if (inspected[0] > MAX_DIRECTORY_INSTANCES) {
                            throw new RegistrySecurityException(RegistrySecurityFailure.RESPONSE_LIMIT_EXCEEDED);
                        }
                        CompletionStage<List<ApplicationInstance>> validated =
                                CompletableFuture.completedFuture(found);
                        for (ApplicationInstance instance : snapshot.instances()) {
                            if (!instance.routableProvider()
                                    || VerifiedProviderSnapshot.invalidEndpoint(instance.rpcEndpoint())) {
                                continue;
                            }
                            validated = validated.thenCompose(routes -> {
                                requireActive(target);
                                return manifestCache.lookup(application, instance.manifestRevision())
                                        .thenApply(optional -> {
                                            requireActive(target);
                                            ApplicationManifest manifest = optional.orElseThrow(() ->
                                                    new IllegalStateException("Missing application manifest"));
                                            if (containsCompatibleService(manifest, key, expectedSchema)) {
                                                routes.add(instance);
                                            }
                                            return routes;
                                        });
                            });
                        }
                        return validated;
                    });
                });
            }
            return result.thenApply(found -> {
                requireActive(target);
                return new VerifiedProviderSnapshot(key, expectedSchema, found, mapping.revision());
            });
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

    /** 阻断刷新并在锁外结束 pending；不关闭外部注入的缓存与 Discovery。 */
    @Override
    public void close() {
        List<RefreshFuture> pending;
        synchronized (securityLock) {
            if (closed) {
                return;
            }
            closed = true;
            pending = slots.values().stream().map(slot -> slot.pending).filter(Objects::nonNull).toList();
            slots.values().forEach(slot -> slot.pending = null);
            slots.clear();
            running = 0;
        }
        pending.forEach(target -> target.publish(null, new IllegalStateException("Resolver closed")));
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

    /** 公共 complete 不能铸造本地信任；只有私有完整验证链可以发布成功。 */
    private final class RefreshFuture extends CompletableFuture<VerifiedProviderSnapshot> {
        private final LookupKey route;
        private final Slot slot;
        private final long epoch;

        private RefreshFuture(LookupKey route, Slot slot, long epoch) {
            this.route = route;
            this.slot = slot;
            this.epoch = epoch;
        }

        @Override
        public boolean complete(VerifiedProviderSnapshot ignored) {
            return false;
        }

        @Override
        public boolean completeExceptionally(Throwable failure) {
            return finish(this, null, Objects.requireNonNull(failure, "failure"));
        }

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            return finish(this, null, new CancellationException("Application discovery refresh cancelled"))
                    || isCancelled();
        }

        private boolean publish(VerifiedProviderSnapshot snapshot, Throwable failure) {
            return failure == null ? super.complete(snapshot) : super.completeExceptionally(failure);
        }
    }

    private static final class Slot {
        private final Object routeProof = new Object();
        private State state = new State(null, null, UNVERIFIED_EPOCH);
        private RefreshFuture pending;
    }

    private record State(VerifiedProviderSnapshot snapshot, Long failureSince, long verifiedEpoch) {
    }

    private record SecurityEpoch(long value, boolean invalidationReady, RegistrySecurityFailure reason) {
    }
}
