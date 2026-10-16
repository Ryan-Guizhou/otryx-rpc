package com.peachsoft.otryx.registry.nacos;

import com.peachsoft.otryx.api.ApplicationKey;
import com.peachsoft.otryx.api.ServiceKey;
import com.peachsoft.otryx.registry.ServiceMappingKey;
import com.peachsoft.otryx.registry.ServiceMappingOwner;
import com.peachsoft.otryx.registry.ServiceMappingRepository;
import com.peachsoft.otryx.registry.ServiceMappingSnapshot;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 采用 Nacos Config MD5-CAS 的实例级分片 ServiceMapping Repository。
 *
 * <p>每个 Owner 的 generation、token 和完整导出服务集合在一个 CAS
 * 文档内原子变更。撤销保留 generation 墓碑，以保证同实例重启后旧 token
 * 不能误删新发布。映射是控制面候选应用，Core 必须进一步校验 Naming
 * 存活成员、Manifest、Schema 和 Endpoint，不能直接将映射当成可路由结果。
 *
 * @Author OpenAI
 * @Version 1.0.0-SNAPSHOT
 * @CreateTime 2026/10/16 13:42
 */
public final class NacosServiceMappingRepository implements ServiceMappingRepository {

    private static final int MAX_CLAIMS_PER_PROCESS = 512;
    private static final int MAX_APPLICATIONS = ServiceMappingSnapshot.MAX_APPLICATIONS;

    private final NacosV2ConfigStore store;
    private final NacosControlExecutor executor;
    private final String namespace;
    private final ConcurrentMap<String, NacosV2MappingCodec.OwnerState> claims = new ConcurrentHashMap<>();
    private final AtomicLong localRevision = new AtomicLong();

    NacosServiceMappingRepository(NacosV2ConfigStore store, NacosControlExecutor executor, String namespace) {
        this.store = Objects.requireNonNull(store, "store");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.namespace = Objects.requireNonNull(namespace, "namespace");
    }

    /** 通过持久化 Config CAS 颁发同实例单调 fencing generation。 */
    @Override
    public CompletionStage<ServiceMappingOwner> claim(ApplicationKey application, String instanceId) {
        requireNamespace(application);
        ServiceMappingOwner checked = new ServiceMappingOwner(application, instanceId, 1);
        String id = NacosV2MappingCodec.identifier(checked);
        if (!claims.containsKey(id) && claims.size() >= MAX_CLAIMS_PER_PROCESS) {
            return CompletableFuture.failedFuture(new IllegalStateException("Nacos mapping owner capacity exceeded"));
        }
        return executor.submit("mapping-claim", id, () -> {
            int shard = NacosV2Names.shard(application, checked.instanceId());
            String dataId = NacosV2Names.shardId(shard);
            for (int attempt = 0; attempt < NacosV2ConfigStore.MAX_CAS_ATTEMPTS; attempt++) {
                NacosV2ConfigStore.Entry entry = store.read(dataId);
                if (entry.content() == null) {
                    throw new IllegalStateException("Nacos mapping shard is not initialized; run V2 bootstrap");
                }
                Map<String, NacosV2MappingCodec.OwnerState> records =
                        new HashMap<>(NacosV2MappingCodec.decode(entry.content(), namespace, shard));
                NacosV2MappingCodec.OwnerState previous = records.get(id);
                long generation = previous == null ? 0 : previous.owner().generation();
                if (generation == Long.MAX_VALUE) {
                    throw new IllegalStateException("Nacos mapping generation exhausted");
                }
                ServiceMappingOwner owner = new ServiceMappingOwner(application, checked.instanceId(), generation + 1);
                NacosV2MappingCodec.OwnerState next = new NacosV2MappingCodec.OwnerState(
                        owner, UUID.randomUUID().toString(), Set.of());
                records.put(id, next);
                if (store.compareAndSet(dataId, NacosV2MappingCodec.encode(records), entry)) {
                    claims.compute(id, (ignored, current) ->
                            current == null || current.owner().generation() < owner.generation()
                                    ? next : current);
                    return owner;
                }
            }
            throw new IllegalStateException("Nacos mapping claim CAS exhausted");
        });
    }

