package com.peachsoft.otryx.registry;

/**
 * 标识实际 Registry 通道采用的认证机制，不承载凭据值。
 *
 * @Author OpenAI
 * @Version 1.0.0-SNAPSHOT
 * @CreateTime 2026/10/19 11:22
 */
public enum CredentialKind {
    /** 未配置后端认证。 */
    NONE,
    /** 后端用户与密码认证。 */
    USER_PASSWORD,
    /** 后端独立 ACL Token。 */
    ACL_TOKEN,
    /** 服务端明确采用的客户端证书身份。 */
    CLIENT_CERTIFICATE
}
