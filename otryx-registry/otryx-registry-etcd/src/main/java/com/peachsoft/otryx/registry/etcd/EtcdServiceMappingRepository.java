package com.peachsoft.otryx.registry.etcd;

import com.peachsoft.otryx.api.ApplicationInstance;
import com.peachsoft.otryx.api.ApplicationKey;
import com.peachsoft.otryx.api.RpcEndpoint;
import com.peachsoft.otryx.api.ServiceKey;
import com.peachsoft.otryx.registry.ServiceMappingKey;
import com.peachsoft.otryx.registry.ServiceMappingOwner;
import com.peachsoft.otryx.registry.ServiceMappingRepository;
import com.peachsoft.otryx.registry.ServiceMappingCandidateSource;
import com.peachsoft.otryx.registry.ServiceMappingOwnerSnapshot;
import com.peachsoft.otryx.registry.ServiceMappingSnapshot;
import io.etcd.jetcd.ByteSequence;
import io.etcd.jetcd.Client;
import io.etcd.jetcd.KeyValue;
import io.etcd.jetcd.kv.GetResponse;
import io.etcd.jetcd.op.Cmp;
import io.etcd.jetcd.op.CmpTarget;
import io.etcd.jetcd.op.Op;
import io.etcd.jetcd.options.GetOption;
import io.etcd.jetcd.options.PutOption;
import java.nio.charset.StandardCharsets;
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

/**
 * Etcd 分布式服务映射，Owner 单键全量发布，使用 Revision CAS 维护单调 fencing。
 *
 * <p>claim 永不删除 Owner generation；revoke 写空墓碑防 ABA。查询将发布集合
 * 与同一 Etcd Revision 的有效 Provider Member 交叉验证，进程崩溃且 Lease
 * 已过期时不得继续把遗留 Mapping 返回为可路由候选应用。接口数量不会影响
 * 应用成员数。Owner 查询只允许有界扫描，面向大型注册集群的索引优化须独立评测。
 *
 * @Author OpenAI
 * @Version 1.0.0-SNAPSHOT
 * @CreateTime 2026/10/16 10:33
 */
public final class EtcdServiceMappingRepository implements ServiceMappingRepository, ServiceMappingCandidateSource {

    private static final int MAX_CAS_ATTEMPTS = 12;
    private static final int MAX_OWNER_SCAN = 512;
    private static final int MAX_MEMBER_SCAN = 256;
    private static final int MAX_TOTAL_OWNER_BYTES = 8 * 1024 * 1024;
    private static final int MAX_LOCAL_OWNERS = 512;

    private final Client client;
    private final EtcdV2Paths paths;
    private final ConcurrentMap<String, Claim> localClaims = new ConcurrentHashMap<>();

    EtcdServiceMappingRepository(Client client, EtcdV2Paths paths) {
        this.client = Objects.requireNonNull(client, "client");
        this.paths = Objects.requireNonNull(paths, "paths");
    }

    /**
     * 通过 Etcd CAS 原子颁发新 generation，清除前一代服务集合。
     *
     * <p>并发 claim 只有一个事务在同一 Revision 成功，失败者重读后递增。
     */
    @Override
    public CompletionStage<ServiceMappingOwner> claim(ApplicationKey application, String instanceId) {
        Objects.requireNonNull(application, "application");
        ServiceMappingOwner validated = new ServiceMappingOwner(application, instanceId, 1);
        paths.requireNamespace(application);
        if (localClaims.size() >= MAX_LOCAL_OWNERS
                && !localClaims.containsKey(paths.ownerKey(application, validated.instanceId()))) {
            return CompletableFuture.failedFuture(new IllegalStateException("Etcd mapping local owner budget reached"));
        }
        return claimAttempt(application, validated.instanceId(), 0);
    }

