package com.peachsoft.otryx.spring.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import com.peachsoft.otryx.codec.RpcCodecRegistry;
import com.peachsoft.otryx.codec.fory.ForyRpcCodec;
import com.peachsoft.otryx.codec.fory.ForyRpcSecurityOptions;
import com.peachsoft.otryx.registry.ApplicationRegistry;
import com.peachsoft.otryx.registry.memory.MemoryApplicationRegistry;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * 验证默认 Fory 的早期校验不约束用户已替换的实际安全 Codec 注册表。
 *
 * @Author OpenAI
 * @Version 1.0.0-SNAPSHOT
 * @CreateTime 2026/10/19 12:10
 */
class OtryxRpcDefaultCodecProfileTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(OtryxRpcAutoConfiguration.class))
            .withPropertyValues("spring.application.name=default-codec-profile");

    @Test
    void unsafeDefaultForyFailsBeforeRegistryOrTransportCreation() {
        AtomicInteger registryCreates = new AtomicInteger();
        SecurityTestTransportFactory factory = new SecurityTestTransportFactory();
        SecurityTestTransportFactory.networkRunner(runner, "default-codec-profile", factory)
                .withBean(ApplicationRegistry.class, () -> {
                    registryCreates.incrementAndGet();
                    return new MemoryApplicationRegistry();
                })
                .withPropertyValues("otryx.rpc.codec.fory.mode=TRUSTED_COMPATIBILITY")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasStackTraceContaining("codec.fory.mode");
                    assertThat(registryCreates).hasValue(0);
                    assertThat(factory.clientCreates).hasValue(0);
                    assertThat(factory.serverCreates).hasValue(0);
                });
    }

    @Test
    void actualStrictReplacementDoesNotRequireUnusedDefaultForyMode() {
        SecurityTestTransportFactory factory = new SecurityTestTransportFactory();
        RpcCodecRegistry replacement = RpcCodecRegistry.of(new ForyRpcCodec(
                ForyRpcSecurityOptions.strictAllowlist(Set.of("com.peachsoft.otryx.spring.autoconfigure.*"))));
        SecurityTestTransportFactory.networkRunner(runner, "default-codec-profile", factory)
                .withBean(RpcCodecRegistry.class, () -> replacement)
                .withPropertyValues("otryx.rpc.codec.fory.mode=TRUSTED_COMPATIBILITY")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(RpcCodecRegistry.class)).isSameAs(replacement);
                    assertThat(factory.clientCreates).hasValue(1);
                    assertThat(factory.serverCreates).hasValue(1);
                    assertThat(factory.starts).hasValue(0);
                    assertThat(factory.requests).hasValue(0);
                });
        assertThat(factory.closes).hasValue(2);
    }
}
