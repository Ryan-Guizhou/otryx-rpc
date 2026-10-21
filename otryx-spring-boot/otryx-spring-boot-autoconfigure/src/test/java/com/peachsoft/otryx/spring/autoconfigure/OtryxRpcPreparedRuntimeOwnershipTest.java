package com.peachsoft.otryx.spring.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

import com.peachsoft.otryx.api.RpcEndpoint;
import com.peachsoft.otryx.api.RpcTarget;
import com.peachsoft.otryx.registry.ApplicationRegistry;
import com.peachsoft.otryx.registry.RegistrySecurityException;
import com.peachsoft.otryx.registry.RegistrySecurityFailure;
import com.peachsoft.otryx.registry.memory.MemoryApplicationRegistry;
import com.peachsoft.otryx.spring.runtime.OtryxRpcRuntimeCoordinator;
import com.peachsoft.otryx.transport.RpcRequestHandler;
import com.peachsoft.otryx.transport.RpcTransportClient;
import com.peachsoft.otryx.transport.RpcTransportFactory;
import com.peachsoft.otryx.transport.RpcTransportOptions;
import com.peachsoft.otryx.transport.RpcTransportResponse;
import com.peachsoft.otryx.transport.RpcTransportSecurityDescriptor;
import com.peachsoft.otryx.transport.RpcTransportServer;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * 准备对象被消费或回滚后不得通过遗留 Builder 再次激活运行时。
 *
 * <p>使用实际 Core 成员状态机和受控描述夹具，不作为 TLS 验收证据。
 *
 * @Author OpenAI
 * @Version 1.0.0-SNAPSHOT
 * @CreateTime 2026/10/19 11:45
 */
