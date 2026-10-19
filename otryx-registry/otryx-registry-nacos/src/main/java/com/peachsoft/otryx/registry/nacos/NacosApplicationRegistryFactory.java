package com.peachsoft.otryx.registry.nacos;

import com.alibaba.nacos.api.NacosFactory;
import com.alibaba.nacos.api.PropertyKeyConst;
import com.alibaba.nacos.api.config.ConfigService;
import com.alibaba.nacos.api.naming.NamingService;
import com.peachsoft.otryx.registry.ApplicationRegistry;
import com.peachsoft.otryx.registry.ApplicationRegistryFactory;
import com.peachsoft.otryx.registry.RegistryOptions;
import com.peachsoft.otryx.spi.Extension;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Objects;
import java.util.Properties;

/**
 * Nacos V2 Naming + Config 的应用级 SPI 工厂。
 *
 * <p>生产运行必须为 Config 配置支持 MD5-CAS 的 Nacos 3.2.4+，
 * 并为 Naming/Config 配置正确的权限隔离。Consumer 使用本机真实 IP
 * 及 port=0，仅用于 Naming 心跳而不形成可调用 RPC Endpoint。
 *
 * @Author OpenAI
 * @Version 1.0.0-SNAPSHOT
 * @CreateTime 2026/10/16 13:42
 */
@Extension(NacosApplicationRegistryFactory.EXTENSION_NAME)
public final class NacosApplicationRegistryFactory implements ApplicationRegistryFactory {

    /** 新应用级 SPI 名称。 */
    public static final String EXTENSION_NAME = "nacos";
    private static final String OPTION_GROUP = "nacosGroup";
    private static final String OPTION_CLUSTER = "nacosCluster";
    private static final String OPTION_MEMBER_HOST = "nacosMemberHost";
    private static final String OPTION_USERNAME = "nacosUsername";
    private static final String OPTION_PASSWORD = "nacosPassword";
    private static final String VALID_GROUP = "[A-Za-z0-9._:-]{1,128}";
    private static final String VALID_CLUSTER = "[A-Za-z0-9.-]{1,64}";
    static final String DEFAULT_ENDPOINT = "127.0.0.1:8848";
    static final String DEFAULT_NAMESPACE = "public";
    static final String DEFAULT_GROUP = "OTRYX_RPC";
    static final String DEFAULT_CLUSTER = "DEFAULT";

    /** 创建应用级 Nacos 工厂。 */
    public NacosApplicationRegistryFactory() {
    }

    /**
     * 为当前进程创建 Naming 与 Config 独立 SDK Client，关联后端共用生命周期。
     *
     * @param options Nacos 注册中心端点、Namespace、Group、Cluster 和可选消费端 Member Host
     * @return 必须由调用方显式关闭的应用级 Registry
     */
    @Override
    public ApplicationRegistry create(RegistryOptions options) {
        Objects.requireNonNull(options, "options");
        List<String> endpoints = options.endpoints().isEmpty()
                ? List.of(DEFAULT_ENDPOINT) : options.endpoints();
        for (String endpoint : endpoints) {
            if (endpoint.contains("@")) {
                throw new IllegalArgumentException("Nacos endpoint must not embed credentials");
            }
        }
        String namespace = options.namespace().isBlank()
                ? DEFAULT_NAMESPACE : options.namespace();
        String group = options.providerOption(OPTION_GROUP, DEFAULT_GROUP);
        String cluster = options.providerOption(OPTION_CLUSTER, DEFAULT_CLUSTER);
        if (!group.matches(VALID_GROUP) || !cluster.matches(VALID_CLUSTER)) {
            throw new IllegalArgumentException("Invalid Nacos group or cluster");
        }
        String host = options.providerOption(OPTION_MEMBER_HOST, "");
        if (host.isBlank()) {
            host = detectMemberHost();
        }
        if (!NacosV2InstanceMapper.validHost(host)) {
            throw new IllegalArgumentException("Nacos consumer membership host must be a real local IP");
        }
        Properties props = clientProperties(options, endpoints, namespace);
        NamingService naming = null;
        ConfigService config = null;
        try {
            naming = NacosFactory.createNamingService(props);
            config = NacosFactory.createConfigService(props);
            return new NacosApplicationRegistry(naming, config, namespace, group, cluster,
                    host, options.observer());
        } catch (Exception error) {
            if (config != null) {
                try {
                    config.shutDown();
                } catch (Exception cleanupFailure) {
                    error.addSuppressed(cleanupFailure);
                }
            }
            if (naming != null) {
                try {
                    naming.shutDown();
                } catch (Exception cleanupFailure) {
                    error.addSuppressed(cleanupFailure);
                }
            }
            throw new IllegalStateException("Unable to create Nacos V2 control plane", error);
        }
    }

    /** SDK 凭据只写入独立属性，不拼入端点、目录元数据或错误文本。 */
    static Properties clientProperties(RegistryOptions options, List<String> endpoints, String namespace) {
        Properties properties = new Properties();
        properties.setProperty(PropertyKeyConst.SERVER_ADDR, String.join(",", endpoints));
        properties.setProperty(PropertyKeyConst.NAMESPACE, namespace);
        String username = options.providerOption(OPTION_USERNAME, "");
        String password = options.providerOption(OPTION_PASSWORD, "");
        if (!username.isBlank()) {
            properties.setProperty(PropertyKeyConst.USERNAME, username);
        }
        if (!password.isBlank()) {
            properties.setProperty(PropertyKeyConst.PASSWORD, password);
        }
        return properties;
    }

    private static String detectMemberHost() {
        try {
            String host = InetAddress.getLocalHost().getHostAddress();
            if (!NacosV2InstanceMapper.validHost(host)) {
                throw new IllegalStateException("Nacos local membership host is not usable");
            }
            return host;
        } catch (UnknownHostException error) {
            throw new IllegalStateException("Configure nacosMemberHost to the real local IP", error);
        }
    }
}
