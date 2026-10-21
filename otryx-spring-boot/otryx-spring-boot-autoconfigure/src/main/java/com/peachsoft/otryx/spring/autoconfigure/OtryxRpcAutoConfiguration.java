package com.peachsoft.otryx.spring.autoconfigure;

import com.peachsoft.otryx.api.ApplicationKey;
import com.peachsoft.otryx.api.RpcSecurityProfile;
import com.peachsoft.otryx.codec.RpcCodecRegistry;
import com.peachsoft.otryx.codec.fory.ForyRpcCodec;
import com.peachsoft.otryx.codec.fory.ForyRpcSecurityOptions;
import com.peachsoft.otryx.core.ApplicationMembershipCoordinator;
import com.peachsoft.otryx.core.OtryxRpcClient;
import com.peachsoft.otryx.core.OtryxRpcServer;
import com.peachsoft.otryx.core.RpcAuthorizer;
import com.peachsoft.otryx.core.RpcClientResilienceOptions;
import com.peachsoft.otryx.core.RpcProviderExecutionOptions;
import com.peachsoft.otryx.interceptor.RpcInterceptor;
import com.peachsoft.otryx.loadbalance.LoadBalancer;
import com.peachsoft.otryx.routing.RpcRouter;
import com.peachsoft.otryx.observability.RpcMetadataPropagator;
import com.peachsoft.otryx.observability.RpcObserver;
import com.peachsoft.otryx.observability.RpcTracingBridge;
import com.peachsoft.otryx.proxy.ProxyFactory;
import com.peachsoft.otryx.registry.ApplicationMetadataBackend;
import com.peachsoft.otryx.registry.ApplicationRegistry;
import com.peachsoft.otryx.registry.ApplicationRegistryFactory;
import com.peachsoft.otryx.registry.ApplicationServiceResolver;
import com.peachsoft.otryx.registry.ManifestSnapshotCache;
import com.peachsoft.otryx.registry.ServiceMappingCache;
import com.peachsoft.otryx.registry.RegistryOptions;
import com.peachsoft.otryx.spi.ExtensionLoader;
import com.peachsoft.otryx.spring.lifecycle.OtryxRpcServerLifecycle;
import com.peachsoft.otryx.spring.processor.OtryxRpcReferenceBeanPostProcessor;
import com.peachsoft.otryx.spring.processor.OtryxRpcServiceBeanDefinitionValidator;
import com.peachsoft.otryx.spring.processor.OtryxRpcServiceBeanPostProcessor;
import com.peachsoft.otryx.spring.runtime.OtryxRpcApplicationIdentity;
import com.peachsoft.otryx.spring.runtime.OtryxRpcRuntimeCoordinator;
import com.peachsoft.otryx.transport.RpcTransportFactory;
import com.peachsoft.otryx.transport.RpcTransportOptions;
import com.peachsoft.otryx.transport.RpcTransportAdmissionOptions;
import com.peachsoft.otryx.transport.RpcTransportSecurityOptions;
import com.peachsoft.otryx.transport.RpcTransportIdentityOptions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.core.env.Environment;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.context.properties.bind.BindHandler;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.PropertySourcesPlaceholdersResolver;
import org.springframework.boot.context.properties.bind.UnboundConfigurationPropertiesException;
import org.springframework.boot.context.properties.bind.handler.NoUnboundElementsBindHandler;
import org.springframework.boot.context.properties.source.ConfigurationPropertyName;
import org.springframework.boot.context.properties.source.ConfigurationPropertySource;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Lazy;

/**
 * OTRYX RPC Spring Boot 自动配置。
 *
 * @Author Ryan
 * @Version 1.0.0
 * @CreateTime 2026/9/23 10:51
 */
@AutoConfiguration
@ConditionalOnClass(OtryxRpcClient.class)
@ConditionalOnProperty(
        prefix = OtryxRpcAutoConfiguration.PROPERTY_PREFIX,
        name = OtryxRpcAutoConfiguration.PROPERTY_ENABLED,
        havingValue = OtryxRpcAutoConfiguration.PROPERTY_ENABLED_VALUE,
        matchIfMissing = true)
@EnableConfigurationProperties(OtryxRpcProperties.class)
public class OtryxRpcAutoConfiguration {

