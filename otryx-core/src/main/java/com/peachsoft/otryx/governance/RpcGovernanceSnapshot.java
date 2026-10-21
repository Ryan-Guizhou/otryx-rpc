package com.peachsoft.otryx.governance;

import com.peachsoft.otryx.api.ServiceKey;
import java.util.Map;
import java.util.Objects;

/**
 * 授权治理事件携带的完整不可变应用/方法规则快照。
 *
 * <p>同一逻辑调用捕获一个 revision，后续 Retry 不混用已更新配置。
 * 0 为 Core 内部的无配置版本，外部事件必须使用大于零的 revision。
 *
 * @param revision 全局单调治理版本
 * @param services 完整 ServiceKey 对应的规则表
 * @Author OpenAI
 * @Version 1.0.0-SNAPSHOT
 * @CreateTime 2026/10/21 17:30
 */
public record RpcGovernanceSnapshot(long revision, Map<ServiceKey, RpcGovernanceServiceRule> services) {
    private static final int MAX_SERVICES = 512;
    /** 不曾从可信治理控制面读到规则时的内部初始状态。 */
    public static final RpcGovernanceSnapshot EMPTY = new RpcGovernanceSnapshot(0L, Map.of());

    /** 对所有 ServiceKey/Rule 做完整校验与防修改拷贝。 */
    public RpcGovernanceSnapshot {
        if (revision < 0L) {
            throw new IllegalArgumentException("Governance revision must not be negative");
        }
        services = Map.copyOf(Objects.requireNonNull(services, "services"));
        if (services.size() > MAX_SERVICES) {
            throw new IllegalArgumentException("Governance service count exceeds capacity");
        }
    }
}
