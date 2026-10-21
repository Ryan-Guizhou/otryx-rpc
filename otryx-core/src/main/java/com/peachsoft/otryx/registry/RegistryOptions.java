package com.peachsoft.otryx.registry;

import com.peachsoft.otryx.api.RpcSecurityProfile;
import com.peachsoft.otryx.observability.RpcObserver;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Registry Adapter 的公共启动配置。
 *
 * @param endpoints 注册中心端点
 * @param namespace 逻辑命名空间
 * @param providerOptions Adapter 私有配置
 * @param observer 控制面 Observer
 * @param securityProfile 与 Core 共用的明确安全意图
 * @param security 当前成员与元数据链的安全配置
 * @param metadataSecurity Eureka 外置元数据链的独立安全配置
 *
 * @Author Ryan
 * @Version 1.0.0
 * @CreateTime 2026/9/23 11:46
 */
public record RegistryOptions(
        List<String> endpoints,
        String namespace,
        Map<String, String> providerOptions,
        RpcObserver observer,
        RpcSecurityProfile securityProfile,
        RegistrySecurityOptions security,
        Optional<RegistrySecurityOptions> metadataSecurity) {

    /**
     * 保留原三参数构造方式，默认使用 NOOP Observer。
     *
     * @param endpoints 注册中心端点
     * @param namespace 逻辑命名空间
     * @param providerOptions Adapter 私有配置
     */
    public RegistryOptions(
            List<String> endpoints,
            String namespace,
            Map<String, String> providerOptions) {
        this(
                endpoints,
                namespace,
                providerOptions,
                RpcObserver.noop());
    }

    /**
     * 保留 Observer 构造形状，但未指定安全意图的配置不能启动 Factory。
     *
     * @param endpoints 注册中心端点
     * @param namespace 逻辑命名空间
     * @param providerOptions Adapter 私有配置
     * @param observer 控制面 Observer
     */
    public RegistryOptions(List<String> endpoints, String namespace,
                           Map<String, String> providerOptions, RpcObserver observer) {
        this(endpoints, namespace, providerOptions, observer, RpcSecurityProfile.UNSPECIFIED,
                RegistrySecurityOptions.none(), Optional.empty());
    }

    /**
     * 为显式开发场景创建不含安全材料的配置。
     *
     * @param endpoints 注册中心端点
     * @param namespace 逻辑命名空间
     * @param providerOptions Adapter 非安全配置
     * @return 明确选择开发意图的 Registry 配置
     */
    public static RegistryOptions trustedDevelopment(
            List<String> endpoints, String namespace, Map<String, String> providerOptions) {
        return trustedDevelopment(endpoints, namespace, providerOptions, RpcObserver.noop());
    }

    /**
     * 为带 Observer 的显式开发场景创建配置，不推断生产保护。
     *
     * @param endpoints 注册中心端点
     * @param namespace 逻辑命名空间
     * @param providerOptions Adapter 非安全配置
     * @param observer 控制面 Observer
     * @return 明确开发意图的配置
     */
    public static RegistryOptions trustedDevelopment(
            List<String> endpoints, String namespace, Map<String, String> providerOptions, RpcObserver observer) {
        return new RegistryOptions(endpoints, namespace, providerOptions, observer,
                RpcSecurityProfile.TRUSTED_DEVELOPMENT, RegistrySecurityOptions.none(), Optional.empty());
    }

    /** 校验并固化 Registry 配置。 */
    public RegistryOptions {
        endpoints = endpoints == null
                ? List.of()
                : endpoints.stream()
                        .map(String::trim)
                        .filter(value -> !value.isEmpty())
                        .toList();
        namespace = namespace == null
                ? ""
                : namespace.trim();
        providerOptions = providerOptions == null
                ? Map.of()
                : Map.copyOf(providerOptions);
        observer = observer == null
                ? RpcObserver.noop()
                : observer;
        securityProfile = Objects.requireNonNull(securityProfile, "securityProfile");
        security = Objects.requireNonNull(security, "security");
        metadataSecurity = Objects.requireNonNull(metadataSecurity, "metadataSecurity");
    }

    /**
     * 从逗号分隔的端点配置创建 RegistryOptions。
     *
     * @param endpoints 逗号分隔端点
     * @param namespace 逻辑命名空间
     * @param providerOptions Adapter 私有配置
     * @return Registry 配置
     */
    public static RegistryOptions fromCsv(
            String endpoints,
            String namespace,
            Map<String, String> providerOptions) {
        return fromCsv(
                endpoints,
                namespace,
                providerOptions,
                RpcObserver.noop());
    }

    /**
     * 从逗号分隔的端点配置创建 RegistryOptions。
     *
     * @param endpoints 逗号分隔端点
     * @param namespace 逻辑命名空间
     * @param providerOptions Adapter 私有配置
     * @param observer 控制面 Observer
     * @return Registry 配置
     */
    public static RegistryOptions fromCsv(
            String endpoints,
            String namespace,
            Map<String, String> providerOptions,
            RpcObserver observer) {
        List<String> values = endpoints == null || endpoints.isBlank()
                ? List.of()
                : Arrays.stream(endpoints.split(","))
                        .map(String::trim)
                        .filter(value -> !value.isEmpty())
                        .toList();
        return new RegistryOptions(
                values,
                namespace,
                providerOptions,
                observer);
    }

    /**
     * 返回脱敏后的配置描述。
     *
     * <p>Registry Credential 不允许通过 record 默认 toString 泄露。
     *
     * @return 脱敏配置描述
     */
    @Override
    public String toString() {
        String safeNamespace = namespace.matches("[A-Za-z0-9._-]{0,128}") ? namespace : "[invalid]";
        return "RegistryOptions[endpointCount=" + endpoints.size()
                + ", namespace=" + safeNamespace
                + ", providerOptionCount=" + providerOptions.size()
                + ", securityProfile=" + securityProfile
                + ", security=" + security
                + ", metadataSecurityConfigured=" + metadataSecurity.isPresent() + ']';
    }

    /**
     * 读取 Adapter 私有配置。
     *
     * @param key 配置键
     * @param fallback 默认值
     * @return 配置值
     */
    public String providerOption(
            String key,
            String fallback) {
        return providerOptions.getOrDefault(
                key,
                fallback);
    }
}