    static final String PROPERTY_PREFIX = "otryx.rpc";
    static final String PROPERTY_ENABLED = "enabled";
    static final String PROPERTY_ENABLED_VALUE = "true";
    private static final String DESTROY_METHOD_CLOSE = "close";
    private static final String CONFIG_REGISTRY_TYPE = "otryx.rpc.registry.type";
    private static final String CONFIG_TRANSPORT_TYPE = "otryx.rpc.transport.type";
    private static final String CONFIG_APPLICATION_NAME = "otryx.rpc.registry.application-name";
    private static final String SPRING_APPLICATION_NAME = "spring.application.name";
    private static final String DEFAULT_APPLICATION_NAMESPACE = "default";
    private static final String DEFAULT_APPLICATION_IDENTITY_BEAN = "peachRpcApplicationIdentity";
    private static final String DEFAULT_CODEC_REGISTRY_BEAN = "peachRpcCodecRegistry";
    private static final String ETCD_REGISTRY_TYPE = "etcd";
    private static final String NACOS_REGISTRY_TYPE = "nacos";
    private static final String CONSUL_REGISTRY_TYPE = "consul";
    private static final String EUREKA_REGISTRY_TYPE = "eureka";
    private static final Duration MAPPING_REFRESH_RETRY = Duration.ofMillis(200);
    private static final List<ConfigurationPropertyName> DELEGATED_PROPERTY_PREFIXES = List.of(
            ConfigurationPropertyName.of("otryx.rpc.observability"),
            ConfigurationPropertyName.of("otryx.rpc.quickstart"));
    private static final String PROVIDER_OPTION_LEASE_TTL_SECONDS = "leaseTtlSeconds";
    private static final String PROVIDER_OPTION_NACOS_GROUP = "nacosGroup";
    private static final String PROVIDER_OPTION_NACOS_CLUSTER = "nacosCluster";
    private static final String PROVIDER_OPTION_NACOS_MEMBER_HOST = "nacosMemberHost";
    private static final String PROVIDER_OPTION_CONSUL_ENTERPRISE_NAMESPACE = "consulEnterpriseNamespace";
    private static final String PROVIDER_OPTION_CONSUL_TTL_SECONDS = "consulTtlSeconds";
    private static final String PROVIDER_OPTION_CONSUL_HEARTBEAT_SECONDS = "consulHeartbeatSeconds";
    private static final String PROVIDER_OPTION_CONSUL_POLL_INTERVAL_MILLIS = "consulPollIntervalMillis";
    private static final String PROVIDER_OPTION_CONSUL_REQUEST_TIMEOUT_MILLIS = "consulRequestTimeoutMillis";
    private static final String PROVIDER_OPTION_EUREKA_LEASE_SECONDS = "eurekaLeaseSeconds";
    private static final String PROVIDER_OPTION_EUREKA_HEARTBEAT_SECONDS = "eurekaHeartbeatSeconds";
    private static final String PROVIDER_OPTION_EUREKA_POLL_INTERVAL_MILLIS = "eurekaPollIntervalMillis";
    private static final String PROVIDER_OPTION_EUREKA_REQUEST_TIMEOUT_MILLIS = "eurekaRequestTimeoutMillis";
    private static final String PROVIDER_OPTION_EUREKA_METADATA_BACKEND = "eurekaMetadataBackend";
    private static final String PROVIDER_OPTION_EUREKA_METADATA_ENDPOINTS = "eurekaMetadataEndpoints";
    private static final String PROVIDER_OPTION_EUREKA_MEMBER_HOST = "eurekaMemberHost";
    private static final String PROVIDER_OPTION_EUREKA_METADATA_PREFIX = "eurekaMetadata.";

    /**
     * 创建 OTRYX RPC 自动配置。
     */
    public OtryxRpcAutoConfiguration() {
    }

