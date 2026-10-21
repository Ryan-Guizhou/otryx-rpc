package com.peachsoft.otryx.registry;

import com.peachsoft.otryx.api.RpcSecurityProfile;
import java.net.URI;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 统一 Factory 的显式意图和允许配置校验；保护描述仍须在真实客户端安装后建立。
 *
 * @Author OpenAI
 * @Version 1.0.0-SNAPSHOT
 * @CreateTime 2026/10/19 11:40
 */
public final class RegistrySecuritySupport {
    private static final int MAX_ENDPOINTS = 16;
    private static final int MAX_ENDPOINT_CHARACTERS = 2048;

    private RegistrySecuritySupport() {
    }

    /**
     * 在创建任何客户端或工作线程之前拒绝未指定意图和未知配置键。
     *
     * @param options Registry 配置
     * @param allowedKeys 当前 Factory 实际实现的非安全配置键
     * @param allowMetadata 是否允许独立 Metadata 安全链
     */
    public static void requireOptions(RegistryOptions options, Set<String> allowedKeys, boolean allowMetadata) {
        if (options.securityProfile() == RpcSecurityProfile.UNSPECIFIED
                || !allowMetadata && options.metadataSecurity().isPresent()
                || !allowedKeys.containsAll(options.providerOptions().keySet())) {
            throw rejected();
        }
    }

    /**
     * 校验所有 HTTP/gRPC 候选均使用同一明确 TLS 模式和受支持的认证机制。
     *
     * @param options Registry 配置
     * @param endpoints 实际客户端即将使用的完整 URL
     * @param supportedCredentials 适配器实际实现的认证方式
     */
    public static void requireTransport(
            RegistryOptions options, List<String> endpoints, Set<CredentialKind> supportedCredentials) {
        RegistrySecurityOptions security = options.security();
        if (!supportedCredentials.contains(security.credentials().kind())
                || endpoints.isEmpty() || endpoints.size() > MAX_ENDPOINTS) {
            throw rejected();
        }
        boolean tls = security.tls().isPresent();
        for (String endpoint : endpoints) {
            URI uri;
            try {
                if (endpoint == null || endpoint.length() > MAX_ENDPOINT_CHARACTERS) {
                    throw rejected();
                }
                uri = URI.create(endpoint);
            } catch (IllegalArgumentException invalid) {
                throw rejected();
            }
            if (uri.getHost() == null || uri.getUserInfo() != null
                    || uri.getQuery() != null || uri.getFragment() != null
                    || !(tls ? "https" : "http").equalsIgnoreCase(uri.getScheme())) {
                throw rejected();
            }
        }
        if (security.credentials().kind() == CredentialKind.CLIENT_CERTIFICATE
                && (!tls || security.tls().orElseThrow().clientIdentity().isEmpty())) {
            throw rejected();
        }
        if (options.securityProfile() == RpcSecurityProfile.NETWORK_MTLS
                && (!tls || security.credentials().kind() == CredentialKind.NONE)) {
            throw rejected();
        }
    }

    /**
     * 仅供安装真实 TLS 与凭据后的 Factory 构建保护事实；不能用于用户自述配置放行。
     *
     * @param security 已完成验证并安装到客户端的单链配置
     * @param namespace 实际数据命名空间
     * @param group 实际后端组
     * @param channels 由同一实际客户端提供的通道
     * @return 不包含秘密的不可变来源描述
     */
    public static RegistrySecurityDescriptor installed(
            RegistrySecurityOptions security, String namespace, Optional<String> group,
            RegistryChannel... channels) {
        Set<CredentialKind> kinds = EnumSet.noneOf(CredentialKind.class);
        if (security.credentials().kind() != CredentialKind.NONE) {
            kinds.add(security.credentials().kind());
        }
        if (security.tls().flatMap(RegistryTlsOptions::clientIdentity).isPresent()) {
            kinds.add(CredentialKind.CLIENT_CERTIFICATE);
        }
        RegistrySourceProtection protection;
        if (security.tls().isEmpty()) {
            protection = RegistrySourceProtection.PLAINTEXT;
        } else if (kinds.isEmpty()) {
            protection = RegistrySourceProtection.UNKNOWN;
        } else {
            protection = RegistrySourceProtection.AUTHENTICATED_TLS;
        }
        ChannelProtection source = new ChannelProtection(protection, kinds, Optional.of(namespace), group);
        Map<RegistryChannel, List<ChannelProtection>> values = new LinkedHashMap<>();
        for (RegistryChannel channel : channels) {
            values.put(channel, List.of(source));
        }
        return new RegistrySecurityDescriptor(values);
    }

    private static RegistrySecurityException rejected() {
        return new RegistrySecurityException(RegistrySecurityFailure.UNSUPPORTED_PROTECTION);
    }
}
