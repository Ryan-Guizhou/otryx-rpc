package com.peachsoft.otryx.registry;

/**
 * 描述已构造的实际 Registry 通道保护，不能替代后端 ACL 验证。
 *
 * @Author OpenAI
 * @Version 1.0.0-SNAPSHOT
 * @CreateTime 2026/10/19 11:22
 */
public enum RegistrySourceProtection {
    /** 实际通道尚未审核或未被覆盖。 */
    UNKNOWN,
    /** Core 内审核过的进程内实现，无网络来源。 */
    LOCAL_PROCESS,
    /** 不满足受认证 TLS 通道要求。 */
    PLAINTEXT,
    /** 已安装验证服务端身份的 TLS 和明确后端认证。 */
    AUTHENTICATED_TLS
}