    /**
     * 在创建任何 RPC 资源之前拒绝无法绑定的 Starter 配置键。
     *
     * <p>不提前实例化用户的 lazy Bean。观测 Adapter 与
     * Quickstart 独立拥有的配置子树交由对应模块校验；其余配置用实际属性模型
     * 严格试绑定。试绑定不调用初始化钩子，也不创建 Registry 或 Transport。
     * 默认应用身份复用实际 Bean 的名称和实例 ID 校验，预检不生成进程实例 ID。
     *
     * @param environment 当前应用的属性源及占位符解析器
     * @return 无网络副作用的启动校验器
     */
    @Bean
    public static BeanFactoryPostProcessor peachRpcApplicationConfigurationValidator(Environment environment) {
        return beanFactory -> {
            OtryxRpcProperties properties = validateConfigurationKeys(environment);
            if (!properties.isEnabled()) {
                return;
            }
            OtryxRpcStartupDiagnostics.validateExplicitProfile(properties);
            if (properties.getSecurityProfile() == RpcSecurityProfile.NETWORK_MTLS
                    && properties.getServer().isEnabled()
                    && beanFactory.getBeanNamesForType(RpcAuthorizer.class, true, false).length == 0) {
                throw new IllegalStateException("NETWORK_MTLS requires an explicit RpcAuthorizer bean");
            }
            OtryxRpcStartupDiagnostics.validate(properties);
            if (beanFactory.containsBeanDefinition(DEFAULT_CODEC_REGISTRY_BEAN)) {
                var definition = beanFactory.getBeanDefinition(DEFAULT_CODEC_REGISTRY_BEAN);
                String factory = definition.getFactoryBeanName();
                if (DEFAULT_CODEC_REGISTRY_BEAN.equals(definition.getFactoryMethodName())
                        && factory != null && beanFactory.getType(factory, false) == OtryxRpcAutoConfiguration.class) {
                    OtryxRpcStartupDiagnostics.validateDefaultCodecProfile(properties);
                }
            }
            if (beanFactory.containsBeanDefinition(DEFAULT_APPLICATION_IDENTITY_BEAN)) {
                var definition = beanFactory.getBeanDefinition(DEFAULT_APPLICATION_IDENTITY_BEAN);
                String factory = definition.getFactoryBeanName();
                if (DEFAULT_APPLICATION_IDENTITY_BEAN.equals(definition.getFactoryMethodName())
                        && factory != null && beanFactory.getType(factory, false) == OtryxRpcAutoConfiguration.class) {
                    validateDefaultIdentity(properties.getRegistry(), environment);
                }
            }
        };
    }

    private static OtryxRpcProperties validateConfigurationKeys(Environment environment) {
        List<ConfigurationPropertySource> sources = new ArrayList<>();
        ConfigurationPropertySources.get(environment).forEach(source -> sources.add(source.filter(name ->
                DELEGATED_PROPERTY_PREFIXES.stream().noneMatch(prefix ->
                        prefix.equals(name) || prefix.isAncestorOf(name)))));
        Binder binder = new Binder(sources, new PropertySourcesPlaceholdersResolver(environment));
        try {
            return binder.bind(PROPERTY_PREFIX, Bindable.of(OtryxRpcProperties.class),
                    new NoUnboundElementsBindHandler(BindHandler.DEFAULT))
                    .orElseGet(OtryxRpcProperties::new);
        } catch (BindException failure) {
            Throwable cause = failure;
            while (cause.getCause() != null && !(cause instanceof UnboundConfigurationPropertiesException)) {
                cause = cause.getCause();
            }
            if (cause instanceof UnboundConfigurationPropertiesException unbound) {
                String keys = unbound.getUnboundProperties().stream()
                        .map(property -> property.getName().toString()).sorted().collect(Collectors.joining(", "));
                throw new IllegalStateException("Unsupported OTRYX RPC configuration keys: " + keys
                        + ". Check spelling and remove interface-level registration settings; "
                        + "application-level registration uses " + CONFIG_APPLICATION_NAME);
            }
            throw new IllegalStateException("Invalid OTRYX RPC configuration at "
                    + failure.getName() + ": binding rejected");
        }
    }

    /**
     * 使用唯一 ApplicationRegistryFactory SPI 创建 V2 注册中心；禁止旧的 ServiceKey 工厂。
     *
     * @param properties 应用级 Registry Adapter 配置
     * @param observerProvider 控制面 Observer 提供器
     * @param authorizerProvider 实际授权器，在默认 Registry 工厂之前检查缺失策略
     * @return Provider、Consumer、BOTH 共享的单一 Application Registry
     */
    @Bean(destroyMethod = DESTROY_METHOD_CLOSE)
    @ConditionalOnMissingBean
    public ApplicationRegistry peachRpcRegistry(
            OtryxRpcProperties properties,
            ObjectProvider<RpcObserver> observerProvider,
            ObjectProvider<RpcAuthorizer> authorizerProvider) {
        OtryxRpcProperties.Registry registry = properties.getRegistry();
        if (properties.getSecurityProfile() == RpcSecurityProfile.NETWORK_MTLS
                && properties.getServer().isEnabled() && authorizerProvider.getIfAvailable() == null) {
            throw new IllegalStateException("NETWORK_MTLS requires an explicit RpcAuthorizer bean");
        }
        OtryxRpcStartupDiagnostics.validateRegistryBackend(registry, properties.getSecurityProfile());
        ApplicationRegistryFactory factory = requireExtension(
                ApplicationRegistryFactory.class, CONFIG_REGISTRY_TYPE, registry.getType());
        List<String> endpoints = RegistryOptions.fromCsv(
                registry.getEndpoints(), namespace(registry), Map.of()).endpoints();
        return factory.create(new RegistryOptions(endpoints, namespace(registry), registryProviderOptions(registry),
                RpcObserver.composite(observerProvider.orderedStream().toList()),
                properties.getSecurityProfile(), registry.getSecurity().toOptions(),
                Optional.ofNullable(registry.getMetadataSecurity())
                        .map(OtryxRpcRegistrySecurityProperties::toOptions)));
    }

