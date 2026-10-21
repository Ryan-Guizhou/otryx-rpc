package com.peachsoft.otryx.registry.nacos;

import com.alibaba.nacos.api.NacosFactory;
import com.alibaba.nacos.api.PropertyKeyConst;
import com.alibaba.nacos.api.naming.NamingService;
import com.peachsoft.otryx.api.ApplicationKey;
import com.peachsoft.otryx.api.RpcSecurityProfile;
import com.peachsoft.otryx.registry.ApplicationRegistry;
import com.peachsoft.otryx.registry.ApplicationRegistryFactory;
import com.peachsoft.otryx.registry.CredentialKind;
import com.peachsoft.otryx.registry.RegistryChannel;
import com.peachsoft.otryx.registry.RegistryOptions;
import com.peachsoft.otryx.registry.RegistrySecurityException;
import com.peachsoft.otryx.registry.RegistrySecurityFailure;
import com.peachsoft.otryx.registry.RegistrySecuritySupport;
import com.peachsoft.otryx.spi.Extension;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;

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
    private static final Set<String> ALLOWED_OPTIONS = Set.of(
            OPTION_GROUP, OPTION_CLUSTER, OPTION_MEMBER_HOST);
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
        requireSecurity(options);
        List<String> endpoints = options.endpoints().isEmpty()
                ? List.of(DEFAULT_ENDPOINT) : options.endpoints();
        validateEndpoints(endpoints);
        String namespace = options.namespace().isBlank()
                ? DEFAULT_NAMESPACE : options.namespace();
        namespace = new ApplicationKey(namespace, "registry").namespace();
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
        NacosConfigOperations config = null;
        try {
            naming = NacosFactory.createNamingService(props);
            config = new NacosConfigClient(props);
            return new NacosApplicationRegistry(naming, config, namespace, group, cluster,
                    host, options.observer(), RegistrySecuritySupport.installed(
                            options.security(), namespace, Optional.of(group),
                            RegistryChannel.MEMBERSHIP, RegistryChannel.MANIFEST, RegistryChannel.MAPPING));
        } catch (Exception error) {
            if (config != null) {
                try {
                    config.shutDown();
                } catch (Exception cleanupFailure) {
                    // 仅清理已创建资源，不把外部清理异常加入可观测 cause/suppressed。
                }
            }
            if (naming != null) {
                try {
                    naming.shutDown();
                } catch (Exception cleanupFailure) {
                    // 仅清理已创建资源，不把外部清理异常加入可观测 cause/suppressed。
                }
            }
            Throwable safe = NacosFailures.normalize(error);
            if (safe instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new IllegalStateException("Unable to create Nacos V2 control plane");
        }
    }

    /** SDK 凭据只写入独立属性，不拼入端点、目录元数据或错误文本。 */
    static Properties clientProperties(RegistryOptions options, List<String> endpoints, String namespace) {
        requireSecurity(options);
        validateEndpoints(endpoints);
        Properties properties = new Properties();
        properties.setProperty(PropertyKeyConst.SERVER_ADDR, String.join(",", endpoints));
        properties.setProperty(PropertyKeyConst.NAMESPACE, namespace);
        if (options.security().credentials().kind() == CredentialKind.USER_PASSWORD) {
            properties.setProperty(PropertyKeyConst.USERNAME, options.security().credentials().username());
            char[] password = options.security().credentials().password();
            try {
                properties.setProperty(PropertyKeyConst.PASSWORD, new String(password));
            } finally {
                Arrays.fill(password, '\0');
            }
        }
        return properties;
    }

    /** 当前 SDK 登录 HTTP 与 gRPC TLS 尚无完整隔离证明，所有 TLS/Network 意图在 SDK 前拒绝。 */
    static void requireSecurity(RegistryOptions options) {
        RegistrySecuritySupport.requireOptions(options, ALLOWED_OPTIONS, false);
        if (options.securityProfile() == RpcSecurityProfile.NETWORK_MTLS
                || options.security().tls().isPresent()
                || !(options.security().credentials().kind() == CredentialKind.NONE
                || options.security().credentials().kind() == CredentialKind.USER_PASSWORD)) {
            throw new RegistrySecurityException(RegistrySecurityFailure.UNSUPPORTED_PROTECTION);
        }
    }

    private static void validateEndpoints(List<String> endpoints) {
        if (endpoints.isEmpty() || endpoints.size() > 16) {
            throw new IllegalArgumentException("Invalid Nacos endpoint count");
        }
        for (String endpoint : endpoints) {
            if (endpoint == null || endpoint.length() > 2048 || endpoint.contains("@")
                    || endpoint.contains("?") || endpoint.contains("#")
                    || endpoint.startsWith("https:") || endpoint.contains("/")) {
                throw new IllegalArgumentException("Invalid Nacos development endpoint");
            }
            try {
                java.net.URI uri = java.net.URI.create("http://" + endpoint);
                if (uri.getHost() == null || uri.getPort() < 1 || uri.getPort() > 65535) {
                    throw new IllegalArgumentException("Invalid Nacos development endpoint");
                }
            } catch (IllegalArgumentException invalid) {
                throw new IllegalArgumentException("Invalid Nacos development endpoint");
            }
        }
    }

    private static String detectMemberHost() {
        try {
            String host = InetAddress.getLocalHost().getHostAddress();
            if (!NacosV2InstanceMapper.validHost(host)) {
                throw new IllegalStateException("Nacos local membership host is not usable");
            }
            return host;
        } catch (UnknownHostException error) {
            throw new IllegalStateException("Configure nacosMemberHost to the real local IP");
        }
    }
}
