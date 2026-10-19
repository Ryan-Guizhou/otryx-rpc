package com.peachsoft.otryx.spring.autoconfigure;

import com.peachsoft.otryx.core.ApplicationMembershipCoordinator;
import com.peachsoft.otryx.registry.ApplicationMetadataBackend;
import com.peachsoft.otryx.registry.ApplicationRegistry;
import com.peachsoft.otryx.registry.RegistryCapabilities;
import com.peachsoft.otryx.registry.RegistryCapability;
import com.peachsoft.otryx.registry.memory.MemoryApplicationManifestRepository;
import com.peachsoft.otryx.registry.memory.MemoryApplicationRegistry;
import com.peachsoft.otryx.registry.memory.MemoryServiceMappingRepository;
import com.peachsoft.otryx.spring.runtime.OtryxRpcApplicationIdentity;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

/**
 * 验证应用级 Starter 的身份优先级、严格配置和 Registry 元数据能力边界。
 *
 * <p>失败用例只使用本地替身，不访问真实注册中心或引入可选 Adapter SDK。
 *
 * @Author OpenAI
 * @Version 1.0.0-SNAPSHOT
 * @CreateTime 2026/10/19 09:45
 */
public class OtryxRpcApplicationConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(OtryxRpcAutoConfiguration.class))
            .withPropertyValues("otryx.rpc.security-profile=TRUSTED_DEVELOPMENT");

    @Test
    void springApplicationNameAndGeneratedInstanceMustProduceOneStableIdentity() {
        namedRunner().run(context -> {
            assertThat(context).hasNotFailed();
            OtryxRpcApplicationIdentity identity = context.getBean(OtryxRpcApplicationIdentity.class);
            assertThat(identity.application().namespace()).isEqualTo("default");
            assertThat(identity.application().applicationName()).isEqualTo("orders");
            assertThat(UUID.fromString(identity.instanceId()).toString()).isEqualTo(identity.instanceId());
            assertThat(context.getBean(OtryxRpcApplicationIdentity.class)).isSameAs(identity);
            assertThat(context.getBean(OtryxRpcProperties.class).getRegistry().getControlPlaneTimeout())
                    .isEqualTo(Duration.ofSeconds(3));
        });
    }

    @Test
    void explicitIdentityMustOverrideSpringNameAndAllowMissingSpringName() {
        runner.withPropertyValues(
                        "otryx.rpc.registry.namespace=tenant-a",
                        "otryx.rpc.registry.application-name=explicit-orders",
                        "otryx.rpc.registry.instance-id=orders-node-7")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    OtryxRpcApplicationIdentity identity = context.getBean(OtryxRpcApplicationIdentity.class);
                    assertThat(identity.application().namespace()).isEqualTo("tenant-a");
                    assertThat(identity.application().applicationName()).isEqualTo("explicit-orders");
                    assertThat(identity.instanceId()).isEqualTo("orders-node-7");
                });
        namedRunner().withPropertyValues("otryx.rpc.registry.application-name=explicit-orders")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(OtryxRpcApplicationIdentity.class)
                            .application().applicationName()).isEqualTo("explicit-orders");
                });
    }

    @Test
    void missingOrBlankApplicationIdentityMustFailBeforeAnyRuntimeIsCreated() {
        runner.run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasStackTraceContaining("spring.application.name");
        });
        runner.withPropertyValues(
                        "spring.application.name=   ",
                        "otryx.rpc.registry.application-name=   ")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasStackTraceContaining("otryx.rpc.registry.application-name");
                });
    }

    @Test
    void unknownPropertiesMustNotBeSilentlyIgnored() {
        namedRunner().withPropertyValues("otryx.rpc.registry.namespce=misspelled")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasStackTraceContaining("otryx.rpc.registry.namespce");
                });
    }

    @Test
    void independentlyOwnedObservabilityAndQuickstartPropertiesMustRemainUsable() {
        namedRunner().withPropertyValues(
                        "otryx.rpc.observability.jfr.enabled=true",
                        "otryx.rpc.quickstart.role=relay")
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void strictConfigurationValidationMustResolvePlaceholdersAndKeepMetadataOptionKeys() {
        namedRunner().withPropertyValues(
                        "test.membership-timeout=2s",
                        "otryx.rpc.registry.control-plane-timeout=${test.membership-timeout}",
                        "otryx.rpc.registry.eureka.metadata-options[consulToken]=test-credential")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    OtryxRpcProperties.Registry registry = context.getBean(OtryxRpcProperties.class).getRegistry();
                    assertThat(registry.getControlPlaneTimeout()).isEqualTo(Duration.ofSeconds(2));
                    assertThat(registry.getEureka().getMetadataOptions())
                            .containsEntry("consulToken", "test-credential");
                });
    }

    @ParameterizedTest
    @ValueSource(strings = {"mode", "registration-mode", "register-mode", "service-name"})
    void interfaceRegistryConfigurationMustExplainApplicationMigration(String key) {
        namedRunner().withPropertyValues("otryx.rpc.registry." + key + "=interface")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasStackTraceContaining("application-level")
                            .hasStackTraceContaining("otryx.rpc.registry.application-name");
                });
    }

    @Test
    void deletedInterfaceRegistryMustNotBeAvailableToUserBeans() {
        assertThat(org.springframework.util.ClassUtils.isPresent(
                "com.peachsoft.otryx.registry.Registry", getClass().getClassLoader())).isFalse();
    }

    @Test
    void customApplicationRegistryMustOverrideUnavailableFactoryAndInactiveBackendProperties() {
        MemoryApplicationRegistry registry = new MemoryApplicationRegistry();
        namedRunner().withBean(ApplicationRegistry.class, () -> registry)
                .withPropertyValues("otryx.rpc.registry.type=eureka")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(ApplicationRegistry.class)).isSameAs(registry);
                    assertThat(context).hasSingleBean(ApplicationMembershipCoordinator.class);
                });
    }

    @Test
    void customRegistryWithoutMetadataMustExplainRequiredCapability() {
        ApplicationRegistry registry = mock(ApplicationRegistry.class);
        namedRunner().withBean(ApplicationRegistry.class, () -> registry)
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasStackTraceContaining("ApplicationMetadataBackend")
                            .hasStackTraceContaining("Manifest")
                            .hasStackTraceContaining("Mapping");
                });
    }

    @Test
    void discoveryOnlyRegistryMustRejectConsumerMembershipConfiguration() {
        ApplicationRegistry registry = metadataRegistry(RegistryCapabilities.of());
        namedRunner().withBean(ApplicationRegistry.class, () -> registry)
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasStackTraceContaining("membership registration");
                });
    }

    @Test
    void declaredRegistrationWithoutRegistrarMustFailCapabilityValidation() {
        ApplicationRegistry registry = metadataRegistry(
                RegistryCapabilities.of(RegistryCapability.REGISTRATION));
        namedRunner().withBean(ApplicationRegistry.class, () -> registry)
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasStackTraceContaining("REGISTRATION")
                            .hasStackTraceContaining("ApplicationRegistrar");
                });
    }

    @Test
    void nullMetadataRepositoryMustFailWithTheMissingRepositoryName() {
        ApplicationRegistry registry = metadataRegistry(RegistryCapabilities.of());
        when(((ApplicationMetadataBackend) registry).manifestRepository()).thenReturn(null);
        namedRunner().withBean(ApplicationRegistry.class, () -> registry)
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasStackTraceContaining("manifestRepository");
                });
    }

    @ParameterizedTest
    @ValueSource(strings = {"0s", "-1s", "30001ms"})
    void invalidRegistryControlTimeoutMustFailEvenWhenProviderIsDisabled(String timeout) {
        namedRunner().withPropertyValues(
                        "otryx.rpc.server.enabled=false",
                        "otryx.rpc.registry.control-plane-timeout=" + timeout)
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasStackTraceContaining("otryx.rpc.registry.control-plane-timeout");
                });
    }

    @Test
    void registryMembershipMustNotUseDisabledProviderTimeout() {
        namedRunner().withPropertyValues(
                        "otryx.rpc.server.enabled=false",
                        "otryx.rpc.server.control-plane-timeout=0s",
                        "otryx.rpc.registry.control-plane-timeout=2s")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(ApplicationMembershipCoordinator.class);
                });
    }

    @Test
    void obsoleteConsulDatacenterMustExplainLocalAgentMigrationBeforeLoadingSdk() {
        namedRunner().withPropertyValues(
                        "otryx.rpc.registry.type=consul",
                        "otryx.rpc.registry.consul.datacenter=dc-east")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasStackTraceContaining("otryx.rpc.registry.consul.datacenter")
                            .hasStackTraceContaining("local Agent");
                });
    }

    @Test
    void missingEurekaMetadataBackendMustReportSpringPropertyBeforeLoadingSdk() {
        namedRunner().withPropertyValues("otryx.rpc.registry.type=eureka")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasStackTraceContaining("otryx.rpc.registry.eureka.metadata-backend");
                });
    }

    @Test
    void unsupportedEurekaMetadataBackendMustFailWithoutEchoingItsValue() {
        String untrustedValue = "memory-secret-marker";
        namedRunner().withPropertyValues(
                        "otryx.rpc.registry.type=eureka",
                        "otryx.rpc.registry.eureka.metadata-backend=" + untrustedValue)
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasStackTraceContaining("otryx.rpc.registry.eureka.metadata-backend")
                            .hasStackTraceContaining("etcd, nacos or consul");
                    assertThat(context.getStartupFailure().getMessage()).doesNotContain(untrustedValue);
                });
    }

    @Test
    void missingEurekaMetadataEndpointsMustReportIndependentBackendAddress() {
        namedRunner().withPropertyValues(
                        "otryx.rpc.registry.type=eureka",
                        "otryx.rpc.registry.eureka.metadata-backend=consul")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasStackTraceContaining("otryx.rpc.registry.eureka.metadata-endpoints");
                });
    }

    @Test
    void validEurekaConfigurationStillRequiresItsExplicitOptionalAdapter() {
        namedRunner().withPropertyValues(
                        "otryx.rpc.registry.type=eureka",
                        "otryx.rpc.registry.eureka.metadata-backend=consul",
                        "otryx.rpc.registry.eureka.metadata-endpoints=http://127.0.0.1:8500")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasStackTraceContaining("otryx.rpc.registry.type")
                            .hasStackTraceContaining("matching OTRYX RPC adapter");
                });
    }

    private ApplicationContextRunner namedRunner() {
        return runner.withPropertyValues("spring.application.name=orders");
    }

    private static ApplicationRegistry metadataRegistry(RegistryCapabilities capabilities) {
        ApplicationRegistry registry = mock(ApplicationRegistry.class,
                withSettings().extraInterfaces(ApplicationMetadataBackend.class));
        when(registry.capabilities()).thenReturn(capabilities);
        when(registry.registrar()).thenCallRealMethod();
        ApplicationMetadataBackend backend = (ApplicationMetadataBackend) registry;
        when(backend.manifestRepository()).thenReturn(new MemoryApplicationManifestRepository());
        when(backend.serviceMappingRepository()).thenReturn(new MemoryServiceMappingRepository());
        return registry;
    }
}
