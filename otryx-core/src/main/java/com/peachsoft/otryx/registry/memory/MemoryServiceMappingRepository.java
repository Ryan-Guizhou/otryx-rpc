package com.peachsoft.otryx.registry.memory;

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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * 无网络的原子应用映射实现，供后续 Memory Registry 与控制面契约测试复用。
 *
 * <p>锁保护 owner 代数、完整导出集合及快照版本；读取返回不可变结果。
 * 生产远端适配不得将此对象当作跨进程一致性的替代品。
 *
 * @Author OpenAI
 * @Version 1.0.0-SNAPSHOT
 * @CreateTime 2026/10/15 18:30
 */
public final class MemoryServiceMappingRepository implements ServiceMappingRepository {

    private static final int MAX_SERVICES_PER_OWNER = 512;
    private static final int MAX_OWNERS = 4096;
    private static final int MAX_INDEXED_KEYS = 16384;

    private final Map<OwnerId, Ownership> owners = new HashMap<>();
    private long generation;
    private long revision;

    /** 创建隔离的内存映射仓库，不与其他进程共享所有权。 */
    public MemoryServiceMappingRepository() {
    }

    /** 原子发放 fencing generation；重复 claim 废止同实例的旧 token。 */
    @Override
    public synchronized CompletionStage<ServiceMappingOwner> claim(
            ApplicationKey application, String instanceId) {
        Objects.requireNonNull(application, "application");
        Objects.requireNonNull(instanceId, "instanceId");
        OwnerId id = new OwnerId(application, instanceId.trim());
        if (!owners.containsKey(id) && owners.size() >= MAX_OWNERS) {
            return CompletableFuture.failedFuture(new IllegalStateException("Mapping owner capacity exceeded"));
        }
        ServiceMappingOwner next = new ServiceMappingOwner(application, instanceId, ++generation);
        Ownership previous = owners.put(id, new Ownership(next, Set.of()));
        if (previous != null && !previous.services().isEmpty()) {
            revision++;
        }
        return CompletableFuture.completedFuture(next);
    }

    /** 原子替换一个 owner 的完整服务集合并防止旧代数误写。 */
    @Override
    public synchronized CompletionStage<Void> publish(ServiceMappingOwner owner, Set<ServiceKey> services) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(services, "services");
        if (services.size() > MAX_SERVICES_PER_OWNER) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Too many exported services"));
        }
        Set<ServiceKey> safe = Set.copyOf(services);
        OwnerId id = new OwnerId(owner.application(), owner.instanceId());
        Ownership current = owners.get(id);
        if (current == null || !current.owner().equals(owner)) {
            return CompletableFuture.failedFuture(new IllegalStateException("Stale mapping owner"));
        }
        if (!current.services().equals(safe)) {
            Set<ServiceMappingKey> keys = new HashSet<>();
            owners.forEach((otherId, value) -> {
                if (!otherId.equals(id)) {
                    for (ServiceKey key : value.services()) {
                        keys.add(new ServiceMappingKey(otherId.application().namespace(), key));
                    }
                }
            });
            for (ServiceKey key : safe) {
                keys.add(new ServiceMappingKey(owner.application().namespace(), key));
            }
            if (keys.size() > MAX_INDEXED_KEYS) {
                return CompletableFuture.failedFuture(new IllegalStateException("Mapping key capacity exceeded"));
            }
            owners.put(id, new Ownership(owner, safe));
            revision++;
        }
        return CompletableFuture.completedFuture(null);
    }

    /** 只撤销当前代数，旧实例重试不会删除新发布。 */
    @Override
    public synchronized CompletionStage<Void> revoke(ServiceMappingOwner owner) {
        Objects.requireNonNull(owner, "owner");
        OwnerId id = new OwnerId(owner.application(), owner.instanceId());
        Ownership current = owners.get(id);
        if (current != null && current.owner().equals(owner)) {
            owners.remove(id);
            if (!current.services().isEmpty()) {
                revision++;
            }
        }
        return CompletableFuture.completedFuture(null);
    }

    /** 返回当前已发布的真实应用集合，永不暴露可变内表。 */
    @Override
    public synchronized CompletionStage<ServiceMappingSnapshot> lookup(ServiceMappingKey key) {
        Objects.requireNonNull(key, "key");
        Set<ApplicationKey> result = new HashSet<>();
        for (Map.Entry<OwnerId, Ownership> entry : owners.entrySet()) {
            if (entry.getKey().application().namespace().equals(key.namespace())
                    && entry.getValue().services().contains(key.serviceKey())) {
                result.add(entry.getKey().application());
            }
        }
        if (result.size() > ServiceMappingSnapshot.MAX_APPLICATIONS) {
            return CompletableFuture.failedFuture(new IllegalStateException("Mapping fanout capacity exceeded"));
        }
        return CompletableFuture.completedFuture(new ServiceMappingSnapshot(key, result, revision));
    }

    private record OwnerId(ApplicationKey application, String instanceId) {
    }

    private record Ownership(ServiceMappingOwner owner, Set<ServiceKey> services) {
    }
}