    /** 只向实际选中的 Adapter 传递其非安全选项；Factory 仍独立校验所有键。 */
    static Map<String, String> registryProviderOptions(OtryxRpcProperties.Registry registry) {
        Map<String, String> options = new HashMap<>();
        switch (registry.getType()) {
            case ETCD_REGISTRY_TYPE -> options.put(
                    PROVIDER_OPTION_LEASE_TTL_SECONDS, Long.toString(registry.getLeaseTtlSeconds()));
            case NACOS_REGISTRY_TYPE -> {
                options.put(PROVIDER_OPTION_NACOS_GROUP, registry.getNacos().getGroup());
                options.put(PROVIDER_OPTION_NACOS_CLUSTER, registry.getNacos().getCluster());
                options.put(PROVIDER_OPTION_NACOS_MEMBER_HOST, registry.getNacos().getMemberHost());
            }
            case CONSUL_REGISTRY_TYPE -> {
                options.put(PROVIDER_OPTION_CONSUL_ENTERPRISE_NAMESPACE,
                        registry.getConsul().getEnterpriseNamespace());
                options.put(PROVIDER_OPTION_CONSUL_TTL_SECONDS, Long.toString(registry.getConsul().getTtlSeconds()));
                options.put(PROVIDER_OPTION_CONSUL_HEARTBEAT_SECONDS,
                        Long.toString(registry.getConsul().getHeartbeatSeconds()));
                options.put(PROVIDER_OPTION_CONSUL_POLL_INTERVAL_MILLIS,
                        Long.toString(registry.getConsul().getPollIntervalMillis()));
                options.put(PROVIDER_OPTION_CONSUL_REQUEST_TIMEOUT_MILLIS,
                        Long.toString(registry.getConsul().getRequestTimeoutMillis()));
            }
            case EUREKA_REGISTRY_TYPE -> {
                options.put(PROVIDER_OPTION_EUREKA_LEASE_SECONDS,
                        Long.toString(registry.getEureka().getLeaseSeconds()));
                options.put(PROVIDER_OPTION_EUREKA_HEARTBEAT_SECONDS,
                        Long.toString(registry.getEureka().getHeartbeatSeconds()));
                options.put(PROVIDER_OPTION_EUREKA_POLL_INTERVAL_MILLIS,
                        Long.toString(registry.getEureka().getPollIntervalMillis()));
                options.put(PROVIDER_OPTION_EUREKA_REQUEST_TIMEOUT_MILLIS,
                        Long.toString(registry.getEureka().getRequestTimeoutMillis()));
                options.put(PROVIDER_OPTION_EUREKA_METADATA_BACKEND, registry.getEureka().getMetadataBackend());
                options.put(PROVIDER_OPTION_EUREKA_METADATA_ENDPOINTS, registry.getEureka().getMetadataEndpoints());
                options.put(PROVIDER_OPTION_EUREKA_MEMBER_HOST, registry.getEureka().getMemberHost());
                registry.getEureka().getMetadataOptions().forEach((key, value) ->
                        options.put(PROVIDER_OPTION_EUREKA_METADATA_PREFIX + key, value));
            }
            default -> {
                // Memory and user adapters receive no unrelated built-in backend settings.
            }
        }
        return Map.copyOf(options);
    }

    /**
     * 获取不可变进程级身份；默认 applicationName 来自 spring.application.name。
     *
     * @param properties 明确的 Registry 身份覆盖
     * @param environment Spring 环境，未给出应用名称时必须启动失败
     * @return 供 Provider、Consumer 和 BOTH 共用的进程身份
     */
    @Bean
    @ConditionalOnMissingBean
    public OtryxRpcApplicationIdentity peachRpcApplicationIdentity(
            OtryxRpcProperties properties, Environment environment) {
        OtryxRpcProperties.Registry registry = properties.getRegistry();
        ApplicationKey application = defaultApplicationKey(registry, environment);
        String id = registry.getInstanceId();
        if (id == null || id.isBlank()) {
            id = UUID.randomUUID().toString();
        }
        return new OtryxRpcApplicationIdentity(application, id);
    }

