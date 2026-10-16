package com.peachsoft.otryx.registry.consul;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.peachsoft.otryx.api.ApplicationInstance;
import com.peachsoft.otryx.api.ApplicationKey;
import com.peachsoft.otryx.api.ServiceKey;
import com.peachsoft.otryx.registry.ServiceMappingKey;
import com.peachsoft.otryx.registry.ServiceMappingOwner;
import com.peachsoft.otryx.registry.ServiceMappingRepository;
import com.peachsoft.otryx.registry.ServiceMappingCandidateSource;
import com.peachsoft.otryx.registry.ServiceMappingOwnerSnapshot;
import com.peachsoft.otryx.registry.ServiceMappingSnapshot;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Consul KV 应用实例粒度的 ServiceMapping CAS 所有权仓库。
 *
 * <p>每个实例只有一条持久化 Owner KV：claim 单调递增 Generation，publish
 * 使用 ModifyIndex CAS 完整替换其服务集合，revoke 清空集合但保留代数墓碑。
 * 对旧代、跨进程发布或延迟撤销均失败关闭。Lookup 额外验证 Consul Passing
 * 的真实 Provider 成员，绝不将 Consumer-only 作为可路由服务。
 *
 * <p>KV 目录扫描、原生 Health 请求均在有界控制面线程中执行，不能从 EventLoop
 * 同步调用。过期墓碑不自动删除，避免 ABA；跨部署清理须另行保证 fencing。
 *
 * @Author OpenAI
 * @Version 1.0.0-SNAPSHOT
 * @CreateTime 2026/10/16 15:00
 */
public final class ConsulServiceMappingRepository implements ServiceMappingRepository, ServiceMappingCandidateSource {

    private static final String FIELD_FORMAT = "format";
    private static final String FIELD_NAMESPACE = "namespace";
    private static final String FIELD_APPLICATION = "application";
    private static final String FIELD_INSTANCE = "instance";
    private static final String FIELD_GENERATION = "generation";
    private static final String FIELD_TOKEN = "token";
    private static final String FIELD_SERVICES = "services";
    private static final String FIELD_NAME = "name";
    private static final String FIELD_VERSION = "version";
    private static final String FIELD_GROUP = "group";
    private static final String FORMAT = "otryx-owner-v2";
    private static final String PATH_HEALTH = "/v1/health/service/";
    private static final String QUERY_PASSING = "?passing=true";
    private static final int MAX_OWNER_BYTES = 256 * 1024;
    private static final int MAX_EXPORTS = 512;
    private static final int MAX_LOCAL_OWNERS = 256;
    private static final int MAX_CAS_ATTEMPTS = 12;
    private static final int MAX_INSTANCE_BYTES = 1024;
    private static final Comparator<ServiceKey> KEY_ORDER = Comparator.comparing(ServiceKey::serviceName)
            .thenComparing(ServiceKey::version).thenComparing(ServiceKey::group);

    private final ConsulV2Client client;
    private final ConsulV2Executor executor;
    private final ConsulV2Identity identity;
    private final ObjectMapper mapper = new ObjectMapper();
    private final ConcurrentMap<String, LocalClaim> localClaims = new ConcurrentHashMap<>();
    private final AtomicLong localRevision = new AtomicLong();

    ConsulServiceMappingRepository(
            ConsulV2Client client, ConsulV2Executor executor, ConsulV2Identity identity) {
        this.client = Objects.requireNonNull(client, "client");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.identity = Objects.requireNonNull(identity, "identity");
    }

