package com.peachsoft.otryx.core;

import com.peachsoft.otryx.api.ServiceKey;
import com.peachsoft.otryx.observability.RpcDirectoryFailureReason;
import java.util.Objects;

/**
 * 一次本地无远程 IO 的不可变 RPC 服务诊断。
 *
 * <p>只返回经过验证的目录状态、计数和最近一次实际路由失败的有限枚举。
 * ServiceKey 仅用于调用方主动查询，禁止作为自动 Metrics/JFR 标签。
 * 不包含实例地址、Metadata、完整异常或业务参数。
 *
 * @param serviceKey 被查询的业务服务契约
 * @param directoryState 本地目录状态
 * @param reason 当前阻断或最近一次同一可信快照下的业务选路失败原因
 * @param verifiedProviders 当前可路由且经过验证的 Provider 数
 * @param usableProviders 其中没有被本地 Outlier 排除的候选数
 * @param mappingRevision 当前受信任的 Mapping revision，未就绪时为零
 *
 * @Author OpenAI
 * @Version 1.0.0-SNAPSHOT
 * @CreateTime 2026/10/21 18:20
 */
public record RpcServiceDiagnosticSnapshot(
        ServiceKey serviceKey, RpcServiceDirectoryState directoryState,
        RpcDirectoryFailureReason reason, int verifiedProviders,
        int usableProviders, long mappingRevision) {

    /** 验证输入计数及非空的本地服务契约。 */
    public RpcServiceDiagnosticSnapshot {
        Objects.requireNonNull(serviceKey, "serviceKey");
        Objects.requireNonNull(directoryState, "directoryState");
        Objects.requireNonNull(reason, "reason");
        if (verifiedProviders < 0 || usableProviders < 0 || usableProviders > verifiedProviders
                || mappingRevision < 0L) {
            throw new IllegalArgumentException("Invalid RPC service diagnostic counters");
        }
    }
}