    /** 只校验明确配置的身份；缺省实例 ID 仍仅由正式 Bean 创建时生成。 */
    private static void validateDefaultIdentity(OtryxRpcProperties.Registry registry, Environment environment) {
        ApplicationKey application = defaultApplicationKey(registry, environment);
        String id = registry.getInstanceId();
        if (id != null && !id.isBlank()) {
            new OtryxRpcApplicationIdentity(application, id);
        }
    }

    /** 预检与正式 Bean 共用名称优先级、规范化和 Core 的 ApplicationKey 校验。 */
    private static ApplicationKey defaultApplicationKey(
            OtryxRpcProperties.Registry registry, Environment environment) {
        String name = registry.getApplicationName();
        if (name == null || name.isBlank()) {
            name = environment.getProperty(SPRING_APPLICATION_NAME, "");
        }
        if (name.isBlank()) {
            throw new IllegalStateException(
                    "Configure " + SPRING_APPLICATION_NAME + " or " + CONFIG_APPLICATION_NAME);
        }
        return new ApplicationKey(namespace(registry), name);
    }

    private static String namespace(OtryxRpcProperties.Registry registry) {
        String value = registry.getNamespace();
        return value == null || value.isBlank() ? DEFAULT_APPLICATION_NAMESPACE : value.trim();
    }

    private static ApplicationMetadataBackend requireMetadata(ApplicationRegistry registry) {
        if (!(registry instanceof ApplicationMetadataBackend backend)) {
            throw new IllegalStateException(
                    "ApplicationRegistry must implement ApplicationMetadataBackend "
                            + "for Manifest and Mapping repositories");
        }
        Objects.requireNonNull(backend.manifestRepository(), "ApplicationMetadataBackend.manifestRepository");
        Objects.requireNonNull(backend.serviceMappingRepository(),
                "ApplicationMetadataBackend.serviceMappingRepository");
        return backend;
    }

    /**
     * 创建 Spring 全进程共享的一份应用成员协调器，只有实际 Consumer/Provider 被创建时入册。
     *
     * @param registry 实际 Member Registry
     * @param identity 应用级进程身份
     * @param properties 操作超时配置
     * @return 有界成员发布与撤销协调器
     */
    @Bean(destroyMethod = DESTROY_METHOD_CLOSE)
    @ConditionalOnMissingBean
    public ApplicationMembershipCoordinator peachRpcMembershipCoordinator(
            ApplicationRegistry registry, OtryxRpcApplicationIdentity identity,
            OtryxRpcProperties properties) {
        ApplicationMetadataBackend metadata = requireMetadata(registry);
        return new ApplicationMembershipCoordinator(identity.application(), identity.instanceId(),
                registry.registrar().orElseThrow(() ->
                        new IllegalStateException("Application Registry does not support membership registration")),
                metadata.manifestRepository(), metadata.serviceMappingRepository(),
                properties.getRegistry().getControlPlaneTimeout(),
                properties.getRegistry().getMemberAttributes());
    }

    /**
     * 创建有界 ServiceMapping 缓存，只有显式控制面刷新才能访问后端。
     *
     * @param registry 拥有 Mapping 仓库的应用级注册中心
     * @param properties Consumer 新鲜度配置
     * @return 本地 ServiceMapping 控制面缓存
     */
    @Bean(destroyMethod = DESTROY_METHOD_CLOSE)
    @ConditionalOnMissingBean
    public ServiceMappingCache peachRpcMappingCache(
            ApplicationRegistry registry, OtryxRpcProperties properties) {
        ApplicationMetadataBackend metadata = requireMetadata(registry);
        Duration stale = properties.getClient().getDirectoryMaxStaleness();
        return new ServiceMappingCache(metadata.serviceMappingRepository(),
                stale, MAPPING_REFRESH_RETRY);
    }

    /**
     * 创建受限的不可变 Manifest 缓存。
     *
     * @param registry 拥有 Manifest 仓库的应用级注册中心
     * @return 防止无界修订号膨胀的 ManifestSnapshotCache
     */
    @Bean(destroyMethod = DESTROY_METHOD_CLOSE)
    @ConditionalOnMissingBean
    public ManifestSnapshotCache peachRpcManifestCache(ApplicationRegistry registry) {
        return new ManifestSnapshotCache(requireMetadata(registry).manifestRepository());
    }

