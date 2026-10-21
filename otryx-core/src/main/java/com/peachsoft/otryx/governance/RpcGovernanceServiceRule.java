package com.peachsoft.otryx.governance;

import com.peachsoft.otryx.api.ApplicationKey;
import com.peachsoft.otryx.routing.RpcRouteRule;
import java.util.Map;
import java.util.Objects;

/**
 * 服务级治理策略，路由仅对已验证 Provider 子集生效。
 *
 * @param route 可选的更严格路由过滤规则
 * @param applicationWeights 指定应用权重，只影响已通过 Router 的候选；不增加或伪造端点
 * @param methods 真实 Method ID 对应的方法级治理
 * @Author OpenAI
 * @Version 1.0.0-SNAPSHOT
 * @CreateTime 2026/10/21 17:30
 */
public record RpcGovernanceServiceRule(
        RpcRouteRule route,
        Map<ApplicationKey, Integer> applicationWeights,
        Map<Integer, RpcGovernanceMethodRule> methods) {
    private static final int MAX_APPLICATION_WEIGHTS = 128;
    private static final int MAX_METHODS = 256;
    private static final int MAX_WEIGHT = 10000;

    /** 完整冻结所有子规则并强制有界输入。 */
    public RpcGovernanceServiceRule {
        applicationWeights = Map.copyOf(Objects.requireNonNull(applicationWeights, "applicationWeights"));
        methods = Map.copyOf(Objects.requireNonNull(methods, "methods"));
        if (applicationWeights.size() > MAX_APPLICATION_WEIGHTS || methods.size() > MAX_METHODS) {
            throw new IllegalArgumentException("Governance service rule exceeds capacity");
        }
        applicationWeights.forEach((application, weight) -> {
            if (application == null || weight == null || weight < 1 || weight > MAX_WEIGHT) {
                throw new IllegalArgumentException("Invalid governance application weight");
            }
        });
        methods.forEach((method, rule) -> {
            if (method == null || rule == null) {
                throw new IllegalArgumentException("Invalid governance method rule");
            }
        });
    }
}
