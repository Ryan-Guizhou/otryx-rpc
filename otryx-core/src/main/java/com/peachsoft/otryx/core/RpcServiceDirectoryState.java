package com.peachsoft.otryx.core;

/**
 * RPC Consumer 本地服务诊断状态，不代表实时远端 Registry 查询。
 *
 * @Author OpenAI
 * @Version 1.0.0-SNAPSHOT
 * @CreateTime 2026/10/21 18:20
 */
public enum RpcServiceDirectoryState {
    /** 尚未通过 refer() 建立该服务订阅。 */
    NOT_REFERENCED,
    /** Consumer 实例成员尚未完成注册。 */
    MEMBERSHIP_NOT_READY,
    /** 尚无经过完整验证的应用目录快照。 */
    NOT_READY,
    /** 本地已验证目录正常。 */
    HEALTHY,
    /** 控制面异常，仍在 LKG 有界容忍窗口内。 */
    LAST_KNOWN_GOOD,
    /** LKG 窗口已过，拒绝过期路由。 */
    STALE,
    /** 来源、完整性或安全 epoch 校验失败。 */
    SECURITY_REJECTED,
    /** Client 或目录已关闭。 */
    CLOSED
}