    /** 在 KV 原子获取严格递增的 Generation，并阻断其他进程复用同代的 Fencing。 */
    @Override
    public CompletionStage<ServiceMappingOwner> claim(ApplicationKey application, String instanceId) {
        identity.requireNamespace(application);
        String key = identity.ownerKey(application, instanceId);
        return executor.submit("mapping-claim", () -> {
            if (!localClaims.containsKey(key) && localClaims.size() >= MAX_LOCAL_OWNERS) {
                throw new IllegalStateException("Consul local owner capacity exceeded");
            }
            for (int attempt = 0; attempt < MAX_CAS_ATTEMPTS; attempt++) {
                Optional<ConsulV2Client.KvEntry> current = client.get(key);
                long generation = current.isEmpty() ? 1L : checkedNextGeneration(
                        decode(current.orElseThrow().value(), application, instanceId).owner().generation());
                long modifyIndex = current.map(ConsulV2Client.KvEntry::modifyIndex).orElse(0L);
                ServiceMappingOwner owner = new ServiceMappingOwner(application, instanceId, generation);
                String token = UUID.randomUUID().toString();
                if (client.cas(key, modifyIndex, encode(owner, token, Set.of()))) {
                    localClaims.put(key, new LocalClaim(owner, token));
                    return owner;
                }
            }
            throw new IllegalStateException("Consul mapping claim CAS exhausted");
        });
    }

