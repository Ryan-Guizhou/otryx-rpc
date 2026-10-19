package com.peachsoft.otryx.registry;

/**
 * 为 Registry 安全拒绝提供稳定、无敏感内容的分类。
 *
 * @Author OpenAI
 * @Version 1.0.0-SNAPSHOT
 * @CreateTime 2026/10/19 11:22
 */
public enum RegistrySecurityFailure {
    /** 实际来源或配置未满足安全保护契约。 */
    UNSUPPORTED_PROTECTION,
    /** 实际 TLS、证书链或主机身份验证失败。 */
    TLS_REJECTED,
    /** 后端明确拒绝认证。 */
    AUTHENTICATION_REJECTED,
    /** 后端明确拒绝访问权限。 */
    AUTHORIZATION_REJECTED,
    /** 内容的应用、业务键或修订引用不匹配。 */
    METADATA_REFERENCE_MISMATCH,
    /** 内容结构、完整性或本地快照凭据验证失败。 */
    METADATA_INTEGRITY_REJECTED,
    /** 不可信响应超过接收或解析预算。 */
    RESPONSE_LIMIT_EXCEEDED
}