    /** 原子替换本 Owner 的全部接口，旧进程延迟写入不能覆盖新代数。 */
    @Override
    public CompletionStage<Void> publish(ServiceMappingOwner owner, Set<ServiceKey> services) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(services, "services");
        requireNamespace(owner.application());
        if (services.size() > NacosV2MappingCodec.MAX_OWNER_SERVICES) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Too many exported Nacos services"));
        }
        return change(owner, Set.copyOf(services), false);
    }

    /** 仅撤销当前代数，其他实例和新的 generation 不受影响。 */
    @Override
    public CompletionStage<Void> revoke(ServiceMappingOwner owner) {
        Objects.requireNonNull(owner, "owner");
        requireNamespace(owner.application());
        return change(owner, Set.of(), true);
    }

    private CompletionStage<Void> change(ServiceMappingOwner owner, Set<ServiceKey> exports, boolean revoking) {
        String id = NacosV2MappingCodec.identifier(owner);
        NacosV2MappingCodec.OwnerState claim = claims.get(id);
        if (claim == null || !claim.owner().equals(owner)) {
            return revoking ? CompletableFuture.completedFuture(null)
                    : CompletableFuture.failedFuture(new IllegalStateException("Stale Nacos mapping owner"));
        }
        return executor.submit(revoking ? "mapping-revoke" : "mapping-publish", id, () -> {
            int shard = NacosV2Names.shard(owner.application(), owner.instanceId());
            String dataId = NacosV2Names.shardId(shard);
            for (int attempt = 0; attempt < NacosV2ConfigStore.MAX_CAS_ATTEMPTS; attempt++) {
                NacosV2ConfigStore.Entry entry = store.read(dataId);
                if (entry.content() == null) {
                    throw new IllegalStateException("Nacos mapping shard is not initialized; run V2 bootstrap");
                }
                Map<String, NacosV2MappingCodec.OwnerState> records =
                        new HashMap<>(NacosV2MappingCodec.decode(entry.content(), namespace, shard));
                NacosV2MappingCodec.OwnerState current = records.get(id);
                if (current == null || !current.owner().equals(owner)
                        || !current.token().equals(claim.token())) {
                    if (revoking) {
                        claims.remove(id, claim);
                        return null;
                    }
                    throw new IllegalStateException("Nacos mapping generation is stale");
                }
                if (current.services().equals(exports)) {
                    if (revoking) {
                        claims.remove(id, claim);
                    }
                    return null;
                }
                records.put(id, new NacosV2MappingCodec.OwnerState(owner, claim.token(), exports));
                if (store.compareAndSet(dataId, NacosV2MappingCodec.encode(records), entry)) {
                    if (revoking) {
                        claims.remove(id, claim);
                    }
                    return null;
                }
            }
            throw new IllegalStateException("Nacos mapping mutation CAS exhausted");
        });
    }

    /**
     * 枚举固定的 8 个有界 CAS 分片，获得候选应用快照。
     *
     * <p>Nacos 不提供跨 Config 的 MVCC 修订点；结果仅是发现候选，
     * 真实可路由性必须经过 Core 对 Naming、Manifest 和 Schema 的再验证。
     */
    @Override
    public CompletionStage<ServiceMappingSnapshot> lookup(ServiceMappingKey key) {
        Objects.requireNonNull(key, "key");
        if (!namespace.equals(key.namespace())) {
            throw new IllegalArgumentException("Nacos mapping lookup crosses namespace");
        }
        return executor.submit("mapping-lookup", key.serviceKey(), () -> {
            Set<ApplicationKey> applications = new HashSet<>();
            for (int shard = 0; shard < NacosV2Names.MAPPING_SHARDS; shard++) {
                String dataId = NacosV2Names.shardId(shard);
                NacosV2ConfigStore.Entry entry = store.read(dataId);
                if (entry.content() == null) {
                    throw new IllegalStateException("Nacos mapping shard is not initialized; run V2 bootstrap");
                }
                Map<String, NacosV2MappingCodec.OwnerState> owners =
                        NacosV2MappingCodec.decode(entry.content(), namespace, shard);
                for (NacosV2MappingCodec.OwnerState owner : owners.values()) {
                    if (owner.services().contains(key.serviceKey())) {
                        applications.add(owner.owner().application());
                        if (applications.size() > MAX_APPLICATIONS) {
                            throw new IllegalStateException("Nacos mapping fanout capacity exceeded");
                        }
                    }
                }
            }
            return new ServiceMappingSnapshot(key, applications, localRevision.incrementAndGet());
        });
    }

    private void requireNamespace(ApplicationKey application) {
        Objects.requireNonNull(application, "application");
        if (!namespace.equals(application.namespace())) {
            throw new IllegalArgumentException("Nacos mapping namespace does not match application");
        }
    }
}
