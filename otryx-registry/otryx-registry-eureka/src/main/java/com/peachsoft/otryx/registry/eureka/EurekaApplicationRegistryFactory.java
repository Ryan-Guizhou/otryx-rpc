package com.peachsoft.otryx.registry.eureka;

import com.peachsoft.otryx.registry.ApplicationMetadataBackend;
import com.peachsoft.otryx.registry.ApplicationRegistry;
import com.peachsoft.otryx.registry.ApplicationRegistryFactory;
import com.peachsoft.otryx.registry.CredentialKind;
import com.peachsoft.otryx.registry.RegistryChannel;
import com.peachsoft.otryx.registry.RegistryFailureSanitizer;
import com.peachsoft.otryx.registry.RegistryOptions;
import com.peachsoft.otryx.registry.RegistrySecurityException;
import com.peachsoft.otryx.registry.RegistrySecurityFailure;
import com.peachsoft.otryx.registry.RegistrySecurityOptions;
import com.peachsoft.otryx.registry.RegistrySecuritySupport;
import com.peachsoft.otryx.registry.ServiceMappingCandidateSource;
import com.peachsoft.otryx.registry.ServiceMappingRepository;
import com.peachsoft.otryx.registry.http.HttpRegistryClient;
import com.peachsoft.otryx.spi.Extension;
import com.peachsoft.otryx.spi.ExtensionLoader;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
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

    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(
            EurekaApplicationRegistryFactory.class);

    /** 应用级注册的 SPI 名称。 */
    public static final String EXTENSION_NAME = "eureka";
    private static final String BACKEND_ETCD = "etcd";
    private static final String BACKEND_NACOS = "nacos";
    private static final String BACKEND_CONSUL = "consul";
    private static final Set<String> BACKENDS = Set.of(BACKEND_ETCD, BACKEND_NACOS, BACKEND_CONSUL);
    private static final String OPTION_METADATA_BACKEND = "eurekaMetadataBackend";
    private static final String OPTION_METADATA_ENDPOINTS = "eurekaMetadataEndpoints";
    private static final String OPTION_METADATA_PREFIX = "eurekaMetadata.";
    private static final String OPTION_MEMBER_HOST = "eurekaMemberHost";
    private static final Set<String> MEMBER_OPTIONS = Set.of(
            OPTION_METADATA_BACKEND, OPTION_METADATA_ENDPOINTS, OPTION_MEMBER_HOST,
            EurekaProtocol.OPTION_LEASE_SECONDS, EurekaProtocol.OPTION_HEARTBEAT_SECONDS,
            EurekaProtocol.OPTION_POLL_INTERVAL_MILLIS, EurekaProtocol.OPTION_REQUEST_TIMEOUT_MILLIS);
    private static final Map<String, Set<String>> METADATA_OPTIONS = Map.of(
            BACKEND_ETCD, Set.of("leaseTtlSeconds"),
            BACKEND_NACOS, Set.of("nacosGroup", "nacosCluster", "nacosMemberHost"),
            BACKEND_CONSUL, Set.of("consulTtlSeconds", "consulHeartbeatSeconds",
                    "consulPollIntervalMillis", "consulRequestTimeoutMillis", "consulEnterpriseNamespace"));
    private static final Set<CredentialKind> CREDENTIALS = Set.of(CredentialKind.NONE, CredentialKind.USER_PASSWORD);
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
        if (options.securityProfile() == com.peachsoft.otryx.api.RpcSecurityProfile.UNSPECIFIED) {
            throw new RegistrySecurityException(RegistrySecurityFailure.UNSUPPORTED_PROTECTION);
        }
        String metadataType = options.providerOption(OPTION_METADATA_BACKEND, "");
        if (!BACKENDS.contains(metadataType)) {
            throw new IllegalArgumentException(
                    "Eureka requires eurekaMetadataBackend=etcd|nacos|consul");
        }
        Set<String> allowed = new HashSet<>(MEMBER_OPTIONS);
        METADATA_OPTIONS.get(metadataType).forEach(key -> allowed.add(OPTION_METADATA_PREFIX + key));
        RegistrySecuritySupport.requireOptions(options, allowed, true);
        String metadataEndpoints = options.providerOption(OPTION_METADATA_ENDPOINTS, "");
        if (metadataEndpoints.isBlank()) {
            throw new IllegalArgumentException("Eureka requires eurekaMetadataEndpoints");
        }
        List<String> endpoints = options.endpoints().isEmpty()
                ? List.of(EurekaProtocol.DEFAULT_ENDPOINT) : options.endpoints();
        RegistrySecuritySupport.requireTransport(options, endpoints, CREDENTIALS);
        int lease = number(options, EurekaProtocol.OPTION_LEASE_SECONDS,
                DEFAULT_LEASE_SECONDS, MIN_LEASE_SECONDS, MAX_LEASE_SECONDS);
        int renew = number(options, EurekaProtocol.OPTION_HEARTBEAT_SECONDS,
                DEFAULT_HEARTBEAT_SECONDS, MIN_HEARTBEAT_SECONDS, lease - 1);
        int poll = number(options, EurekaProtocol.OPTION_POLL_INTERVAL_MILLIS,
                DEFAULT_POLL_MILLIS, MIN_POLL_MILLIS, MAX_POLL_MILLIS);
        int timeout = number(options, EurekaProtocol.OPTION_REQUEST_TIMEOUT_MILLIS,
                DEFAULT_TIMEOUT_MILLIS, MIN_TIMEOUT_MILLIS, MAX_TIMEOUT_MILLIS);
        String credential = credential(options);
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
        RegistryOptions parsed = RegistryOptions.fromCsv(metadataEndpoints, namespace, metadataOptions);
        RegistryOptions backendOptions = new RegistryOptions(
                parsed.endpoints(), namespace, metadataOptions, options.observer(), options.securityProfile(),
                options.metadataSecurity().orElseGet(RegistrySecurityOptions::none), Optional.empty());
        HttpRegistryClient client = new HttpRegistryClient(endpoints,
                credential.isEmpty() ? "" : EurekaProtocol.AUTHORIZATION_HEADER, credential,
                Duration.ofMillis(timeout), HttpRegistryClient.DEFAULT_MAX_RESPONSE_BYTES, options.security().tls());
        ApplicationRegistry metadataRegistry = null;
        EurekaV2Executor executor = null;
        try {
            metadataRegistry = ExtensionLoader.getLoader(ApplicationRegistryFactory.class)
                    .getExtension(metadataType).create(backendOptions);
            if (!(metadataRegistry instanceof ApplicationMetadataBackend provider)) {
                throw new RegistrySecurityException(RegistrySecurityFailure.UNSUPPORTED_PROTECTION);
            }
            ServiceMappingRepository repository = provider.serviceMappingRepository();
            if (!(repository instanceof ServiceMappingCandidateSource source)) {
                throw new RegistrySecurityException(RegistrySecurityFailure.UNSUPPORTED_PROTECTION);
            }
            executor = new EurekaV2Executor();
            return new EurekaApplicationRegistry(client, executor,
                    new EurekaV2Identity(namespace), metadataRegistry,
                    provider.manifestRepository(), repository, source, host,
                    Duration.ofSeconds(lease), Duration.ofSeconds(renew), Duration.ofMillis(poll),
                    RegistrySecuritySupport.installed(options.security(), namespace, Optional.empty(),
                            RegistryChannel.MEMBERSHIP));
        } catch (RuntimeException failure) {
            closeFailed(executor);
            closeFailed(metadataRegistry);
            closeFailed(client);
            Throwable safe = RegistryFailureSanitizer.sanitize(failure);
            if (safe instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new IllegalStateException("Eureka metadata backend could not be created");
        }
    }

    private static void closeFailed(AutoCloseable resource) {
        if (resource != null) {
            try {
                resource.close();
            } catch (Exception cleanupFailure) {
                if (cleanupFailure instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                LOG.debug("Registry construction cleanup failed: {}", cleanupFailure.getClass().getSimpleName());
            }
        }
    }

    private static String credential(RegistryOptions options) {
        if (options.security().credentials().kind() == CredentialKind.NONE) {
            return "";
        }
        String username = options.security().credentials().username();
        if (username.indexOf(':') >= 0) {
            throw new RegistrySecurityException(RegistrySecurityFailure.UNSUPPORTED_PROTECTION);
        }
        char[] password = options.security().credentials().password();
        byte[] encoded = null;
        try {
            encoded = (username + ":" + new String(password)).getBytes(StandardCharsets.UTF_8);
            return EurekaProtocol.BASIC_AUTH_PREFIX + Base64.getEncoder().encodeToString(encoded);
        } finally {
            Arrays.fill(password, '\0');
            if (encoded != null) {
                Arrays.fill(encoded, (byte) 0);
            }
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
            throw new IllegalStateException("Configure eurekaMemberHost with an actual local address");
        }
    }

    private static int number(RegistryOptions options, String key, int fallback, int minimum, int maximum) {
        final int result;
        try {
            result = Integer.parseInt(options.providerOption(key, Integer.toString(fallback)));
        } catch (NumberFormatException failure) {
            throw new IllegalArgumentException("Invalid Eureka numeric option: " + key);
        }
        if (result < minimum || result > maximum) {
            throw new IllegalArgumentException("Eureka option out of range: " + key);
        }
        return result;
    }
}
