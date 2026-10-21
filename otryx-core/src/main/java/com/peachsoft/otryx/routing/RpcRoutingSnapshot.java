package com.peachsoft.otryx.routing;

import com.peachsoft.otryx.api.ServiceKey;
import java.util.Map;
import java.util.Objects;

/**
 * 带单调版本号的完整路由规则快照；不可变且有数量上界。
 *
 * @param revision 单调递增版本，零表示初始无规则
 * @param rules 每个业务契约独立的路由规则
 * @Author OpenAI
 * @Version 1.0.0-SNAPSHOT
 * @CreateTime 2026/10/21 13:38
 */
public record RpcRoutingSnapshot(long revision, Map<ServiceKey, RpcRouteRule> rules) {
    private static final int MAX_RULES = 512;

    /** 默认无过滤规则。 */
    public static final RpcRoutingSnapshot EMPTY = new RpcRoutingSnapshot(0L, Map.of());

    /** 验证并冻结路由快照，版本不得为负数。 */
    public RpcRoutingSnapshot {
        if (revision < 0L) {
            throw new IllegalArgumentException("Routing revision must not be negative");
        }
        rules = Map.copyOf(Objects.requireNonNull(rules, "rules"));
        if (rules.size() > MAX_RULES) {
            throw new IllegalArgumentException("Routing snapshot exceeds capacity");
        }
    }
}
