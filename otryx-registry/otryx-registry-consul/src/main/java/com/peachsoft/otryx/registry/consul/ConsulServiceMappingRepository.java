package com.peachsoft.otryx.registry.consul;

import com.peachsoft.otryx.api.ApplicationInstance;
import com.peachsoft.otryx.api.ApplicationKey;
import com.peachsoft.otryx.api.ServiceKey;
import com.peachsoft.otryx.registry.ServiceMappingKey;
import com.peachsoft.otryx.registry.ServiceMappingOwner;
import com.peachsoft.otryx.registry.ServiceMappingRepository;
import com.peachsoft.otryx.registry.ServiceMappingSnapshot;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Consul KV 原生 CAS 的实例级 Mapping、持久 Fencing 与 Session GC。
 *
 * <p>claim 对稳定 Owner Key 执行 cas=0 或 ModifyIndex CAS；更新一个实例的
 * 完整 ServiceKey 集合也是单 Key CAS，两个 Provider 不会互相删除。
 * 另为每代 Owner 绑定 Behavior=delete 的 TTL Session 活性键；该 Key
 * 到期后由 Consul GC，稳定 Owner Key 始终保留 generation 墓碑。
 *
 * <p>Mapping.lookup 是控制面有界前缀扫描，同时验证 Session Marker 与
 * passing Provider 成员，不能仅凭过期 KV 将 Consumer-only 当作 Provider。
 *
 * @Author OpenAI
 * @Version 1.0.0-SNAPSHOT
 * @CreateTime 2026/10/16 14:12
 */
public final class ConsulServiceMappingRepository implements ServiceMappingRepository, AutoCloseable {

    private static final Logger LOGGER = LoggerFactory.getLogger(ConsulServiceMappingRepository.class);
    private static final int MAX_LOCAL_OWNERS = 16;
    private static final int MAX_CAS_ATTEMPTS = 16;
    private static final int MAX_CANDIDATE_APPLICATIONS = ServiceMappingSnapshot.MAX_APPLICATIONS;

    private final ConsulV2Http http;
    private final ConsulV2Executor executor;
    private final String namespace;
    private final int sessionTtlSeconds;
    private final ConcurrentMap<String, LocalClaim> claims = new ConcurrentHashMap<>();
    private final AtomicLong revision = new AtomicLong();
    private final AtomicBoolean closed = new AtomicBoolean();

    ConsulServiceMappingRepository(
            ConsulV2Http http, ConsulV2Executor executor, String namespace, int sessionTtlSeconds) {
        this.http = Objects.requireNonNull(http, "http");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.namespace = Objects.requireNonNull(namespace, "namespace");
        if (sessionTtlSeconds < 10) {
            throw new IllegalArgumentException("Consul Mapping Session TTL must be >= 10s");
        }
        this.sessionTtlSeconds = sessionTtlSeconds;
    }

    /** 通过原生 KV CAS 单调增加 Owner Generation，先废止旧一代的全部导出。 */
    @Override
    public CompletionStage<ServiceMappingOwner> claim(ApplicationKey application, String instanceId) {
        requireNamespace(application);
        ServiceMappingOwner identity = new ServiceMappingOwner(application, instanceId, 1);
        String key = ConsulV2Keys.owner(application, identity.instanceId());
        if (!claims.containsKey(key) && claims.size() >= MAX_LOCAL_OWNERS) {
            return CompletableFuture.failedFuture(new IllegalStateException("Consul local Owner capacity exceeded"));
        }
        requireOpen();
        return executor.submit("consul-mapping-claim", () -> {
            for (int attempt = 0; attempt < MAX_CAS_ATTEMPTS; attempt++) {
                ConsulV2Http.KvEntry prior = http.get(key, ConsulV2Http.MAX_MAPPING_VALUE_BYTES);
                long previousGeneration = prior == null ? 0L
                        : ConsulV2MappingCodec.decode(key, namespace, prior.value()).owner().generation();
                if (previousGeneration == Long.MAX_VALUE) {
                    throw new IllegalStateException("Consul Mapping generation exhausted");
                }
                ServiceMappingOwner owner = new ServiceMappingOwner(application, instanceId, previousGeneration + 1);
                ConsulV2MappingCodec.OwnerState state =
                        new ConsulV2MappingCodec.OwnerState(owner, UUID.randomUUID().toString(), Set.of());
                long expected = prior == null ? 0L : prior.modifyIndex();
                if (!http.compareAndSet(key, ConsulV2MappingCodec.encode(state),
                        expected, ConsulV2Http.MAX_MAPPING_VALUE_BYTES)) {
                    continue;
                }
                String sessionId = http.createSession(sessionTtlSeconds);
                try {
                    String live = ConsulV2Keys.live(application, instanceId, owner.generation());
                    if (!http.acquire(live, ConsulV2MappingCodec.marker(state), sessionId)) {
                        throw new IllegalStateException("Consul Mapping Session acquire rejected");
                    }
                    ConsulV2Http.KvEntry confirmed = http.get(key, ConsulV2Http.MAX_MAPPING_VALUE_BYTES);
                    if (confirmed == null || !state.equals(
                            ConsulV2MappingCodec.decode(key, namespace, confirmed.value()))) {
                        throw new IllegalStateException("Consul Mapping claim superseded during Session acquisition");
                    }
                    LocalClaim claim = new LocalClaim(state, sessionId);
                    claim.renewal = executor.periodic("consul-session-renew",
                            Duration.ofSeconds(Math.max(1L, sessionTtlSeconds / 3L)),
                            () -> {
                                if (!claim.lost.get() && !closed.get()) {
                                    if (!http.renewSession(sessionId)) {
                                        claim.lost.set(true);
                                        LOGGER.warn("Consul Mapping Session expired; owner must be reclaimed");
                                    }
                                }
                                return null;
                            });
                    LocalClaim previous = claims.compute(key, (ignored, current) -> current == null
                            || current.state.owner().generation() < owner.generation() ? claim : current);
                    if (previous != claim) {
                        retire(claim);
                        throw new IllegalStateException("Consul Mapping local generation was superseded");
                    }
                    return owner;
                } catch (Exception failure) {
                    try {
                        http.destroySession(sessionId);
                    } catch (Exception cleanup) {
                        failure.addSuppressed(cleanup);
                    }
                    throw failure;
                }
            }
            throw new IllegalStateException("Consul Mapping CAS claim retries exhausted");
        });
    }

