package com.peachsoft.otryx.registry.etcd;

import com.peachsoft.otryx.registry.Registry;
import com.peachsoft.otryx.registry.RegistryFactory;
import com.peachsoft.otryx.registry.RegistryOptions;
import com.peachsoft.otryx.spi.Extension;
import java.util.List;

/**
 * Etcd 注册中心工厂。
 *
 * @Author Ryan
 * @Version 1.0.0
 * @CreateTime 2026/9/23 10:51
 */
@Extension(EtcdRegistryFactory.EXTENSION_NAME)
public final class EtcdRegistryFactory implements RegistryFactory {

    static final String EXTENSION_NAME = "etcd";
    private static final String OPTION_LEASE_TTL_SECONDS = "leaseTtlSeconds";

    private static final long DEFAULT_LEASE_TTL_SECONDS = 30;
    private static final List<String> DEFAULT_ENDPOINTS =
            List.of("http://127.0.0.1:2379");

    /** 创建 Etcd 注册中心工厂。 */
    public EtcdRegistryFactory() {
    }

    @Override
    public Registry create(RegistryOptions options) {
        List<String> configured = options.endpoints().isEmpty()
                ? DEFAULT_ENDPOINTS
                : options.endpoints();
        long leaseTtlSeconds = Long.parseLong(options.providerOption(
                OPTION_LEASE_TTL_SECONDS,
                Long.toString(DEFAULT_LEASE_TTL_SECONDS)));
        if (leaseTtlSeconds <= 0) {
            throw new IllegalArgumentException(
                    "leaseTtlSeconds must be positive");
        }
        String namespace = options.namespace().isBlank()
                ? EtcdRegistry.DEFAULT_NAMESPACE
                : options.namespace();
        return new EtcdRegistry(
                configured.toArray(String[]::new),
                leaseTtlSeconds,
                namespace,
                options.observer());
    }
}
