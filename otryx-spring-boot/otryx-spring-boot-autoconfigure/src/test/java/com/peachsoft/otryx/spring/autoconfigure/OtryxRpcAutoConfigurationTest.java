package com.peachsoft.otryx.spring.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import com.peachsoft.otryx.core.OtryxRpcClient;
import com.peachsoft.otryx.core.OtryxRpcServer;
import com.peachsoft.otryx.observability.RpcSecurityMode;
import com.peachsoft.otryx.transport.RpcTransportOptions;
import com.peachsoft.otryx.spring.annotation.OtryxRpcReference;
import com.peachsoft.otryx.spring.annotation.OtryxRpcService;
import com.peachsoft.otryx.spring.runtime.OtryxRpcRuntimeCoordinator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.annotation.Scope;

/**
 * OTRYX RPC 注解驱动自动配置测试。
 *
 * @Author Ryan
 * @Version 1.0.0
 * @CreateTime 2026/9/23 10:51
 */
public class OtryxRpcAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner =
            new ApplicationContextRunner()
                    .withConfiguration(
                            AutoConfigurations.of(
                                    OtryxRpcAutoConfiguration.class));


    @Test
    void invalidClientDeadlineShouldFailAtConfigurationBinding() {
        contextRunner
                .withPropertyValues("otryx.rpc.client.timeout=0s")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasStackTraceContaining("otryx.rpc.client.timeout");
                });
    }

    @Test
    void invalidTransportConnectionShardsShouldFailFast() {
        contextRunner
                .withPropertyValues("otryx.rpc.transport.connections-per-endpoint=0")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasStackTraceContaining(
                                    "otryx.rpc.transport.connections-per-endpoint");
                });
    }

    @Test
    void invalidProviderCpuCapacityShouldFailFast() {
        contextRunner
                .withPropertyValues("otryx.rpc.server.execution.cpu-queue-capacity=0")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasStackTraceContaining(
                                    "otryx.rpc.server.execution.cpu-queue-capacity");
                });
    }

    @Test
    void invalidProviderShutdownTimeoutShouldFailFast() {
        contextRunner
                .withPropertyValues("otryx.rpc.server.shutdown-timeout=0s")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasStackTraceContaining(
                                    "server.shutdown-timeout");
                });
    }

    @Test
    void providerShutdownTimeoutShouldBind() {
        contextRunner
                .withPropertyValues("otryx.rpc.server.shutdown-timeout=18s")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(OtryxRpcProperties.class)
                            .getServer().getShutdownTimeout())
                            .isEqualTo(java.time.Duration.ofSeconds(18));
                });
    }

    @Test
    void consulAndEurekaRegistryOptionsShouldBind() {
        contextRunner
                .withPropertyValues(
                        "otryx.rpc.registry.consul.token=test-token",
                        "otryx.rpc.registry.consul.ttl-seconds=45",
                        "otryx.rpc.registry.consul.heartbeat-seconds=15",
                        "otryx.rpc.registry.consul.datacenter=dc-east",
                        "otryx.rpc.registry.eureka.username=test-user",
                        "otryx.rpc.registry.eureka.password=test-password",
                        "otryx.rpc.registry.eureka.lease-seconds=60",
                        "otryx.rpc.registry.eureka.poll-interval-millis=3000")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    OtryxRpcProperties.Registry props =
                            context.getBean(OtryxRpcProperties.class).getRegistry();
                    assertThat(props.getConsul().getToken()).isEqualTo("test-token");
                    assertThat(props.getConsul().getTtlSeconds()).isEqualTo(45);
                    assertThat(props.getConsul().getHeartbeatSeconds()).isEqualTo(15);
                    assertThat(props.getConsul().getDatacenter()).isEqualTo("dc-east");
                    assertThat(props.getEureka().getUsername()).isEqualTo("test-user");
                    assertThat(props.getEureka().getLeaseSeconds()).isEqualTo(60);
                    assertThat(props.getEureka().getPollIntervalMillis()).isEqualTo(3000);
                });
    }

    @Test
    void missingNacosAdapterShouldSuggestClasspathCorrection() {
        contextRunner
                .withPropertyValues("otryx.rpc.registry.type=nacos")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasStackTraceContaining("otryx.rpc.registry.type")
                            .hasStackTraceContaining("matching OTRYX RPC adapter");
                });
    }

    @Test
    void disabledConsumerShouldIgnoreItsInactiveTimeout() {
        contextRunner
                .withPropertyValues(
                        "otryx.rpc.client.enabled=false",
                        "otryx.rpc.client.timeout=0s")
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void noRpcAnnotationsShouldNotCreateRuntime() {
        contextRunner.run(context -> {
            OtryxRpcRuntimeCoordinator coordinator =
                    context.getBean(
                            OtryxRpcRuntimeCoordinator.class);

            assertThat(coordinator.clientIfCreated()).isEmpty();
            assertThat(coordinator.serverIfCreated()).isEmpty();
        });
    }

    @Test
    void explicitEnableShouldNotCreateRuntimeWithoutUsage() {
        contextRunner
                .withPropertyValues(
                        "otryx.rpc.client.enabled=true",
                        "otryx.rpc.server.enabled=true")
                .run(context -> {
                    OtryxRpcRuntimeCoordinator coordinator =
                            context.getBean(
                                    OtryxRpcRuntimeCoordinator.class);

                    assertThat(coordinator.clientIfCreated()).isEmpty();
                    assertThat(coordinator.serverIfCreated()).isEmpty();
                });
    }

    @Test
    void referenceShouldCreateOnlyConsumerRuntime() {
        contextRunner.withBean(ConsumerBean.class)
                .run(context -> {
                    OtryxRpcRuntimeCoordinator coordinator =
                            context.getBean(
                                    OtryxRpcRuntimeCoordinator.class);

                    assertThat(coordinator.clientIfCreated()).isPresent();
                    assertThat(coordinator.serverIfCreated()).isEmpty();
                });
    }

    @Test
    void serviceShouldCreateOnlyProviderRuntime() {
        providerRunner()
                .withBean(ServiceBean.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    OtryxRpcRuntimeCoordinator coordinator =
                            context.getBean(
                                    OtryxRpcRuntimeCoordinator.class);

                    assertThat(coordinator.clientIfCreated()).isEmpty();
                    assertThat(coordinator.serverIfCreated()).isPresent();
                });
    }

    @Test
    void memoryRegistryShouldCompleteRealRpcWithoutExternalInfrastructure() {
        providerRunner()
                .withBean(ServiceBean.class)
                .withBean(ConsumerBean.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    ConsumerBean consumer = context.getBean(ConsumerBean.class);
                    assertThat(consumer.service.call()).isEqualTo("ok");
                });
    }

    @Test
    void referenceAndServiceShouldCreateBothRuntimes() {
        providerRunner()
                .withBean(ServiceBean.class)
                .withBean(ConsumerBean.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    OtryxRpcRuntimeCoordinator coordinator =
                            context.getBean(
                                    OtryxRpcRuntimeCoordinator.class);

                    assertThat(coordinator.clientIfCreated()).isPresent();
                    assertThat(coordinator.serverIfCreated()).isPresent();
                });
    }

    @Test
    void multipleServicesShouldRegisterBeforeServerStarts() {
        providerRunner()
                .withBean(ServiceBean.class)
                .withBean(SecondServiceBean.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(
                            OtryxRpcServer.class);
                });
    }

    @Test
    void disabledConsumerShouldFailWhenReferenceExists() {
        contextRunner
                .withPropertyValues(
                        "otryx.rpc.client.enabled=false")
                .withBean(ConsumerBean.class)
                .run(context ->
                        assertThat(context).hasFailed());
    }

    @Test
    void disabledProviderShouldFailWhenServiceExists() {
        contextRunner
                .withPropertyValues(
                        "otryx.rpc.server.enabled=false")
                .withBean(ServiceBean.class)
                .run(context ->
                        assertThat(context).hasFailed());
    }

    @Test
    void nonInterfaceReferenceShouldFailFast() {
        contextRunner
                .withBean(InvalidReferenceBean.class)
                .run(context ->
                        assertThat(context).hasFailed());
    }

    @Test
    void mismatchedServiceInterfaceShouldFailFast() {
        contextRunner
                .withBean(MismatchedServiceBean.class)
                .run(context ->
                        assertThat(context).hasFailed());
    }

    @Test
    void lazyProviderShouldFailFast() {
        contextRunner.withBean(LazyServiceBean.class)
                .run(context ->
                        assertThat(context).hasFailed());
    }

    @Test
    void prototypeProviderShouldFailFast() {
        contextRunner
                .withUserConfiguration(
                        PrototypeProviderConfiguration.class)
                .run(context ->
                        assertThat(context).hasFailed());
    }

    @Test
    void disabledFrameworkShouldCreateNoRpcBeans() {
        contextRunner
                .withPropertyValues(
                        "otryx.rpc.enabled=false")
                .withBean(ConsumerBean.class)
                .run(context -> {
                    assertThat(context).doesNotHaveBean(
                            OtryxRpcRuntimeCoordinator.class);
                    assertThat(context).doesNotHaveBean(
                            OtryxRpcClient.class);
                    assertThat(context).doesNotHaveBean(
                            OtryxRpcServer.class);
                });
    }

    @Test
    void tlsPropertiesShouldBindToTransportSecurityOptions() {
        contextRunner
                .withPropertyValues(
                        "otryx.rpc.transport.security.mode=MTLS",
                        "otryx.rpc.transport.security.certificate-path=/tmp/server.crt",
                        "otryx.rpc.transport.security.private-key-path=/tmp/server.key",
                        "otryx.rpc.transport.security.trust-certificate-path=/tmp/ca.crt",
                        "otryx.rpc.transport.security.hostname-verification=false",
                        "otryx.rpc.transport.security.handshake-timeout=5s",
                        "otryx.rpc.transport.security.reload-interval=45s",
                        "otryx.rpc.transport.security.expiry-warning-threshold=10d")
                .run(context -> {
                    RpcTransportOptions options =
                            context.getBean(
                                    RpcTransportOptions.class);

                    assertThat(options.security().mode())
                            .isEqualTo(
                                    RpcSecurityMode.MTLS);
                    assertThat(
                            options.security()
                                    .certificatePath())
                            .isEqualTo("/tmp/server.crt");
                    assertThat(
                            options.security()
                                    .privateKeyPath())
                            .isEqualTo("/tmp/server.key");
                    assertThat(
                            options.security()
                                    .trustCertificatePath())
                            .isEqualTo("/tmp/ca.crt");
                    assertThat(
                            options.security()
                                    .hostnameVerification())
                            .isFalse();
                    assertThat(
                            options.security()
                                    .handshakeTimeout())
                            .isEqualTo(
                                    java.time.Duration
                                            .ofSeconds(5));
                    assertThat(
                            options.security()
                                    .reloadInterval())
                            .isEqualTo(
                                    java.time.Duration
                                            .ofSeconds(45));
                    assertThat(
                            options.security()
                                    .expiryWarningThreshold())
                            .isEqualTo(
                                    java.time.Duration
                                            .ofDays(10));
                });
    }

    @Test
    void strictForySecurityPropertiesShouldBind() {
        contextRunner
                .withPropertyValues(
                        "otryx.rpc.codec.fory.mode=STRICT_ALLOWLIST",
                        "otryx.rpc.codec.fory.allowed-class-patterns[0]=com.peachsoft.otryx.demo.*",
                        "otryx.rpc.codec.fory.max-depth=16",
                        "otryx.rpc.codec.fory.max-graph-memory-bytes=1048576",
                        "otryx.rpc.codec.fory.max-payload-bytes=16384")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    OtryxRpcProperties.ForySecurity security = context
                            .getBean(OtryxRpcProperties.class)
                            .getCodec().getFory();
                    assertThat(security.getMode().name())
                            .isEqualTo("STRICT_ALLOWLIST");
                    assertThat(security.getAllowedClassPatterns())
                            .contains("com.peachsoft.otryx.demo.*");
                    assertThat(security.getMaxDepth()).isEqualTo(16);
                    assertThat(security.getMaxGraphMemoryBytes())
                            .isEqualTo(1048576L);
                    assertThat(security.getMaxPayloadBytes())
                            .isEqualTo(16384);
                });
    }

    @Test
    void strictForySecurityMustRejectMissingAllowlist() {
        contextRunner
                .withPropertyValues(
                        "otryx.rpc.codec.fory.mode=STRICT_ALLOWLIST")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void providerAdmissionBudgetPropertiesShouldBind() {
        contextRunner.withPropertyValues(
                "otryx.rpc.server.max-concurrent=64",
                "otryx.rpc.server.admission.max-inflight-bytes=4194304",
                "otryx.rpc.server.admission.max-concurrent-per-service=16",
                "otryx.rpc.server.admission.max-concurrent-per-method=8",
                "otryx.rpc.server.admission.max-inflight-bytes-per-service=1048576",
                "otryx.rpc.server.admission.max-inflight-bytes-per-method=262144")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    OtryxRpcProperties.Server options = context
                            .getBean(OtryxRpcProperties.class).getServer();
                    assertThat(options.getMaxConcurrent()).isEqualTo(64);
                    assertThat(options.getAdmission().getMaxInflightBytes())
                            .isEqualTo(4194304L);
                    assertThat(options.getAdmission()
                                    .getMaxConcurrentPerService())
                            .isEqualTo(16);
                    assertThat(options.getAdmission()
                                    .getMaxConcurrentPerMethod())
                            .isEqualTo(8);
                    assertThat(options.getAdmission()
                                    .getMaxInflightBytesPerService())
                            .isEqualTo(1048576L);
                    assertThat(options.getAdmission()
                                    .getMaxInflightBytesPerMethod())
                            .isEqualTo(262144L);
                });
    }

    @Test
    void rawClientInjectionShouldCreateConsumerRuntime() {
        contextRunner
                .withBean(RawClientBean.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    OtryxRpcRuntimeCoordinator coordinator =
                            context.getBean(
                                    OtryxRpcRuntimeCoordinator.class);

                    assertThat(coordinator.clientIfCreated()).isPresent();
                    assertThat(coordinator.serverIfCreated()).isEmpty();
                });
    }

    @Test
    void rawServerInjectionShouldNotAutoStartProvider() {
        contextRunner
                .withPropertyValues(
                        "otryx.rpc.server.host=0.0.0.0")
                .withBean(RawServerBean.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    OtryxRpcRuntimeCoordinator coordinator =
                            context.getBean(
                                    OtryxRpcRuntimeCoordinator.class);

                    assertThat(coordinator.serverIfCreated()).isPresent();
                    assertThat(coordinator.autoStartServerIfCreated())
                            .isEmpty();
                });
    }

    private ApplicationContextRunner providerRunner() {
        return contextRunner.withPropertyValues(
                "otryx.rpc.server.host=127.0.0.1",
                "otryx.rpc.server.port=0");
    }

    public interface DemoService {
        String call();
    }

    public interface SecondDemoService {
        String second();
    }

    public interface OtherService {
        String other();
    }

    @OtryxRpcService(
            interfaceClass = DemoService.class,
            version = "1.0.0")
    public static class ServiceBean implements DemoService {
        @Override
        public String call() {
            return "ok";
        }
    }

    @OtryxRpcService(
            interfaceClass = SecondDemoService.class,
            version = "1.0.0")
    public static class SecondServiceBean
            implements SecondDemoService {
        @Override
        public String second() {
            return "second";
        }
    }

    @OtryxRpcService(
            interfaceClass = DemoService.class,
            version = "1.0.0")
    public static class MismatchedServiceBean
            implements OtherService {
        @Override
        public String other() {
            return "other";
        }
    }

    @Lazy
    @OtryxRpcService(
            interfaceClass = DemoService.class,
            version = "1.0.0")
    public static class LazyServiceBean implements DemoService {
        @Override
        public String call() {
            return "ok";
        }
    }

    @OtryxRpcService(
            interfaceClass = DemoService.class,
            version = "1.0.0")
    public static class PrototypeServiceBean
            implements DemoService {
        @Override
        public String call() {
            return "prototype";
        }
    }

    public static class ConsumerBean {
        @OtryxRpcReference(version = "1.0.0")
        private DemoService service;
    }

    public static class InvalidReferenceBean {
        @OtryxRpcReference
        private String service;
    }

    public static class RawClientBean {
        public RawClientBean(OtryxRpcClient client) {
        }
    }

    public static class RawServerBean {
        public RawServerBean(OtryxRpcServer server) {
        }
    }

    @Configuration(proxyBeanMethods = false)
    public static class PrototypeProviderConfiguration {

        @Bean
        @Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
        public PrototypeServiceBean prototypeServiceBean() {
            return new PrototypeServiceBean();
        }
    }
}
