package com.peachsoft.otryx.spring.autoconfigure;

import com.peachsoft.otryx.codec.RpcCodecRegistry;
import com.peachsoft.otryx.codec.fory.ForyRpcCodec;
import com.peachsoft.otryx.codec.fory.ForyRpcSecurityOptions;
import com.peachsoft.otryx.core.OtryxRpcClient;
import com.peachsoft.otryx.core.OtryxRpcServer;
import com.peachsoft.otryx.core.RpcClientResilienceOptions;
import com.peachsoft.otryx.core.RpcProviderExecutionOptions;
import com.peachsoft.otryx.loadbalance.LoadBalancer;
import com.peachsoft.otryx.observability.RpcMetadataPropagator;
import com.peachsoft.otryx.observability.RpcObserver;
import com.peachsoft.otryx.observability.RpcTracingBridge;
import com.peachsoft.otryx.proxy.ProxyFactory;
import com.peachsoft.otryx.registry.Registry;
import com.peachsoft.otryx.registry.RegistryFactory;
import com.peachsoft.otryx.registry.RegistryOptions;
import com.peachsoft.otryx.spi.ExtensionLoader;
import com.peachsoft.otryx.spring.lifecycle.OtryxRpcServerLifecycle;
import com.peachsoft.otryx.spring.processor.OtryxRpcReferenceBeanPostProcessor;
import com.peachsoft.otryx.spring.processor.OtryxRpcServiceBeanDefinitionValidator;
import com.peachsoft.otryx.spring.processor.OtryxRpcServiceBeanPostProcessor;
import com.peachsoft.otryx.spring.runtime.OtryxRpcRuntimeCoordinator;
import com.peachsoft.otryx.transport.RpcTransportFactory;
import com.peachsoft.otryx.transport.RpcTransportOptions;
import com.peachsoft.otryx.transport.RpcTransportSecurityOptions;
import java.util.Map;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
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
    private static final String PROVIDER_OPTION_LEASE_TTL_SECONDS = "leaseTtlSeconds";
    private static final String PROVIDER_OPTION_NACOS_GROUP = "nacosGroup";
    private static final String PROVIDER_OPTION_NACOS_CLUSTER = "nacosCluster";
    private static final String PROVIDER_OPTION_NACOS_USERNAME = "nacosUsername";
    private static final String PROVIDER_OPTION_NACOS_PASSWORD = "nacosPassword";
    private static final String PROVIDER_OPTION_CONSUL_TOKEN = "consulToken";
    private static final String PROVIDER_OPTION_CONSUL_DATACENTER = "consulDatacenter";
    private static final String PROVIDER_OPTION_CONSUL_ENTERPRISE_NAMESPACE = "consulEnterpriseNamespace";
    private static final String PROVIDER_OPTION_CONSUL_TTL_SECONDS = "consulTtlSeconds";
    private static final String PROVIDER_OPTION_CONSUL_HEARTBEAT_SECONDS = "consulHeartbeatSeconds";
    private static final String PROVIDER_OPTION_CONSUL_POLL_INTERVAL_MILLIS = "consulPollIntervalMillis";
    private static final String PROVIDER_OPTION_CONSUL_REQUEST_TIMEOUT_MILLIS = "consulRequestTimeoutMillis";
    private static final String PROVIDER_OPTION_EUREKA_USERNAME = "eurekaUsername";
    private static final String PROVIDER_OPTION_EUREKA_PASSWORD = "eurekaPassword";
    private static final String PROVIDER_OPTION_EUREKA_LEASE_SECONDS = "eurekaLeaseSeconds";
    private static final String PROVIDER_OPTION_EUREKA_HEARTBEAT_SECONDS = "eurekaHeartbeatSeconds";
    private static final String PROVIDER_OPTION_EUREKA_POLL_INTERVAL_MILLIS = "eurekaPollIntervalMillis";
    private static final String PROVIDER_OPTION_EUREKA_REQUEST_TIMEOUT_MILLIS = "eurekaRequestTimeoutMillis";

    /**
     * 创建 OTRYX RPC 自动配置。
     */
    public OtryxRpcAutoConfiguration() {
    }

    /**
     * 创建注册中心实例。
     *
     * @param properties OTRYX RPC 配置
     * @param observerProvider Registry 控制面 Observer 提供器
     * @return 注册中心实例
     */
    @Bean(destroyMethod = DESTROY_METHOD_CLOSE)
    @ConditionalOnMissingBean
    public Registry peachRpcRegistry(
            OtryxRpcProperties properties,
            ObjectProvider<RpcObserver> observerProvider) {
        OtryxRpcProperties.Registry registry = properties.getRegistry();
        RegistryFactory factory = requireExtension(
                RegistryFactory.class,
                CONFIG_REGISTRY_TYPE,
                registry.getType());
        return factory.create(RegistryOptions.fromCsv(
                registry.getEndpoints(),
                registry.getNamespace(),
                Map.ofEntries(
                        Map.entry(PROVIDER_OPTION_LEASE_TTL_SECONDS,
                                Long.toString(registry.getLeaseTtlSeconds())),
                        Map.entry(PROVIDER_OPTION_NACOS_GROUP, registry.getNacos().getGroup()),
                        Map.entry(PROVIDER_OPTION_NACOS_CLUSTER, registry.getNacos().getCluster()),
                        Map.entry(PROVIDER_OPTION_NACOS_USERNAME, registry.getNacos().getUsername()),
                        Map.entry(PROVIDER_OPTION_NACOS_PASSWORD, registry.getNacos().getPassword()),
                        Map.entry(PROVIDER_OPTION_CONSUL_TOKEN, registry.getConsul().getToken()),
                        Map.entry(PROVIDER_OPTION_CONSUL_DATACENTER, registry.getConsul().getDatacenter()),
                        Map.entry(PROVIDER_OPTION_CONSUL_ENTERPRISE_NAMESPACE,
                                registry.getConsul().getEnterpriseNamespace()),
                        Map.entry(PROVIDER_OPTION_CONSUL_TTL_SECONDS,
                                Long.toString(registry.getConsul().getTtlSeconds())),
                        Map.entry(PROVIDER_OPTION_CONSUL_HEARTBEAT_SECONDS,
                                Long.toString(registry.getConsul().getHeartbeatSeconds())),
                        Map.entry(PROVIDER_OPTION_CONSUL_POLL_INTERVAL_MILLIS,
                                Long.toString(registry.getConsul().getPollIntervalMillis())),
                        Map.entry(PROVIDER_OPTION_CONSUL_REQUEST_TIMEOUT_MILLIS,
                                Long.toString(registry.getConsul().getRequestTimeoutMillis())),
                        Map.entry(PROVIDER_OPTION_EUREKA_USERNAME, registry.getEureka().getUsername()),
                        Map.entry(PROVIDER_OPTION_EUREKA_PASSWORD, registry.getEureka().getPassword()),
                        Map.entry(PROVIDER_OPTION_EUREKA_LEASE_SECONDS,
                                Long.toString(registry.getEureka().getLeaseSeconds())),
                        Map.entry(PROVIDER_OPTION_EUREKA_HEARTBEAT_SECONDS,
                                Long.toString(registry.getEureka().getHeartbeatSeconds())),
                        Map.entry(PROVIDER_OPTION_EUREKA_POLL_INTERVAL_MILLIS,
                                Long.toString(registry.getEureka().getPollIntervalMillis())),
                        Map.entry(PROVIDER_OPTION_EUREKA_REQUEST_TIMEOUT_MILLIS,
                                Long.toString(registry.getEureka().getRequestTimeoutMillis()))),
                RpcObserver.composite(
                        observerProvider.orderedStream().toList())));
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
        OtryxRpcProperties.ForySecurity security = properties.getCodec().getFory();
        ForyRpcSecurityOptions options = new ForyRpcSecurityOptions(
                security.getMode(),
                java.util.Set.copyOf(security.getAllowedClassPatterns()),
                security.getMaxDepth(),
                security.getMaxGraphMemoryBytes(),
                security.getMaxPayloadBytes());
        return RpcCodecRegistry.fromSpi()
                .withReplacement(new ForyRpcCodec(options));
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
     * @return Transport 运行参数
     */
    @Bean
    @ConditionalOnMissingBean
    public RpcTransportOptions peachRpcTransportOptions(
            OtryxRpcProperties properties,
            RpcCodecRegistry codecRegistry) {
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
                        security.getExpiryWarningThreshold());
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
                .withSecurity(securityOptions);
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
     * @param registry 注册中心
     * @param codecRegistry Codec 注册表
     * @param transportFactory Transport 工厂
     * @param transportOptions Transport 配置
     * @param loadBalancer Consumer 负载均衡器
     * @param proxyFactory Consumer 代理工厂
     * @param resilienceOptions Consumer 容错参数
     * @param executionOptions Provider 执行资源参数
     * @param observerProvider 可观测性 Observer 提供器
     * @param metadataPropagatorProvider Metadata 传播器提供器
     * @param tracingBridgeProvider 分布式 Trace Bridge 提供器
     * @param properties OTRYX RPC 配置
     * @return 运行时协调器
     */
    @Bean(destroyMethod = DESTROY_METHOD_CLOSE)
    @ConditionalOnMissingBean
    public OtryxRpcRuntimeCoordinator peachRpcRuntimeCoordinator(
            Registry registry,
            RpcCodecRegistry codecRegistry,
            RpcTransportFactory transportFactory,
            RpcTransportOptions transportOptions,
            LoadBalancer loadBalancer,
            ProxyFactory proxyFactory,
            RpcClientResilienceOptions resilienceOptions,
            RpcProviderExecutionOptions executionOptions,
            ObjectProvider<RpcObserver> observerProvider,
            ObjectProvider<RpcMetadataPropagator> metadataPropagatorProvider,
            ObjectProvider<RpcTracingBridge> tracingBridgeProvider,
            OtryxRpcProperties properties) {
        return new OtryxRpcRuntimeCoordinator(
                registry,
                codecRegistry,
                transportFactory,
                transportOptions,
                loadBalancer,
                proxyFactory,
                resilienceOptions,
                executionOptions,
                observerProvider,
                metadataPropagatorProvider,
                tracingBridgeProvider,
                properties);
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
