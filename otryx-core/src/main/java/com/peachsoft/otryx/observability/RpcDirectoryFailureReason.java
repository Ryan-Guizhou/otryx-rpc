package com.peachsoft.otryx.observability;

/**
 * RPC Consumer 本地应用级目录和实际路由失败的固定、低基数原因。
 *
 * <p>不包含业务参数、ServiceKey、InstanceId、Endpoint 或远端异常文本。
 *
 * @Author OpenAI
 * @Version 1.0.0-SNAPSHOT
 * @CreateTime 2026/10/21 18:20
 */
public enum RpcDirectoryFailureReason {
    /** 尚未观察到目录/选路失败。 */
    NONE,
    /** Consumer 尚未完成真实应用成员注册。 */
    MEMBERSHIP_NOT_READY,
    /** 尚未收到可信初始目录。 */
    DIRECTORY_NOT_READY,
    /** 最新可信目录内没有兼容且可调用的 Provider。 */
    VERIFIED_EMPTY,
    /** 目录已超出失联后的有效期限。 */
    DIRECTORY_STALE,
    /** 目录已经关闭。 */
    DIRECTORY_CLOSED,
    /** 安全来源或签名验证失败。 */
    SECURITY_REJECTED,
    /** Router 筛除了全部可信候选。 */
    ROUTER_FILTERED_ALL,
    /** 治理规则筛除了全部可信候选。 */
    GOVERNANCE_FILTERED_ALL,
    /** 已有可信候选但未选出健康端点。 */
    NO_HEALTHY_ENDPOINT
}