    /**
     * 应用级服务解析采用 Mapping→Eureka/Etcd/Nacos/Consul Member→Manifest→Schema。
     *
     * @param mappingCache 映射缓存
     * @param registry 唯一权威成员目录
     * @param manifestCache 不可变 Manifest 缓存
     * @param properties Consumer 最长陈旧窗口
     * @return 非 EventLoop 阻塞的 V2 Resolver
     */
    @Bean(destroyMethod = DESTROY_METHOD_CLOSE)
    @ConditionalOnMissingBean
    public ApplicationServiceResolver peachRpcApplicationResolver(
            ServiceMappingCache mappingCache, ApplicationRegistry registry,
            ManifestSnapshotCache manifestCache, OtryxRpcProperties properties) {
        return new ApplicationServiceResolver(
                mappingCache, registry, manifestCache, properties.getClient().getDirectoryMaxStaleness());
    }

    /**
     * 创建 Codec 注册表。
     *
     * @param properties Codec 安全配置
     * @return Codec 注册表
     */
    @Bean
    @ConditionalOnMissingBean
    public RpcCodecRegistry peachRpcCodecRegistry(OtryxRpcProperties properties) {
        OtryxRpcStartupDiagnostics.validateDefaultCodecProfile(properties);
        OtryxRpcProperties.ForySecurity security = properties.getCodec().getFory();
        ForyRpcSecurityOptions options = new ForyRpcSecurityOptions(
                security.getMode(),
                java.util.Set.copyOf(security.getAllowedClassPatterns()),
                security.getMaxDepth(),
                security.getMaxGraphMemoryBytes(),
                security.getMaxPayloadBytes());
        RpcCodecRegistry registry = RpcCodecRegistry.fromSpi()
                .withReplacement(new ForyRpcCodec(options));
        OtryxRpcStartupDiagnostics.reportDefaultCodec(security.getMode());
        return registry;
    }

    /**
     * 创建 Transport 工厂。
     *
     * @param properties OTRYX RPC 配置
     * @return Transport 工厂
     */
    @Bean
    @ConditionalOnMissingBean
    public RpcTransportFactory peachRpcTransportFactory(OtryxRpcProperties properties) {
        return requireExtension(
                RpcTransportFactory.class,
                CONFIG_TRANSPORT_TYPE,
                properties.getTransport().getType());
    }

    /**
     * 创建 Transport 运行参数。
     *
     * @param properties OTRYX RPC 配置
     * @param codecRegistry Codec 注册表
     * @param identity 实际成员应用身份，禁止另设可漂移的 TLS 本地应用
     * @return Transport 运行参数
     */
    @Bean
    @ConditionalOnMissingBean
    public RpcTransportOptions peachRpcTransportOptions(
            OtryxRpcProperties properties,
            RpcCodecRegistry codecRegistry,
            OtryxRpcApplicationIdentity identity) {
        OtryxRpcProperties.Transport transport = properties.getTransport();
        OtryxRpcProperties.Security security =
                transport.getSecurity();
        RpcTransportSecurityOptions securityOptions =
                new RpcTransportSecurityOptions(
                        security.getMode(),
                        security.getCertificatePath(),
                        security.getPrivateKeyPath(),
                        security.getTrustCertificatePath(),
                        security.isHostnameVerification(),
                        security.getHandshakeTimeout(),
                        security.getReloadInterval(),
                        security.getExpiryWarningThreshold(),
                        security.getIdentity().getCertificateBindings().isEmpty() ? Optional.empty()
                                : Optional.of(new RpcTransportIdentityOptions(identity.application(),
                                        security.getIdentity().validatedBindings())));
        return new RpcTransportOptions(
                transport.getMaxInflightPerConnection(),
                transport.getMaxFrameBytes(),
                transport.getMaxWriteQueueBytes(),
                transport.getConnectTimeout(),
                transport.getHandshakeTimeout(),
                codecRegistry.supportedCodecIds(),
                transport.getConnectionsPerEndpoint(),
                transport.getHeartbeatInterval(),
                transport.getHeartbeatTimeout(),
                transport.getReconnectBaseBackoff(),
                transport.getReconnectMaxBackoff())
                .withSecurity(securityOptions)
                .withAdmission(new RpcTransportAdmissionOptions(
                        transport.getAdmission().getMaxConnections(),
                        transport.getAdmission().getMaxHandshakes(),
                        transport.getAdmission().getMaxBufferedPartialFrameBytes(),
                        transport.getAdmission().getPartialFrameTimeout(),
                        transport.getAdmission().getMaxBufferedResponseBytes()));
    }

