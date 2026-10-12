package com.peachsoft.otryx.registry.nacos;

import com.alibaba.nacos.api.NacosFactory;
import com.alibaba.nacos.api.PropertyKeyConst;
import com.alibaba.nacos.api.exception.NacosException;
import com.alibaba.nacos.api.naming.NamingService;
import com.peachsoft.otryx.registry.Registry;
import com.peachsoft.otryx.registry.RegistryFactory;
import com.peachsoft.otryx.registry.RegistryOptions;
import com.peachsoft.otryx.spi.Extension;
import java.util.List;
import java.util.Properties;

/**
 * Nacos 注册中心工厂。
 *
 * @Author Ryan
 * @Version 1.0.0
 * @CreateTime 2026/9/28 11:35
 */
@Extension(NacosRegistryFactory.EXTENSION_NAME)
public final class NacosRegistryFactory implements RegistryFactory {

    static final String EXTENSION_NAME = "nacos";
    private static final String OPTION_GROUP = "nacosGroup";
    private static final String OPTION_CLUSTER = "nacosCluster";
    private static final String OPTION_USERNAME = "nacosUsername";
    private static final String OPTION_PASSWORD = "nacosPassword";
    private static final String CLUSTER_NAME_PATTERN = "[0-9A-Za-z.-]+";

    static final String DEFAULT_ENDPOINT = "127.0.0.1:8848";
    static final String DEFAULT_NAMESPACE = "public";
    static final String DEFAULT_GROUP = "OTRYX_RPC";
    static final String DEFAULT_CLUSTER = "DEFAULT";

    /** 创建 Nacos 注册中心工厂。 */
    public NacosRegistryFactory() {
    }

    @Override
    public Registry create(RegistryOptions options) {
        List<String> endpoints = options.endpoints().isEmpty()
                ? List.of(DEFAULT_ENDPOINT)
                : options.endpoints();
        validateEndpoints(endpoints);
        String namespace = defaultIfBlank(
                options.namespace(),
                DEFAULT_NAMESPACE);
        String group = defaultIfBlank(
                options.providerOption(OPTION_GROUP, ""),
                DEFAULT_GROUP);
        String cluster = defaultIfBlank(
                options.providerOption(OPTION_CLUSTER, ""),
                DEFAULT_CLUSTER);
        validateCluster(cluster);
        Properties properties = clientProperties(
                options,
                endpoints,
                namespace);
        try {
            NamingService namingService =
                    NacosFactory.createNamingService(properties);
            return new NacosRegistry(
                    namingService,
                    namespace,
                    group,
                    cluster,
                    options.observer());
        } catch (NacosException error) {
            throw new IllegalStateException(
                    "Failed to create Nacos registry client: endpoints="
                            + endpoints
                            + ", namespace="
                            + namespace
                            + ", group="
                            + group
                            + ", cluster="
                            + cluster,
                    error);
        }
    }

    /**
     * 构造 Nacos SDK Properties。
     *
     * <p>该包级方法用于验证 Credential 只进入 SDK 配置，而不会被编码进
     * endpoint 或异常描述。
     *
     * @param options Registry 配置
     * @param endpoints 已校验端点
     * @param namespace Nacos Namespace
     * @return Nacos SDK Properties
     */
    static Properties clientProperties(
            RegistryOptions options,
            List<String> endpoints,
            String namespace) {
        Properties properties = new Properties();
        properties.setProperty(
                PropertyKeyConst.SERVER_ADDR,
                String.join(",", endpoints));
        properties.setProperty(
                PropertyKeyConst.NAMESPACE,
                namespace);
        String username =
                options.providerOption(
                        OPTION_USERNAME,
                        "");
        String password =
                options.providerOption(
                        OPTION_PASSWORD,
                        "");
        if (!username.isBlank()) {
            properties.setProperty(
                    PropertyKeyConst.USERNAME,
                    username);
        }
        if (!password.isBlank()) {
            properties.setProperty(
                    PropertyKeyConst.PASSWORD,
                    password);
        }
        return properties;
    }

    private static void validateCluster(String cluster) {
        if (!cluster.matches(CLUSTER_NAME_PATTERN)) {
            throw new IllegalArgumentException(
                    "Nacos cluster must contain only 0-9, a-z, A-Z, '-' or '.'");
        }
    }

    private static void validateEndpoints(List<String> endpoints) {
        for (String endpoint : endpoints) {
            if (endpoint.indexOf('@') >= 0) {
                throw new IllegalArgumentException(
                        "Nacos endpoint must not embed credentials");
            }
        }
    }

    private static String defaultIfBlank(
            String value,
            String fallback) {
        return value == null || value.isBlank()
                ? fallback
                : value.trim();
    }
}
