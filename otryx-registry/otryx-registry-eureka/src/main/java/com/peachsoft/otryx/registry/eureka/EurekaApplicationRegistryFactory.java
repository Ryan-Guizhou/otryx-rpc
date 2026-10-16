package com.peachsoft.otryx.registry.eureka;

import com.peachsoft.otryx.registry.ApplicationMetadataBackend;
import com.peachsoft.otryx.registry.ApplicationRegistry;
import com.peachsoft.otryx.registry.ApplicationRegistryFactory;
import com.peachsoft.otryx.registry.RegistryOptions;
import com.peachsoft.otryx.registry.ServiceMappingCandidateSource;
import com.peachsoft.otryx.registry.ServiceMappingRepository;
import com.peachsoft.otryx.registry.http.HttpRegistryClient;
import com.peachsoft.otryx.spi.Extension;
import com.peachsoft.otryx.spi.ExtensionLoader;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Eureka V2 应用成员注册工厂，强制配置外置可靠 Manifest/Mapping Backend。
 *
 * <p>Eureka 仅管理 Member Lease；元数据由 Etcd/Nacos/Consul 的现有原子仓库持有。
 * 显式拒绝空后端、误用 Eureka 自身、缺失 SPI 或不支持 Candidate 查询的后端。
 * 不会将 Eureka 伪装成拥有本机 KV，也不会双写其他注册中心的成员身份。
 *
 * @Author OpenAI
 * @Version 1.0.0-SNAPSHOT
 * @CreateTime 2026/10/16 18:09
 */
@Extension(EurekaApplicationRegistryFactory.EXTENSION_NAME)
public final class EurekaApplicationRegistryFactory implements ApplicationRegistryFactory {

    /** 应用级注册的 SPI 名称。 */
    public static final String EXTENSION_NAME = "eureka";
    private static final Set<String> BACKENDS = Set.of("etcd", "nacos", "consul");
    private static final String OPTION_METADATA_BACKEND = "eurekaMetadataBackend";
    private static final String OPTION_METADATA_ENDPOINTS = "eurekaMetadataEndpoints";
    private static final String OPTION_METADATA_PREFIX = "eurekaMetadata.";
    private static final String OPTION_MEMBER_HOST = "eurekaMemberHost";
    private static final int DEFAULT_LEASE_SECONDS = 30;
    private static final int MIN_LEASE_SECONDS = 9;
    private static final int MAX_LEASE_SECONDS = 3600;
    private static final int DEFAULT_HEARTBEAT_SECONDS = 10;
    private static final int MIN_HEARTBEAT_SECONDS = 1;
    private static final int DEFAULT_POLL_MILLIS = 1000;
    private static final int MIN_POLL_MILLIS = 200;
    private static final int MAX_POLL_MILLIS = 60000;
    private static final int DEFAULT_TIMEOUT_MILLIS = 3000;
    private static final int MIN_TIMEOUT_MILLIS = 100;
    private static final int MAX_TIMEOUT_MILLIS = 60000;

    /** 创建应用级 Eureka Registry 工厂。 */
    public EurekaApplicationRegistryFactory() {
    }