    /**
     * 创建 Consumer 容错参数。
     *
     * @param properties OTRYX RPC 配置
     * @return Consumer 容错参数
     */
    @Bean
    @ConditionalOnMissingBean
    public RpcClientResilienceOptions peachRpcClientResilienceOptions(
            OtryxRpcProperties properties) {
        OtryxRpcProperties.Resilience resilience =
                properties.getClient().getResilience();
        return new RpcClientResilienceOptions(
                resilience.getMaxAttempts(),
                resilience.getRetryBudgetRatio(),
                resilience.getRetryBudgetMinRetries(),
                resilience.getRetryBudgetMaxRetries(),
                resilience.getRetryBaseBackoff(),
                resilience.getRetryMaxBackoff(),
                resilience.getOutlierConsecutiveFailureThreshold(),
                resilience.getOutlierEjectionDuration(),
                resilience.getCircuitConsecutiveFailureThreshold(),
                resilience.getCircuitOpenDuration());
    }

    /**
     * 创建 Provider 执行资源参数。
     *
     * @param properties OTRYX RPC 配置
     * @return Provider 执行资源参数
     */
    @Bean
    @ConditionalOnMissingBean
    public RpcProviderExecutionOptions peachRpcProviderExecutionOptions(
            OtryxRpcProperties properties) {
        OtryxRpcProperties.Execution execution =
                properties.getServer().getExecution();
        return new RpcProviderExecutionOptions(
                execution.isAllowDirect(),
                execution.getCpuParallelism(),
                execution.getCpuQueueCapacity());
    }

    /**
     * 创建 Consumer 负载均衡器。
     *
     * @param properties OTRYX RPC 配置
     * @return 负载均衡器
     */
    @Bean
    @ConditionalOnMissingBean
    public LoadBalancer peachRpcLoadBalancer(OtryxRpcProperties properties) {
        return requireExtension(
                LoadBalancer.class,
                "otryx.rpc.client.load-balancer",
                properties.getClient().getLoadBalancer());
    }

    /**
     * 创建 Controller/Consumer 的路由扩展，先于负载均衡执行。
     *
     * @param properties Consumer SPI 选择
     * @return Router 扩展
     */
    @Bean
    @ConditionalOnMissingBean
    public RpcRouter peachRpcRouter(OtryxRpcProperties properties) {
        return requireExtension(RpcRouter.class, "otryx.rpc.client.router", properties.getClient().getRouter());
    }

    /**
     * 创建 Consumer 代理工厂。
     *
     * @param properties OTRYX RPC 配置
     * @return 代理工厂
     */
    @Bean
    @ConditionalOnMissingBean
    public ProxyFactory peachRpcProxyFactory(OtryxRpcProperties properties) {
        return requireExtension(
                ProxyFactory.class,
                "otryx.rpc.client.proxy",
                properties.getClient().getProxy());
    }

    /**
     * 使用完整配置键定位缺失或拼写错误的扩展，避免只暴露底层 SPI 异常。
     *
     * @param type SPI 扩展接口
     * @param propertyName 配置键
     * @param name 已配置扩展名称
     * @param <T> SPI 类型
     * @return 匹配的 SPI 扩展实例
     */
    private static <T> T requireExtension(
            Class<T> type,
            String propertyName,
            String name) {
        try {
            return ExtensionLoader.getLoader(type).getExtension(name);
        } catch (IllegalArgumentException error) {
            throw new IllegalStateException(
                    "Invalid " + propertyName + "='" + name
                            + "': install the matching OTRYX RPC adapter "
                            + "or select an available SPI implementation. "
                            + error.getMessage(),
                    error);
        }
    }