    /** 发布当前 Claim 的完整集合；旧 Generation 或失效 Token 禁止修改。 */
    @Override
    public CompletionStage<Void> publish(ServiceMappingOwner owner, Set<ServiceKey> services) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(services, "services");
        identity.requireNamespace(owner.application());
        Set<ServiceKey> exports = Set.copyOf(services);
        if (exports.size() > MAX_EXPORTS) {
            throw new IllegalArgumentException("Consul mapping export count exceeded");
        }
        String key = identity.ownerKey(owner.application(), owner.instanceId());
        return executor.submit("mapping-publish", () -> {
            LocalClaim local = requireClaim(key, owner);
            for (int attempt = 0; attempt < MAX_CAS_ATTEMPTS; attempt++) {
                LocalClaim live = requireClaim(key, owner);
                if (!live.equals(local)) {
                    throw new IllegalStateException("Consul mapping local ownership changed");
                }
                ConsulV2Client.KvEntry current = client.get(key).orElseThrow(
                        () -> new IllegalStateException("Consul mapping Owner is missing"));
                OwnerState stored = decode(current.value(), owner.application(), owner.instanceId());
                requireCurrent(stored, live);
                if (stored.services().equals(exports)) {
                    return null;
                }
                if (client.cas(key, current.modifyIndex(), encode(owner, live.token(), exports))) {
                    return null;
                }
            }
            throw new IllegalStateException("Consul mapping publish CAS exhausted");
        });
    }

    /** 清空当前代的服务集合，保留墓碑和 Generation 以避免迟到写入。 */
    @Override
    public CompletionStage<Void> revoke(ServiceMappingOwner owner) {
        Objects.requireNonNull(owner, "owner");
        identity.requireNamespace(owner.application());
        String key = identity.ownerKey(owner.application(), owner.instanceId());
        return executor.submit("mapping-revoke", () -> {
            LocalClaim local = localClaims.get(key);
            if (local == null || !local.owner().equals(owner)) {
                return null;
            }
            for (int attempt = 0; attempt < MAX_CAS_ATTEMPTS; attempt++) {
                Optional<ConsulV2Client.KvEntry> entry = client.get(key);
                if (entry.isEmpty()) {
                    localClaims.remove(key, local);
                    return null;
                }
                OwnerState stored = decode(entry.orElseThrow().value(),
                        owner.application(), owner.instanceId());
                if (!stored.owner().equals(owner) || !stored.token().equals(local.token())) {
                    localClaims.remove(key, local);
                    return null;
                }
                if (stored.services().isEmpty()
                        || client.cas(key, entry.orElseThrow().modifyIndex(),
                                encode(owner, local.token(), Set.of()))) {
                    localClaims.remove(key, local);
                    return null;
                }
            }
            throw new IllegalStateException("Consul mapping revoke CAS exhausted");
        });
    }

    /**
     * 扫描有界 Owner KV 并验证活跃 Provider；跨 Namespace、畸形或 ACL 失败不可当作健康空值。
     */
    @Override
    public CompletionStage<ServiceMappingSnapshot> lookup(ServiceMappingKey key) {
        Objects.requireNonNull(key, "key");
        identity.requireNamespace(key.namespace());
        return executor.submit("mapping-lookup", () -> {
            ServiceMappingOwnerSnapshot candidates = scanOwnerCandidates(key);
            Set<ApplicationKey> healthy = new HashSet<>();
            for (Map.Entry<ApplicationKey, Set<String>> candidate : candidates.ownerInstances().entrySet()) {
                for (ApplicationInstance instance : liveMembers(candidate.getKey())) {
                    if (instance.routableProvider()
                            && candidate.getValue().contains(instance.instanceId())) {
                        healthy.add(candidate.getKey());
                        break;
                    }
                }
            }
            return new ServiceMappingSnapshot(key, healthy, candidates.revision());
        });
    }

    /**
     * 读取未按当前 Consul 成员过滤的 Owner 快照，用于 Eureka 等独立成员注册中心。
     *
     * @param key 命名空间和完整 ServiceKey
     * @return 必须与权威注册中心实际 Provider instanceId 交叉验证的候选快照
     */
    @Override
    public CompletionStage<ServiceMappingOwnerSnapshot> lookupCandidateOwners(ServiceMappingKey key) {
        Objects.requireNonNull(key, "key");
        identity.requireNamespace(key.namespace());
        return executor.submit("mapping-candidates", () -> scanOwnerCandidates(key));
    }

    private ServiceMappingOwnerSnapshot scanOwnerCandidates(ServiceMappingKey key) throws Exception {
        List<ConsulV2Client.KvEntry> entries = client.list(identity.ownersPrefix());
        Map<ApplicationKey, Set<String>> claimed = new HashMap<>();
        long maximumIndex = 0;
        for (ConsulV2Client.KvEntry entry : entries) {
            OwnerState state = decode(entry.value(), null, null);
            String expectedKey = identity.ownerKey(state.owner().application(), state.owner().instanceId());
            if (!entry.key().equals(expectedKey)) {
                throw new IllegalArgumentException("Consul Owner KV identity mismatch");
            }
            maximumIndex = Math.max(maximumIndex, entry.modifyIndex());
            if (state.services().contains(key.serviceKey())) {
                claimed.computeIfAbsent(state.owner().application(), ignored -> new HashSet<>())
                        .add(state.owner().instanceId());
            }
        }
        if (claimed.size() > ServiceMappingSnapshot.MAX_APPLICATIONS) {
            throw new IllegalStateException("Consul mapping candidate fanout exceeded");
        }
        long watermark = maximumIndex;
        long revision = localRevision.updateAndGet(previous -> Math.max(previous + 1, watermark));
        return new ServiceMappingOwnerSnapshot(key, claimed, revision);
    }

    private List<ApplicationInstance> liveMembers(ApplicationKey application) throws Exception {
        ConsulV2Client.JsonResult result = client.json("GET",
                PATH_HEALTH + identity.serviceName(application) + QUERY_PASSING, null);
        if (result.missing()) {
            return List.of();
        }
        if (result.body() == null || !result.body().isArray()
                || result.body().size() > ConsulV2Client.MAX_KV_ITEMS) {
            throw new IllegalStateException("Invalid Consul application health directory");
        }
        List<ApplicationInstance> members = new ArrayList<>();
        for (JsonNode record : result.body()) {
            ApplicationInstance member = identity.fromHealth(application, record);
            if (member != null) {
                members.add(member);
            }
        }
        return members;
    }

    private LocalClaim requireClaim(String key, ServiceMappingOwner expected) {
        LocalClaim claim = localClaims.get(key);
        if (claim == null || !claim.owner().equals(expected)) {
            throw new IllegalStateException("Consul mapping Fencing token is not owned locally");
        }
        return claim;
    }

    private static void requireCurrent(OwnerState stored, LocalClaim local) {
        if (!stored.owner().equals(local.owner()) || !stored.token().equals(local.token())) {
            throw new IllegalStateException("Consul mapping ownership was superseded");
        }
    }

    private static long checkedNextGeneration(long generation) {
        if (generation >= Long.MAX_VALUE) {
            throw new IllegalStateException("Consul mapping generation exhausted");
        }
        return generation + 1;
    }

    private byte[] encode(ServiceMappingOwner owner, String token, Set<ServiceKey> services) throws Exception {
        List<Map<String, String>> records = services.stream().sorted(KEY_ORDER).map(service -> Map.of(
                FIELD_NAME, service.serviceName(),
                FIELD_VERSION, service.version(),
                FIELD_GROUP, service.group())).toList();
        Map<String, Object> body = Map.of(
                FIELD_FORMAT, FORMAT,
                FIELD_NAMESPACE, owner.application().namespace(),
                FIELD_APPLICATION, owner.application().applicationName(),
                FIELD_INSTANCE, owner.instanceId(),
                FIELD_GENERATION, owner.generation(),
                FIELD_TOKEN, token,
                FIELD_SERVICES, records);
        byte[] data = mapper.writeValueAsBytes(body);
        if (data.length == 0 || data.length > MAX_OWNER_BYTES) {
            throw new IllegalArgumentException("Consul Owner record exceeds byte budget");
        }
        return data;
    }

    private OwnerState decode(byte[] payload, ApplicationKey expectedApplication, String expectedInstance)
            throws Exception {
        if (payload.length == 0 || payload.length > MAX_OWNER_BYTES) {
            throw new IllegalArgumentException("Consul Owner record size invalid");
        }
        JsonNode root = mapper.readTree(payload);
        if (!root.isObject() || !FORMAT.equals(root.path(FIELD_FORMAT).asText())) {
            throw new IllegalArgumentException("Consul Owner record format invalid");
        }
        ApplicationKey application = new ApplicationKey(root.path(FIELD_NAMESPACE).asText(),
                root.path(FIELD_APPLICATION).asText());
        identity.requireNamespace(application);
        String instanceId = root.path(FIELD_INSTANCE).asText();
        long generation = root.path(FIELD_GENERATION).asLong(0L);
        String token = root.path(FIELD_TOKEN).asText();
        if (token.isBlank() || token.length() > MAX_INSTANCE_BYTES
                || expectedApplication != null && !expectedApplication.equals(application)
                || expectedInstance != null && !expectedInstance.equals(instanceId)) {
            throw new IllegalArgumentException("Consul Owner identity mismatch");
        }
        ServiceMappingOwner owner = new ServiceMappingOwner(application, instanceId, generation);
        JsonNode list = root.path(FIELD_SERVICES);
        if (!list.isArray() || list.size() > MAX_EXPORTS) {
            throw new IllegalArgumentException("Consul Owner exports exceed limit");
        }
        List<ServiceKey> services = new ArrayList<>();
        for (JsonNode value : list) {
            if (!value.isObject()) {
                throw new IllegalArgumentException("Invalid Consul Owner export");
            }
            services.add(new ServiceKey(value.path(FIELD_NAME).asText(),
                    value.path(FIELD_VERSION).asText(), value.path(FIELD_GROUP).asText()));
        }
        Set<ServiceKey> unique = Set.copyOf(services);
        if (unique.size() != services.size()
                || !services.equals(services.stream().sorted(KEY_ORDER).toList())) {
            throw new IllegalArgumentException("Duplicate or unsorted Consul Owner exports");
        }
        return new OwnerState(owner, token, unique);
    }

    private record LocalClaim(ServiceMappingOwner owner, String token) {
    }

    private record OwnerState(ServiceMappingOwner owner, String token, Set<ServiceKey> services) {
    }
}