    private CompletionStage<ServiceMappingOwner> claimAttempt(
            ApplicationKey application, String instanceId, int attempts) {
        if (attempts >= MAX_CAS_ATTEMPTS) {
            return CompletableFuture.failedFuture(new IllegalStateException("Etcd mapping claim CAS exhausted"));
        }
        String path = paths.ownerKey(application, instanceId);
        ByteSequence key = utf8(path);
        return client.getKVClient().get(key).thenCompose(response -> {
            boolean absent = response.getKvs().isEmpty();
            long currentRevision = absent ? 0 : response.getKvs().getFirst().getModRevision();
            long previousGeneration = 0;
            if (!absent) {
                EtcdV2Codec.OwnerRecord stored =
                        EtcdV2Codec.decodeOwner(response.getKvs().getFirst().getValue().getBytes());
                requireOwnerKey(path, stored);
                previousGeneration = stored.owner().generation();
            }
            if (previousGeneration == Long.MAX_VALUE) {
                return CompletableFuture.failedFuture(
                        new IllegalStateException("Etcd mapping fencing generation exhausted"));
            }
            ServiceMappingOwner owner = new ServiceMappingOwner(application, instanceId, previousGeneration + 1);
            String token = UUID.randomUUID().toString();
            byte[] data = EtcdV2Codec.encodeOwner(owner, token, Set.of());
            Cmp compare = absent
                    ? new Cmp(key, Cmp.Op.EQUAL, CmpTarget.version(0))
                    : new Cmp(key, Cmp.Op.EQUAL, CmpTarget.modRevision(currentRevision));
            return client.getKVClient().txn()
                    .If(compare)
                    .Then(Op.put(key, ByteSequence.from(data), PutOption.DEFAULT))
                    .commit()
                    .thenCompose(result -> {
                        if (!result.isSucceeded()) {
                            return claimAttempt(application, instanceId, attempts + 1);
                        }
                        localClaims.compute(path, (ignored, current) ->
                                current == null || current.owner().generation() < owner.generation()
                                        ? new Claim(owner, token) : current);
                        return CompletableFuture.completedFuture(owner);
                    });
        });
    }