    /**
     * 先校验外置元数据 Backend，再创建独立 Eureka Member Registry。
     *
     * @param options Eureka 端点、逻辑 Namespace、ACL/Lease 与显式 Metadata Backend 配置
     * @return 调用方必须关闭的应用级 Eureka 组合控制面
     */
    @Override
    public ApplicationRegistry create(RegistryOptions options) {
        Objects.requireNonNull(options, "options");
        String metadataType = options.providerOption(OPTION_METADATA_BACKEND, "");
        if (!BACKENDS.contains(metadataType)) {
            throw new IllegalArgumentException(
                    "Eureka requires eurekaMetadataBackend=etcd|nacos|consul");
        }
        String metadataEndpoints = options.providerOption(OPTION_METADATA_ENDPOINTS, "");
        if (metadataEndpoints.isBlank()) {
            throw new IllegalArgumentException("Eureka requires eurekaMetadataEndpoints");
        }
        List<String> endpoints = options.endpoints().isEmpty()
                ? List.of(EurekaProtocol.DEFAULT_ENDPOINT) : options.endpoints();
        int lease = number(options, EurekaProtocol.OPTION_LEASE_SECONDS,
                DEFAULT_LEASE_SECONDS, MIN_LEASE_SECONDS, MAX_LEASE_SECONDS);
        int renew = number(options, EurekaProtocol.OPTION_HEARTBEAT_SECONDS,
                DEFAULT_HEARTBEAT_SECONDS, MIN_HEARTBEAT_SECONDS, lease - 1);
        int poll = number(options, EurekaProtocol.OPTION_POLL_INTERVAL_MILLIS,
                DEFAULT_POLL_MILLIS, MIN_POLL_MILLIS, MAX_POLL_MILLIS);
        int timeout = number(options, EurekaProtocol.OPTION_REQUEST_TIMEOUT_MILLIS,
                DEFAULT_TIMEOUT_MILLIS, MIN_TIMEOUT_MILLIS, MAX_TIMEOUT_MILLIS);
        String user = options.providerOption(EurekaProtocol.OPTION_USERNAME, "");
        String password = options.providerOption(EurekaProtocol.OPTION_PASSWORD, "");
        if (user.isBlank() != password.isBlank()) {
            throw new IllegalArgumentException("Eureka Basic Auth requires both username and password");
        }
        String credential = user.isBlank() ? "" : EurekaProtocol.BASIC_AUTH_PREFIX
                + Base64.getEncoder().encodeToString(
                        (user + ":" + password).getBytes(StandardCharsets.UTF_8));
        String namespace = options.namespace().isBlank() ? "default" : options.namespace();
        String host = options.providerOption(OPTION_MEMBER_HOST, "");
        if (host.isBlank()) {
            host = localHost();
        }
        if (EurekaV2Identity.unspecified(host)) {
            throw new IllegalArgumentException("Eureka Consumer membership requires a real host");
        }
        Map<String, String> metadataOptions = new HashMap<>();
        options.providerOptions().forEach((key, value) -> {
            if (key.startsWith(OPTION_METADATA_PREFIX)) {
                String unprefixed = key.substring(OPTION_METADATA_PREFIX.length());
                if (unprefixed.isBlank()) {
                    throw new IllegalArgumentException("Empty Eureka metadata backend option");
                }
                metadataOptions.put(unprefixed, value);
            }
        });
        RegistryOptions backendOptions = RegistryOptions.fromCsv(
                metadataEndpoints, namespace, metadataOptions, options.observer());
        ApplicationRegistry metadataRegistry;
        try {
            metadataRegistry = ExtensionLoader.getLoader(ApplicationRegistryFactory.class)
                    .getExtension(metadataType).create(backendOptions);
        } catch (RuntimeException failure) {
            throw new IllegalStateException("Eureka metadata backend could not be created: "
                    + metadataType, failure);
        }
        HttpRegistryClient client = null;
        EurekaV2Executor executor = null;
        try {
            if (!(metadataRegistry instanceof ApplicationMetadataBackend provider)) {
                throw new IllegalStateException(
                        "Eureka metadata backend does not expose Manifest and Mapping repositories");
            }
            ServiceMappingRepository repository = provider.serviceMappingRepository();
            if (!(repository instanceof ServiceMappingCandidateSource source)) {
                throw new IllegalStateException(
                        "Eureka metadata backend cannot read service Mapping candidates");
            }
            client = new HttpRegistryClient(endpoints,
                    EurekaProtocol.AUTHORIZATION_HEADER, credential, Duration.ofMillis(timeout));
            executor = new EurekaV2Executor();
            return new EurekaApplicationRegistry(client, executor,
                    new EurekaV2Identity(namespace), metadataRegistry,
                    provider.manifestRepository(), repository, source, host,
                    Duration.ofSeconds(lease), Duration.ofSeconds(renew), Duration.ofMillis(poll));
        } catch (RuntimeException failure) {
            if (executor != null) {
                executor.close();
            }
            if (client != null) {
                client.close();
            }
            metadataRegistry.close();
            throw failure;
        }
    }

    private static String localHost() {
        try {
            String detected = InetAddress.getLocalHost().getHostAddress();
            if (EurekaV2Identity.unspecified(detected)) {
                throw new IllegalStateException("Eureka local Member host is unroutable");
            }
            return detected;
        } catch (UnknownHostException failure) {
            throw new IllegalStateException("Configure eurekaMemberHost with an actual local address", failure);
        }
    }

    private static int number(RegistryOptions options, String key, int fallback, int minimum, int maximum) {
        final int result;
        try {
            result = Integer.parseInt(options.providerOption(key, Integer.toString(fallback)));
        } catch (NumberFormatException failure) {
            throw new IllegalArgumentException("Invalid Eureka numeric option: " + key, failure);
        }
        if (result < minimum || result > maximum) {
            throw new IllegalArgumentException("Eureka option out of range: " + key);
        }
        return result;
    }
}