@Timeout(20)
class OtryxRpcPreparedRuntimeOwnershipTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(OtryxRpcAutoConfiguration.class))
            .withPropertyValues("spring.application.name=prepared-ownership",
                    "otryx.rpc.registry.control-plane-timeout=1s");

    @Test
    void rejectedFirstMembershipMustMakeBothPreparedCapabilitiesTerminal() {
        SecurityTestTransportFactory factory = new SecurityTestTransportFactory();
        MemoryApplicationRegistry registry = spy(new MemoryApplicationRegistry());
        AtomicInteger registrations = new AtomicInteger();
        doAnswer(invocation -> registrations.incrementAndGet() == 1
                ? CompletableFuture.failedFuture(new IllegalStateException("synthetic first registration failure"))
                : invocation.callRealMethod()).when(registry).register(any());
        SecurityTestTransportFactory.networkRunner(runner, "prepared-ownership", factory)
                .withBean(ApplicationRegistry.class, () -> registry)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    OtryxRpcRuntimeCoordinator runtime = context.getBean(OtryxRpcRuntimeCoordinator.class);
                    assertThat(factory.closes).hasValue(0);
                    assertThatThrownBy(runtime::client).isInstanceOf(IllegalStateException.class)
                            .hasMessageContaining("membership");
                    assertThat(registrations).hasValue(1);
                    assertThat(factory.closes).hasValue(2);
                    assertThatThrownBy(runtime::client).isInstanceOf(IllegalStateException.class)
                            .hasMessageContaining("startup failed");
                    assertThatThrownBy(runtime::server).isInstanceOf(IllegalStateException.class)
                            .hasMessageContaining("startup failed");
                    assertThat(registrations).hasValue(1);
                    assertThat(factory.clientCreates).hasValue(1);
                    assertThat(factory.serverCreates).hasValue(1);
                    assertThat(factory.starts).hasValue(0);
                    assertThat(factory.requests).hasValue(0);
                    assertThat(runtime.clientIfCreated()).isEmpty();
                    assertThat(runtime.serverIfCreated()).isEmpty();
                });
        assertThat(factory.closes).hasValue(2);
    }

    @ParameterizedTest
    @EnumSource(value = RegistrySecurityFailure.class,
            names = {"TLS_REJECTED", "AUTHENTICATION_REJECTED"})
    void rejectedMembershipPreservesSanitizedRegistrySecurityFailure(RegistrySecurityFailure reason) {
        SecurityTestTransportFactory factory = new SecurityTestTransportFactory();
        MemoryApplicationRegistry registry = spy(new MemoryApplicationRegistry());
        AtomicInteger registrations = new AtomicInteger();
        CompletionException rejected = new CompletionException("synthetic-private-outer-wrapper",
                new ExecutionException("synthetic-private-inner-wrapper", new RegistrySecurityException(reason)));
        doAnswer(invocation -> {
            registrations.incrementAndGet();
            return CompletableFuture.failedFuture(rejected);
        }).when(registry).register(any());
        SecurityTestTransportFactory.networkRunner(runner, "prepared-ownership", factory)
                .withBean(ApplicationRegistry.class, () -> registry)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    OtryxRpcRuntimeCoordinator runtime = context.getBean(OtryxRpcRuntimeCoordinator.class);
                    assertThatThrownBy(runtime::client).isExactlyInstanceOf(RegistrySecurityException.class)
                            .hasMessage("Registry security rejected: " + reason).hasNoCause()
                            .satisfies(failure -> {
                                assertThat(((RegistrySecurityException) failure).reason()).isEqualTo(reason);
                                assertThat(failure.getSuppressed()).isEmpty();
                            });
                    assertThat(registrations).hasValue(1);
                    assertThat(factory.closes).hasValue(2);
                    assertThat(factory.starts).hasValue(0);
                    assertThat(factory.requests).hasValue(0);
                    assertThat(runtime.clientIfCreated()).isEmpty();
                    assertThat(runtime.serverIfCreated()).isEmpty();
                    assertThatThrownBy(runtime::client).hasMessageContaining("startup failed");
                    assertThatThrownBy(runtime::server).hasMessageContaining("startup failed");
                    assertThat(registrations).hasValue(1);
                    assertThat(factory.clientCreates).hasValue(1);
                    assertThat(factory.serverCreates).hasValue(1);
                });
        assertThat(factory.closes).hasValue(2);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void failureInsideCoreBuildMustConsumePreparedOwnershipAndPreventRetry(boolean serverFailure) {
        SecurityTestTransportFactory actual = new SecurityTestTransportFactory();
        BuildFailureFactory factory = new BuildFailureFactory(actual, serverFailure);
        MemoryApplicationRegistry registry = spy(new MemoryApplicationRegistry());
        SecurityTestTransportFactory.networkRunner(runner, "prepared-ownership", actual)
                .withBean("buildFailureFactory", RpcTransportFactory.class, () -> factory,
                        definition -> definition.setPrimary(true))
                .withBean(ApplicationRegistry.class, () -> registry)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    OtryxRpcRuntimeCoordinator runtime = context.getBean(OtryxRpcRuntimeCoordinator.class);
                    // 一次外部 validate 后再一次 Core build.validate，后者才失败。
                    int rejectedRead = factory.descriptions.get() + 2;
                    factory.rejectAt.set(rejectedRead);
                    assertThatThrownBy(() -> {
                        if (serverFailure) {
                            runtime.server();
                        } else {
                            runtime.client();
                        }
                    }).isInstanceOf(IllegalStateException.class)
                            .hasMessageContaining("transport security capabilities");
                    assertThat(factory.descriptions).hasValue(rejectedRead);
                    assertThat(actual.closes).hasValue(2);
                    factory.rejectAt.set(Integer.MAX_VALUE);
                    assertThatThrownBy(runtime::server).isInstanceOf(IllegalStateException.class)
                            .hasMessageContaining("startup failed");
                    assertThatThrownBy(runtime::client).isInstanceOf(IllegalStateException.class)
                            .hasMessageContaining("startup failed");
                    assertThat(factory.descriptions).hasValue(rejectedRead);
                    assertThat(actual.clientCreates).hasValue(1);
                    assertThat(actual.serverCreates).hasValue(1);
                    assertThat(actual.starts).hasValue(0);
                    assertThat(actual.requests).hasValue(0);
                    verify(registry, never()).register(any());
                });
        assertThat(actual.closes).hasValue(2);
    }

    private static final class BuildFailureFactory implements RpcTransportFactory {
        private final SecurityTestTransportFactory delegate;
        private final boolean serverFailure;
        private final AtomicInteger descriptions = new AtomicInteger();
        private final AtomicInteger rejectAt = new AtomicInteger(Integer.MAX_VALUE);

        private BuildFailureFactory(SecurityTestTransportFactory delegate, boolean serverFailure) {
            this.delegate = delegate;
            this.serverFailure = serverFailure;
        }

        @Override
        public RpcTransportClient createClient(RpcTransportOptions options) {
            RpcTransportClient client = delegate.createClient(options);
            return new RpcTransportClient() {
                @Override
                public CompletionStage<RpcTransportResponse> request(
                        RpcTarget target, byte[] frame, Duration timeout) {
                    return client.request(target, frame, timeout);
                }

                @Override
                public RpcTransportSecurityDescriptor securityDescriptor() {
                    return !serverFailure && descriptions.incrementAndGet() == rejectAt.get()
                            ? RpcTransportSecurityDescriptor.unknown() : client.securityDescriptor();
                }

                @Override
                public void close() {
                    client.close();
                }
            };
        }

        @Override
        public RpcTransportServer createServer(RpcTransportOptions options) {
            RpcTransportServer server = delegate.createServer(options);
            return new RpcTransportServer() {
                @Override
                public CompletionStage<RpcEndpoint> start(RpcEndpoint endpoint, RpcRequestHandler handler) {
                    return server.start(endpoint, handler);
                }

                @Override
                public RpcTransportSecurityDescriptor securityDescriptor() {
                    return serverFailure && descriptions.incrementAndGet() == rejectAt.get()
                            ? RpcTransportSecurityDescriptor.unknown() : server.securityDescriptor();
                }

                @Override
                public void close() {
                    server.close();
                }
            };
        }
    }
}