    /** 当前代数 CAS 原子替换该实例所有导出服务，不生成逐接口注册记录。 */
    @Override
    public CompletionStage<Void> publish(ServiceMappingOwner owner, Set<ServiceKey> services) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(services, "services");
        paths.requireNamespace(owner.application());
        if (services.size() > EtcdV2Codec.MAX_SERVICES) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Too many Etcd exported services"));
        }
        return changeOwner(owner, Set.copyOf(services), false, 0);
    }

    /**
     * 仅当前代数可清除自身发布，保留 generation 与 tombstone 阻止旧请求复活。
     *
     * <p>旧代 revoke 不得删除其他实例或新代 Owner。
     */
    @Override
    public CompletionStage<Void> revoke(ServiceMappingOwner owner) {
        Objects.requireNonNull(owner, "owner");
        paths.requireNamespace(owner.application());
        String path = paths.ownerKey(owner.application(), owner.instanceId());
        Claim local = localClaims.get(path);
        if (local == null || !local.owner().equals(owner)) {
            return CompletableFuture.completedFuture(null);
        }
        return changeOwner(owner, Set.of(), true, 0);
    }

    private CompletionStage<Void> changeOwner(
            ServiceMappingOwner owner, Set<ServiceKey> services, boolean revoking, int attempts) {
        if (attempts >= MAX_CAS_ATTEMPTS) {
            return CompletableFuture.failedFuture(new IllegalStateException("Etcd mapping update CAS exhausted"));
        }
        String path = paths.ownerKey(owner.application(), owner.instanceId());
        Claim claim = localClaims.get(path);
        if (claim == null || !claim.owner().equals(owner)) {
            return revoking ? CompletableFuture.completedFuture(null)
                    : CompletableFuture.failedFuture(new IllegalStateException("Stale Etcd mapping owner"));
        }
        ByteSequence key = utf8(path);
        return client.getKVClient().get(key).thenCompose(response -> {
            if (response.getKvs().isEmpty()) {
                return CompletableFuture.failedFuture(new IllegalStateException("Etcd mapping owner is missing"));
            }
            KeyValue kv = response.getKvs().getFirst();
            EtcdV2Codec.OwnerRecord stored = EtcdV2Codec.decodeOwner(kv.getValue().getBytes());
            requireOwnerKey(path, stored);
            if (!stored.owner().equals(owner) || !stored.token().equals(claim.token())) {
                if (revoking) {
                    localClaims.remove(path, claim);
                    return CompletableFuture.completedFuture(null);
                }
                return CompletableFuture.failedFuture(new IllegalStateException("Stale Etcd mapping generation"));
            }
            if (stored.services().equals(services)) {
                if (revoking) {
                    localClaims.remove(path, claim);
                }
                return CompletableFuture.completedFuture(null);
            }
            byte[] payload = EtcdV2Codec.encodeOwner(owner, claim.token(), services);
            return client.getKVClient().txn()
                    .If(new Cmp(key, Cmp.Op.EQUAL, CmpTarget.modRevision(kv.getModRevision())))
                    .Then(Op.put(key, ByteSequence.from(payload), PutOption.DEFAULT))
                    .commit()
                    .thenCompose(result -> {
                        if (!result.isSucceeded()) {
                            return changeOwner(owner, services, revoking, attempts + 1);
                        }
                        if (revoking) {
                            localClaims.remove(path, claim);
                        }
                        return CompletableFuture.completedFuture(null);
                    });
        });
    }

    /**
     * 有界读取所有 Owner，在一致修订版检查 Provider Member 的 Lease 存活性。
     *
     * <p>不返回纯 Consumer，不信任单独的过期 Mapping；失败时传播异常而非假空结果。
     */
    @Override
    public CompletionStage<ServiceMappingSnapshot> lookup(ServiceMappingKey key) {
        Objects.requireNonNull(key, "key");
        paths.requireNamespace(key.namespace());
        GetOption options = GetOption.builder().isPrefix(true).withLimit(MAX_OWNER_SCAN + 1).build();
        return client.getKVClient().get(utf8(paths.ownerPrefix()), options)
                .thenCompose(response -> lookupAtRevision(key, response, true));
    }

    /**
     * 仅查询持久化 CAS Owner 的候选应用，成员存活由调用方自己的注册中心验证。
     *
     * @param key 命名空间与完整 ServiceKey
     * @return 不可直接路由的候选快照
     */
    @Override
    public CompletionStage<ServiceMappingOwnerSnapshot> lookupCandidateOwners(ServiceMappingKey key) {
        Objects.requireNonNull(key, "key");
        paths.requireNamespace(key.namespace());
        GetOption options = GetOption.builder().isPrefix(true).withLimit(MAX_OWNER_SCAN + 1).build();
        return client.getKVClient().get(utf8(paths.ownerPrefix()), options)
                .thenApply(response -> decodeCandidateOwners(key, response));
    }

    private CompletionStage<ServiceMappingSnapshot> lookupAtRevision(
            ServiceMappingKey key, GetResponse response, boolean checkMembers) {
        ServiceMappingOwnerSnapshot candidates = decodeCandidateOwners(key, response);
        if (!checkMembers) {
            return CompletableFuture.completedFuture(
                    new ServiceMappingSnapshot(key, candidates.ownerInstances().keySet(), candidates.revision()));
        }
        Set<ApplicationKey> active = new HashSet<>();
        CompletionStage<Void> checks = CompletableFuture.completedFuture(null);
        for (Map.Entry<ApplicationKey, Set<String>> candidate : candidates.ownerInstances().entrySet()) {
            checks = checks.thenCompose(ignored ->
                    requireLiveProvider(candidate.getKey(), candidate.getValue(), candidates.revision())
                            .thenAccept(live -> {
                                if (live) {
                                    active.add(candidate.getKey());
                                }
                            }));
        }
        return checks.thenApply(ignored -> new ServiceMappingSnapshot(key, active, candidates.revision()));
    }

    private ServiceMappingOwnerSnapshot decodeCandidateOwners(ServiceMappingKey key, GetResponse owners) {
        if (owners.getCount() > MAX_OWNER_SCAN || owners.getKvs().size() > MAX_OWNER_SCAN) {
            throw new IllegalStateException("Etcd mapping owner scan budget exceeded");
        }
        Map<ApplicationKey, Set<String>> providers = new HashMap<>();
        int bytes = 0;
        for (KeyValue kv : owners.getKvs()) {
            byte[] payload = kv.getValue().getBytes();
            bytes += payload.length;
            if (bytes > MAX_TOTAL_OWNER_BYTES) {
                throw new IllegalStateException("Etcd mapping byte scan budget exceeded");
            }
            EtcdV2Codec.OwnerRecord record = EtcdV2Codec.decodeOwner(payload);
            String expectedKey = paths.ownerKey(
                    record.owner().application(), record.owner().instanceId());
            if (!utf8(expectedKey).equals(kv.getKey())) {
                throw new IllegalArgumentException("Etcd mapping owner KV identity mismatch");
            }
            if (record.services().contains(key.serviceKey())) {
                providers.computeIfAbsent(record.owner().application(), ignored -> new HashSet<>())
                        .add(record.owner().instanceId());
                if (providers.size() > ServiceMappingSnapshot.MAX_APPLICATIONS) {
                    throw new IllegalStateException("Etcd mapping fanout budget exceeded");
                }
            }
        }
        return new ServiceMappingOwnerSnapshot(key, providers, owners.getHeader().getRevision());
    }

    private CompletionStage<Boolean> requireLiveProvider(
            ApplicationKey application, Set<String> ownerInstances, long revision) {
        GetOption options = GetOption.builder()
                .isPrefix(true)
                .withLimit(MAX_MEMBER_SCAN + 1)
                .withRevision(revision)
                .build();
        return client.getKVClient().get(utf8(paths.memberPrefix(application)), options).thenApply(response -> {
            if (response.getCount() > MAX_MEMBER_SCAN) {
                throw new IllegalStateException("Etcd member scan budget exceeded");
            }
            for (KeyValue kv : response.getKvs()) {
                EtcdV2Codec.MemberRecord record = EtcdV2Codec.decodeMember(kv.getValue().getBytes());
                ApplicationInstance member = record.instance();
                if (!application.equals(member.application())
                        || !utf8(paths.memberKey(application, member.instanceId())).equals(kv.getKey())) {
                    throw new IllegalArgumentException("Etcd member KV identity mismatch");
                }
                if (ownerInstances.contains(member.instanceId()) && member.routableProvider()
                        && member.rpcEndpoint().port() > 0
                        && !member.rpcEndpoint().host().equals(RpcEndpoint.UNSPECIFIED_IPV4_HOST)
                        && !member.rpcEndpoint().host().equals(RpcEndpoint.UNSPECIFIED_IPV6_HOST)
                        && !member.rpcEndpoint().host().equals(RpcEndpoint.UNSPECIFIED_IPV6_BRACKETED_HOST)) {
                    return true;
                }
            }
            return false;
        });
    }

    private void requireOwnerKey(String key, EtcdV2Codec.OwnerRecord owner) {
        if (!key.equals(paths.ownerKey(owner.owner().application(), owner.owner().instanceId()))) {
            throw new IllegalArgumentException("Etcd mapping owner key mismatch");
        }
    }

    private static ByteSequence utf8(String value) {
        return ByteSequence.from(value, StandardCharsets.UTF_8);
    }

    private record Claim(ServiceMappingOwner owner, String token) {
    }
}
