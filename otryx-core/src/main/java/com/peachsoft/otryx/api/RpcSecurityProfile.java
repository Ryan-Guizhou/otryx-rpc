package com.peachsoft.otryx.api;

/**
 * 宿主显式选择的 RPC 安全要求，不从 Transport 配置推断。
 *
 * @Author OpenAI
 * @Version 1.0.0-SNAPSHOT
 * @CreateTime 2026/10/19 11:18
 */
public enum RpcSecurityProfile {
    /** 尚未显式选择安全要求，运行时 Builder 与 Registry 工厂必须拒绝该值。 */
    UNSPECIFIED,
    /** 宿主明确选择隔离可信开发环境，协议校验与资源约束仍然生效。 */
    TRUSTED_DEVELOPMENT,
    /** 要求双向 TLS、应用身份绑定、有限授权及实际 Codec 与 Registry 安全能力。 */
    NETWORK_MTLS
}