    /**
     * 创建运行时协调器。
     *
     * @param registry 应用级成员注册中心
     * @param membership Provider/Consumer 共享的成员发布协调器
     * @param resolver V2 应用级服务发现和安全过滤解析器
     * @param identity 应用级名称和进程实例 ID
     * @param codecRegistry Codec 注册表
     * @param transportFactory Transport 工厂
     * @param transportOptions Transport 配置
     * @param loadBalancer Consumer 负载均衡器
     * @param router Consumer 本地路由器
     * @param proxyFactory Consumer 代理工厂
     * @param resilienceOptions Consumer 容错参数
     * @param executionOptions Provider 执行资源参数
     * @param observerProvider 可观测性 Observer 提供器
     * @param metadataPropagatorProvider Metadata 传播器提供器
     * @param tracingBridgeProvider 分布式 Trace Bridge 提供器
     * @param authorizerProvider 非阻塞 RPC 授权策略提供器
     * @param interceptorProvider 启动阶段一次性发现的有序业务 Hook
     * @param properties OTRYX RPC 配置
     * @return 运行时协调器
     */
    @Bean(destroyMethod = DESTROY_METHOD_CLOSE)
    @ConditionalOnMissingBean
    public OtryxRpcRuntimeCoordinator peachRpcRuntimeCoordinator(
            ApplicationRegistry registry,
            ApplicationMembershipCoordinator membership,
            ApplicationServiceResolver resolver,
            OtryxRpcApplicationIdentity identity,
            RpcCodecRegistry codecRegistry,
            RpcTransportFactory transportFactory,
            RpcTransportOptions transportOptions,
            LoadBalancer loadBalancer,
            RpcRouter router,
            ProxyFactory proxyFactory,
            RpcClientResilienceOptions resilienceOptions,
            RpcProviderExecutionOptions executionOptions,
            ObjectProvider<RpcObserver> observerProvider,
            ObjectProvider<RpcMetadataPropagator> metadataPropagatorProvider,
            ObjectProvider<RpcTracingBridge> tracingBridgeProvider,
            ObjectProvider<RpcAuthorizer> authorizerProvider,
            ObjectProvider<RpcInterceptor> interceptorProvider,
            OtryxRpcProperties properties) {
        OtryxRpcRuntimeCoordinator runtime = new OtryxRpcRuntimeCoordinator(
                registry,
                membership,
                resolver,
                identity,
                codecRegistry,
                transportFactory,
                transportOptions,
                loadBalancer,
                router,
                proxyFactory,
                resilienceOptions,
                executionOptions,
                observerProvider,
                metadataPropagatorProvider,
                tracingBridgeProvider,
                authorizerProvider,
                properties);
        runtime.configureInterceptors(interceptorProvider.orderedStream().toList(),
                properties.getInterceptor().getWorkers(), properties.getInterceptor().getMaxActive());
        return runtime;
    }

    /**
     * 暴露可选的程序化 Consumer Bean；只有真实注入时才创建运行时。
     *
     * @param coordinator 运行时协调器
     * @return Consumer
     */
    @Bean(destroyMethod = "")
    @Lazy
    @ConditionalOnMissingBean
    public OtryxRpcClient peachRpcClient(OtryxRpcRuntimeCoordinator coordinator) {
        return coordinator.client();
    }

    /**
     * 暴露可选的程序化 Provider Bean；只有真实注入时才创建运行时。
     *
     * @param coordinator 运行时协调器
     * @return Provider
     */
    @Bean(destroyMethod = "")
    @Lazy
    @ConditionalOnMissingBean
    public OtryxRpcServer peachRpcServer(OtryxRpcRuntimeCoordinator coordinator) {
        return coordinator.server();
    }

    /**
     * 创建 Provider Bean 定义校验器。
     *
     * @return Provider Bean 定义校验器
     */
    @Bean
    @ConditionalOnMissingBean
    public static OtryxRpcServiceBeanDefinitionValidator
            peachRpcServiceBeanDefinitionValidator() {
        return new OtryxRpcServiceBeanDefinitionValidator();
    }

    /**
     * 创建 Consumer 引用注入处理器。
     *
     * @param coordinatorProvider 运行时协调器延迟提供器
     * @return Consumer 引用注入处理器
     */
    @Bean
    @ConditionalOnMissingBean
    public static OtryxRpcReferenceBeanPostProcessor peachRpcReferenceBeanPostProcessor(
            ObjectProvider<OtryxRpcRuntimeCoordinator> coordinatorProvider) {
        return new OtryxRpcReferenceBeanPostProcessor(coordinatorProvider);
    }

    /**
     * 创建 Provider 服务导出处理器。
     *
     * @param coordinatorProvider 运行时协调器延迟提供器
     * @return Provider 服务导出处理器
     */
    @Bean
    @ConditionalOnMissingBean
    public static OtryxRpcServiceBeanPostProcessor peachRpcServiceBeanPostProcessor(
            ObjectProvider<OtryxRpcRuntimeCoordinator> coordinatorProvider) {
        return new OtryxRpcServiceBeanPostProcessor(coordinatorProvider);
    }

    /**
     * 创建 Provider 生命周期适配器。
     *
     * @param coordinator 运行时协调器
     * @return Provider 生命周期适配器
     */
    @Bean
    @ConditionalOnMissingBean
    public OtryxRpcServerLifecycle peachRpcServerLifecycle(
            OtryxRpcRuntimeCoordinator coordinator) {
        return new OtryxRpcServerLifecycle(coordinator);
    }

}