    /** CAS 原子替换本代 Owner 的完整接口集合。 */
    @Override
    public CompletionStage<Void> publish(ServiceMappingOwner owner, Set<ServiceKey> services) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(services, "services");
        requireNamespace(owner.application());
        return change(owner, Set.copyOf(services), false);
    }

    /** 幂等撤销本代 Owner；保留持久 Generation 墓碑并销毁活性 Session。 */
    @Override
    public CompletionStage<Void> revoke(ServiceMappingOwner owner) {
        Objects.requireNonNull(owner, "owner");
        requireNamespace(owner.application());
        return change(owner, Set.of(), true);
    }

    private CompletionStage<Void> change(ServiceMappingOwner owner, Set<ServiceKey> next, boolean revoking) {
        String key = ConsulV2Keys.owner(owner.application(), owner.instanceId());
        LocalClaim claim = claims.get(key);
        if (claim == null || !claim.state.owner().equals(owner)) {
            return revoking ? CompletableFuture.completedFuture(null)
                    : CompletableFuture.failedFuture(new IllegalStateException("Stale Consul Mapping Owner"));
        }
        return executor.submit(revoking ? "consul-mapping-revoke" : "consul-mapping-publish", () -> {
            for (int attempt = 0; attempt < MAX_CAS_ATTEMPTS; attempt++) {
                if (claim.lost.get()) {
                    throw new IllegalStateException("Consul Mapping Session is no longer valid");
                }
                ConsulV2Http.KvEntry live = http.get(
                        ConsulV2Keys.live(owner.application(), owner.instanceId(), owner.generation()),
                        ConsulV2Http.MAX_MAPPING_VALUE_BYTES);
                if (!ConsulV2MappingCodec.validMarker(live, claim.state)
                        || !claim.sessionId.equals(live.sessionId())) {
                    claim.lost.set(true);
                    throw new IllegalStateException("Consul Mapping owner has lost Session liveness");
                }
                ConsulV2Http.KvEntry existing = http.get(key, ConsulV2Http.MAX_MAPPING_VALUE_BYTES);
                if (existing == null) {
                    throw new IllegalStateException("Consul Mapping fencing key disappeared");
                }
                ConsulV2MappingCodec.OwnerState remote =
                        ConsulV2MappingCodec.decode(key, namespace, existing.value());
                if (!remote.owner().equals(owner) || !remote.token().equals(claim.state.token())) {
                    if (revoking) {
                        closeClaim(key, claim);
                        return null;
                    }
                    throw new IllegalStateException("Consul Mapping generation was superseded");
                }
                if (remote.services().equals(next)) {
                    if (revoking) {
                        closeClaim(key, claim);
                    }
                    return null;
                }
                ConsulV2MappingCodec.OwnerState updated =
                        new ConsulV2MappingCodec.OwnerState(owner, claim.state.token(), next);
                if (http.compareAndSet(key, ConsulV2MappingCodec.encode(updated),
                        existing.modifyIndex(), ConsulV2Http.MAX_MAPPING_VALUE_BYTES)) {
                    if (revoking) {
                        closeClaim(key, claim);
                    }
                    return null;
                }
            }
            throw new IllegalStateException("Consul Mapping CAS publish retries exhausted");
        });
    }

    /**
     * 枚举固定分片，严格关联当前 Generation、Session 键和 passing Provider 实例。
     *
     * <p>这是有界控制面发现，不在 RPC 请求热路径执行。单个 Consul KV Scan
     * 响应最多 64 个 Owner 和 128 个短寿命 Marker；超过时拒绝而非截断。
     */
    @Override
    public CompletionStage<ServiceMappingSnapshot> lookup(ServiceMappingKey key) {
        Objects.requireNonNull(key, "key");
        if (!namespace.equals(key.namespace())) {
            throw new IllegalArgumentException("Consul Mapping lookup namespace mismatch");
        }
        requireOpen();
        return executor.submit("consul-mapping-lookup", () -> {
            List<ConsulV2MappingCodec.OwnerState> candidates = new ArrayList<>();
            for (int shard = 0; shard < ConsulV2Keys.OWNER_SHARDS; shard++) {
                List<ConsulV2Http.KvEntry> owners = http.scan(
                        ConsulV2Keys.ownerPrefix(namespace, shard), ConsulV2Keys.MAX_OWNERS_PER_SHARD,
                        ConsulV2Http.MAX_MAPPING_VALUE_BYTES);
                List<ConsulV2Http.KvEntry> live = http.scan(
                        ConsulV2Keys.livePrefix(namespace, shard), ConsulV2Keys.MAX_OWNERS_PER_SHARD * 2,
                        ConsulV2Http.MAX_MAPPING_VALUE_BYTES);
                Map<String, ConsulV2Http.KvEntry> markers = new HashMap<>();
                for (ConsulV2Http.KvEntry entry : live) {
                    if (markers.putIfAbsent(entry.key(), entry) != null) {
                        throw new IllegalStateException("Duplicate Consul Session Marker");
                    }
                }
                for (ConsulV2Http.KvEntry entry : owners) {
                    ConsulV2MappingCodec.OwnerState record =
                            ConsulV2MappingCodec.decode(entry.key(), namespace, entry.value());
                    if (record.services().contains(key.serviceKey())
                            && ConsulV2MappingCodec.validMarker(
                            markers.get(ConsulV2Keys.live(record.owner().application(),
                                    record.owner().instanceId(), record.owner().generation())), record)) {
                        candidates.add(record);
                    }
                }
            }
            Set<ApplicationKey> applications = new HashSet<>();
            Map<ApplicationKey, List<ApplicationInstance>> healthy = new HashMap<>();
            for (ConsulV2MappingCodec.OwnerState candidate : candidates) {
                ApplicationKey app = candidate.owner().application();
                List<ApplicationInstance> members = healthy.get(app);
                if (members == null) {
                    members = new ArrayList<>();
                    for (var raw : http.healthy(ConsulV2Keys.serviceName(app))) {
                        ApplicationInstance member = ConsulV2MemberCodec.decode(app, raw);
                        if (member != null) {
                            members.add(member);
                        }
                    }
                    healthy.put(app, members);
                }
                boolean currentProvider = members.stream().anyMatch(member ->
                        member.instanceId().equals(candidate.owner().instanceId()) && member.routableProvider());
                if (currentProvider) {
                    applications.add(app);
                    if (applications.size() > MAX_CANDIDATE_APPLICATIONS) {
                        throw new IllegalStateException("Consul Mapping application fanout exceeded");
                    }
                }
            }
            return new ServiceMappingSnapshot(key, applications, revision.incrementAndGet());
        });
    }

    private void closeClaim(String key, LocalClaim claim) throws Exception {
        if (claims.remove(key, claim)) {
            retire(claim);
        }
    }

    private void retire(LocalClaim claim) throws Exception {
        if (claim.lost.compareAndSet(false, true)) {
            executor.cancel(claim.renewal);
        } else {
            executor.cancel(claim.renewal);
        }
        http.destroySession(claim.sessionId);
    }

    /** 安全释放本 Registry 创建的 Session，不删除持久 Fencing KV 记录。 */
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        for (Map.Entry<String, LocalClaim> entry : claims.entrySet()) {
            if (claims.remove(entry.getKey(), entry.getValue())) {
                try {
                    retire(entry.getValue());
                } catch (Exception failure) {
                    LOGGER.warn("Consul Mapping Session cleanup failed: errorType={}",
                            failure.getClass().getSimpleName());
                }
            }
        }
    }

    private void requireNamespace(ApplicationKey app) {
        Objects.requireNonNull(app, "application");
        if (!namespace.equals(app.namespace())) {
            throw new IllegalArgumentException("Consul Mapping owner namespace mismatch");
        }
    }

    private void requireOpen() {
        if (closed.get()) {
            throw new IllegalStateException("Consul Mapping repository is closed");
        }
    }

    private static final class LocalClaim {
        private final ConsulV2MappingCodec.OwnerState state;
        private final String sessionId;
        private final AtomicBoolean lost = new AtomicBoolean();
        private volatile ScheduledFuture<?> renewal;

        private LocalClaim(ConsulV2MappingCodec.OwnerState state, String sessionId) {
            this.state = state;
            this.sessionId = sessionId;
        }
    }
}
