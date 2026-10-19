package com.peachsoft.otryx.spring.autoconfigure;

import com.peachsoft.otryx.registry.ApplicationRegistry;
import com.peachsoft.otryx.registry.memory.MemoryApplicationRegistry;
import com.peachsoft.otryx.core.RpcAuthorizer;
import com.peachsoft.otryx.spring.runtime.OtryxRpcRuntimeCoordinator;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

/**
 * 验证静态安全错误在资源工厂前拒绝，并且绑定异常的完整输出不泄漏输入值。
 *
 * @Author OpenAI
 * @Version 1.0.0-SNAPSHOT
 * @CreateTime 2026/10/19 11:21
 */
@ExtendWith(OutputCaptureExtension.class)
class OtryxRpcSecurityPreflightTest {
    private static final String SYNTHETIC_SECRET = "RPC_TEST_SECRET_c8f24d5e";

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(OtryxRpcAutoConfiguration.class))
            .withPropertyValues("spring.application.name=security-preflight");

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void networkProviderRejectsMissingAuthorizerBeforeFactoryInvocation(boolean consumerEnabled) {
        AtomicInteger registryCreates = new AtomicInteger();
        runner.withBean(ApplicationRegistry.class, () -> {
            registryCreates.incrementAndGet();
            return new MemoryApplicationRegistry();
        }).withPropertyValues(
                "otryx.rpc.security-profile=NETWORK_MTLS",
                "otryx.rpc.transport.security.mode=MTLS",
                "otryx.rpc.codec.fory.mode=STRICT_ALLOWLIST",
                "otryx.rpc.codec.fory.allowed-class-patterns[0]=com.peachsoft.otryx.spring.autoconfigure.*",
                "otryx.rpc.client.enabled=" + consumerEnabled,
                "otryx.rpc.server.enabled=true")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasStackTraceContaining("RpcAuthorizer");
                    assertThat(registryCreates).hasValue(0);
                });
    }

    @Test
    void unspecifiedProfileRejectsBeforeCustomResourcesAreCreated() {
        AtomicInteger registryCreates = new AtomicInteger();
        runner.withBean(ApplicationRegistry.class, () -> {
            registryCreates.incrementAndGet();
            return new MemoryApplicationRegistry();
        }).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasStackTraceContaining("security-profile");
            assertThat(registryCreates).hasValue(0);
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"security-profile", "transport.security.handshake-timeout"})
    void malformedSensitiveValueNeverAppearsInFailureChainOrOutput(String key, CapturedOutput output) {
        runner.withPropertyValues(
                "otryx.rpc.security-profile=TRUSTED_DEVELOPMENT",
                "otryx.rpc." + key + "=" + SYNTHETIC_SECRET)
                .run(context -> {
                    assertThat(context).hasFailed();
                    StringWriter trace = new StringWriter();
                    context.getStartupFailure().printStackTrace(new PrintWriter(trace));
                    assertThat(trace.toString()).doesNotContain(SYNTHETIC_SECRET);
                    assertThat(output.getAll()).doesNotContain(SYNTHETIC_SECRET);
                    assertThat(trace.toString()).contains("otryx.rpc");
                });
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void bothCapabilitiesArePreflightedBeforeAnyRuntime(boolean unknownClient) {
        SecurityTestTransportFactory factory = new SecurityTestTransportFactory();
        factory.unknownClient = unknownClient;
        factory.unknownServer = !unknownClient;
        MemoryApplicationRegistry registry = spy(new MemoryApplicationRegistry());
        SecurityTestTransportFactory.networkRunner(runner, "security-preflight", factory)
                .withBean(ApplicationRegistry.class, () -> registry)
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasStackTraceContaining("transport security capabilities");
                    assertThat(factory.clientCreates).hasValue(1);
                    assertThat(factory.serverCreates).hasValue(1);
                    assertThat(factory.starts).hasValue(0);
                    assertThat(factory.requests).hasValue(0);
                    assertThat(factory.closes).hasValue(2);
                    verify(registry, never()).register(any());
                });
    }

    @Test
    void consumerOnlyNetworkPreparationDoesNotRequireServerPolicy() {
        SecurityTestTransportFactory factory = new SecurityTestTransportFactory();
        SecurityTestTransportFactory.networkRunner(runner, "security-preflight", factory, false)
                .withPropertyValues("otryx.rpc.server.enabled=false")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(RpcAuthorizer.class);
                    OtryxRpcRuntimeCoordinator runtime = context.getBean(OtryxRpcRuntimeCoordinator.class);
                    assertThat(runtime.clientIfCreated()).isEmpty();
                    assertThat(runtime.serverIfCreated()).isEmpty();
                    assertThat(factory.clientCreates).hasValue(1);
                    assertThat(factory.serverCreates).hasValue(0);
                    assertThat(factory.starts).hasValue(0);
                    assertThat(factory.requests).hasValue(0);
                });
        assertThat(factory.closes).hasValue(1);
    }
}
