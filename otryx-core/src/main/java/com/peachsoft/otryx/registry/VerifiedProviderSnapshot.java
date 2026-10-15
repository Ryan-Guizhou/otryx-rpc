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
 * @param key 带命名空间的业务服务键
 * @param schemaFingerprint 调用方期望的 RPC Schema 指纹
 * @param providers 经校验并可供 Router/LB 使用的实例快照
 * @param mappingRevision 生成本快照的 ServiceMapping Revision
 *
 * @Author OpenAI
 * @Version 1.0.0-SNAPSHOT
 * @CreateTime 2026/10/15 18:30
 */
public record VerifiedProviderSnapshot(
        ServiceMappingKey key,
        String schemaFingerprint,
        List<ApplicationInstance> providers,
        long mappingRevision) {

    private static final int MAX_PROVIDERS = 1024;

    /** 校验不可变路由集，拒绝不安全 Endpoint 及重复实例。 */
    public VerifiedProviderSnapshot {
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
