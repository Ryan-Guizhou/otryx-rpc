package com.peachsoft.otryx.registry.etcd;

import com.peachsoft.otryx.registry.ApplicationRegistry;
import com.peachsoft.otryx.registry.ApplicationRegistryFactory;
import com.peachsoft.otryx.registry.RegistryOptions;
import com.peachsoft.otryx.spi.Extension;
import java.util.List;
import java.util.Objects;

/**
 * Etcd V2 应用级注册中心 SPI 工厂，Provider 与 Consumer 共享一个实例。
 *
 * <p>通过返回实例的 manifestRepository 与 serviceMappingRepository 获取同一
 * 可信 Etcd 命名空间上的元数据仓库；不复用历史接口级 RegistryFactory。
 *
 * @Author OpenAI
 * @Version 1.0.0-SNAPSHOT
 * @CreateTime 2026/10/16 10:33
 */
@Extension(EtcdApplicationRegistryFactory.EXTENSION_NAME)
public final class EtcdApplicationRegistryFactory implements ApplicationRegistryFactory {

    /** 应用级 Etcd SPI 名称。 */
    public static final String EXTENSION_NAME = "etcd";
    private static final String OPTION_LEASE_TTL_SECONDS = "leaseTtlSeconds";
    private static final long DEFAULT_LEASE_TTL_SECONDS = 30L;
    private static final String DEFAULT_NAMESPACE = "default";
    private static final List<String> DEFAULT_ENDPOINTS = List.of("http://127.0.0.1:2379");

    /** 创建 Etcd V2 注册中心工厂。 */
    public EtcdApplicationRegistryFactory() {
    }

    /**
     * 创建一个独立的应用级 Registry 及关联元数据后端生命周期。
     *
     * @param options 已脱敏的配置入口，必须为调用方主动提供
     * @return 由调用方负责关闭的 Etcd V2 应用注册中心
     */
    @Override
    public ApplicationRegistry create(RegistryOptions options) {
        Objects.requireNonNull(options, "options");
        List<String> endpoints = options.endpoints().isEmpty()
                ? DEFAULT_ENDPOINTS : options.endpoints();
        long ttl = Long.parseLong(options.providerOption(
                OPTION_LEASE_TTL_SECONDS, Long.toString(DEFAULT_LEASE_TTL_SECONDS)));
        if (ttl <= 0L) {
            throw new IllegalArgumentException("Etcd leaseTtlSeconds must be positive");
        }
        String namespace = options.namespace().isBlank()
                ? DEFAULT_NAMESPACE : options.namespace();
        return new EtcdApplicationRegistry(
                io.etcd.jetcd.Client.builder().endpoints(endpoints.toArray(String[]::new)).build(),
                ttl,
                namespace,
                options.observer());
    }
}
