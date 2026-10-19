package com.peachsoft.otryx.registry;

import com.peachsoft.otryx.api.ApplicationInstance;
import com.peachsoft.otryx.api.RpcEndpoint;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 已通过 Mapping、Application Directory、Manifest 及 Schema 逐级验证的 Provider 列表。
 *
 * <p>此对象不包含纯 Consumer、无效 Endpoint 或未声明目标业务服务的实例。
 * 正常空集合表示本轮完整控制面同步成功但暂时没有兼容 Provider。
 *
 * @Author OpenAI
 * @Version 1.0.0-SNAPSHOT
 * @CreateTime 2026/10/15 18:30
 */
public final class VerifiedProviderSnapshot {

    private static final int MAX_PROVIDERS = 1024;

    private final ServiceMappingKey key;
    private final String schemaFingerprint;
    private final List<ApplicationInstance> providers;
    private final long mappingRevision;
    private final Object authority;
    private final long verifiedEpoch;
    private final Object routeProof;

    /**
     * 创建不可变值对象；公开构造不会取得 Resolver 的本地路由授权凭据。
     *
     * @param key 服务映射键
     * @param schemaFingerprint 调用方 Schema 指纹
     * @param providers 需要校验的 Provider 值列表
     * @param mappingRevision Mapping 修订号
     */
    public VerifiedProviderSnapshot(ServiceMappingKey key, String schemaFingerprint,
                                    List<ApplicationInstance> providers, long mappingRevision) {
        this(key, schemaFingerprint, providers, mappingRevision, null, 0, null);
    }

    /** 仅 Resolver 在完整验证并提交当前 epoch 时附加其私有固定大小凭据。 */
    VerifiedProviderSnapshot(ServiceMappingKey key, String schemaFingerprint,
                             List<ApplicationInstance> providers, long mappingRevision,
                             Object authority, long verifiedEpoch, Object routeProof) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(schemaFingerprint, "schemaFingerprint");
        if (!schemaFingerprint.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Invalid expected schema fingerprint");
        }
        providers = List.copyOf(Objects.requireNonNull(providers, "providers"));
        if (providers.size() > MAX_PROVIDERS || mappingRevision < 0) {
            throw new IllegalArgumentException("Invalid provider snapshot capacity or revision");
        }
        Set<String> identities = new HashSet<>();
        for (ApplicationInstance instance : providers) {
            if (!instance.routableProvider()
                    || !key.namespace().equals(instance.application().namespace())
                    || invalidEndpoint(instance.rpcEndpoint())) {
                throw new IllegalArgumentException("Provider snapshot contains invalid route");
            }
            String identity = instance.application().applicationName() + ":" + instance.instanceId();
            if (!identities.add(identity)) {
                throw new IllegalArgumentException("Duplicate Provider identity");
            }
        }
        this.key = key;
        this.schemaFingerprint = schemaFingerprint;
        this.providers = providers;
        this.mappingRevision = mappingRevision;
        this.authority = authority;
        this.verifiedEpoch = verifiedEpoch;
        this.routeProof = routeProof;
    }

    /**
     * 返回不可变业务键。
     *
     * @return 服务映射键
     */
    public ServiceMappingKey key() {
        return key;
    }

    /**
     * 返回已验证的期望 Schema。
     *
     * @return Schema 指纹
     */
    public String schemaFingerprint() {
        return schemaFingerprint;
    }

    /**
     * 返回不可变 Provider 值列表；使用前仍应通过 Resolver guard。
     *
     * @return Provider 快照
     */
    public List<ApplicationInstance> providers() {
        return providers;
    }

    /**
     * 返回实际 Mapping 修订号，不能用它代替安全 epoch。
     *
     * @return 非负修订号
     */
    public long mappingRevision() {
        return mappingRevision;
    }

    boolean hasAuthority(Object expectedAuthority, Object expectedRouteProof) {
        return authority != null && authority == expectedAuthority && routeProof == expectedRouteProof;
    }

    long verifiedEpoch() {
        return verifiedEpoch;
    }

    /** 值等价只覆盖四个业务字段，不授予或比较本地信任。 */
    @Override
    public boolean equals(Object other) {
        return this == other || other instanceof VerifiedProviderSnapshot snapshot
                && mappingRevision == snapshot.mappingRevision
                && key.equals(snapshot.key)
                && schemaFingerprint.equals(snapshot.schemaFingerprint)
                && providers.equals(snapshot.providers);
    }

    /** 四个业务字段决定值哈希，凭据不作为可见数据。 */
    @Override
    public int hashCode() {
        return Objects.hash(key, schemaFingerprint, providers, mappingRevision);
    }

    /** 只描述业务快照，不输出私有授权凭据。 */
    @Override
    public String toString() {
        return "VerifiedProviderSnapshot[key=" + key + ", schemaFingerprint=" + schemaFingerprint
                + ", providers=" + providers + ", mappingRevision=" + mappingRevision + ']';
    }

    /**
     * 拒绝零端口以及只能作为本地监听地址使用的通配地址。
     *
     * @param endpoint 待校验端点
     * @return 不可对外建立 RPC 连接时返回 true
     */
    public static boolean invalidEndpoint(RpcEndpoint endpoint) {
        if (endpoint == null || endpoint.port() <= RpcEndpoint.MIN_PORT) {
            return true;
        }
        String host = endpoint.host();
        return RpcEndpoint.UNSPECIFIED_IPV4_HOST.equals(host)
                || RpcEndpoint.UNSPECIFIED_IPV6_HOST.equals(host)
                || RpcEndpoint.UNSPECIFIED_IPV6_BRACKETED_HOST.equals(host);
    }
}
