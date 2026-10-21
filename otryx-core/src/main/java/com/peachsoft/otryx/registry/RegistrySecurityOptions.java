package com.peachsoft.otryx.registry;

import java.util.Objects;
import java.util.Optional;

/**
 * 组合单条 Registry 链的 TLS 材料与凭据；Eureka Metadata 必须独立配置。
 *
 * @Author OpenAI
 * @Version 1.0.0-SNAPSHOT
 * @CreateTime 2026/10/19 11:22
 */
public final class RegistrySecurityOptions {
    private final Optional<RegistryTlsOptions> tls;
    private final RegistryCredentials credentials;

    /**
     * 固化单链安全意图，不将 DTO 本身作为实际保护证明。
     *
     * @param tls 可选受控 TLS 材料
     * @param credentials 非 null 后端凭据配置
     */
    public RegistrySecurityOptions(Optional<RegistryTlsOptions> tls, RegistryCredentials credentials) {
        this.tls = Objects.requireNonNull(tls, "tls");
        this.credentials = Objects.requireNonNull(credentials, "credentials");
    }

    /**
     * 创建没有网络保护材料的配置，只能配合明确允许的开发或进程内场景。
     *
     * @return 空材料配置
     */
    public static RegistrySecurityOptions none() {
        return new RegistrySecurityOptions(Optional.empty(), RegistryCredentials.none());
    }

    /**
     * 返回受控材料引用。
     *
     * @return 可选 TLS 配置
     */
    public Optional<RegistryTlsOptions> tls() {
        return tls;
    }

    /**
     * 返回不会公开可变内部数组的凭据配置。
     *
     * @return 单种凭据配置
     */
    public RegistryCredentials credentials() {
        return credentials;
    }

    /** 隐藏全部材料与凭据值。 */
    @Override
    public String toString() {
        return "RegistrySecurityOptions[tls=" + tls.isPresent() + ", credentials=" + credentials.kind() + ']';
    }
}
