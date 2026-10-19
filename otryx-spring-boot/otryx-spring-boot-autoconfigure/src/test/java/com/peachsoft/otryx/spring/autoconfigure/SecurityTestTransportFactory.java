package com.peachsoft.otryx.spring.autoconfigure;

import com.peachsoft.otryx.api.RpcEndpoint;
import com.peachsoft.otryx.api.RpcTarget;
import com.peachsoft.otryx.core.RpcAuthorizer;
import com.peachsoft.otryx.transport.RpcRequestHandler;
import com.peachsoft.otryx.transport.RpcTransportClient;
import com.peachsoft.otryx.transport.RpcTransportFactory;
import com.peachsoft.otryx.transport.RpcTransportOptions;
import com.peachsoft.otryx.transport.RpcTransportResponse;
import com.peachsoft.otryx.transport.RpcTransportSecurityDescriptor;
import com.peachsoft.otryx.transport.RpcTransportServer;
import java.time.Duration;
import java.util.EnumSet;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * 只用于验证 Starter 对实际 SPI 描述的接线与启动顺序，不提供真实 TLS 验收证据。
 *
 * @Author OpenAI
 * @Version 1.0.0-SNAPSHOT
 * @CreateTime 2026/10/19 11:30
 */
final class SecurityTestTransportFactory implements RpcTransportFactory {
    final AtomicInteger clientCreates = new AtomicInteger();
    final AtomicInteger serverCreates = new AtomicInteger();
    final AtomicInteger requests = new AtomicInteger();
    final AtomicInteger starts = new AtomicInteger();
    final AtomicInteger closes = new AtomicInteger();
    boolean unknownClient;
    boolean unknownServer;

    static ApplicationContextRunner networkRunner(
            ApplicationContextRunner runner, String applicationName, SecurityTestTransportFactory factory) {
        return networkRunner(runner, applicationName, factory, true);
    }

    static ApplicationContextRunner networkRunner(
            ApplicationContextRunner runner, String applicationName,
            SecurityTestTransportFactory factory, boolean includePolicy) {
        ApplicationContextRunner configured = runner.withBean(RpcTransportFactory.class, () -> factory)
                .withPropertyValues(
                        "otryx.rpc.security-profile=NETWORK_MTLS",
                        "otryx.rpc.transport.security.mode=MTLS",
                        "otryx.rpc.transport.security.certificate-path=fixture-leaf.pem",
                        "otryx.rpc.transport.security.private-key-path=fixture-key.pem",
                        "otryx.rpc.transport.security.trust-certificate-path=fixture-ca.pem",
                        "otryx.rpc.codec.fory.mode=STRICT_ALLOWLIST",
                        "otryx.rpc.codec.fory.allowed-class-patterns[0]=com.peachsoft.otryx.demo.*",
                        "otryx.rpc.transport.security.identity.certificate-bindings[0].certificate-sha256="
                                + "a".repeat(64),
                        "otryx.rpc.transport.security.identity.certificate-bindings[0].application.namespace=default",
                        "otryx.rpc.transport.security.identity.certificate-bindings[0].application.application-name="
                                + applicationName,
                        "otryx.rpc.transport.security.identity.certificate-bindings[0].valid-until="
                                + "2100-01-01T00:00:00Z");
        return includePolicy ? configured.withBean(RpcAuthorizer.class, RpcAuthorizer::denyAll) : configured;
    }

    @Override
    public RpcTransportClient createClient(RpcTransportOptions options) {
        clientCreates.incrementAndGet();
        return new RpcTransportClient() {
            @Override
            public CompletionStage<RpcTransportResponse> request(RpcTarget target, byte[] frame, Duration timeout) {
                requests.incrementAndGet();
                return CompletableFuture.failedFuture(new AssertionError("Preflight must not send RPC"));
            }

            @Override
            public RpcTransportSecurityDescriptor securityDescriptor() {
                return unknownClient ? RpcTransportSecurityDescriptor.unknown() : descriptor(options);
            }

            @Override
            public void close() {
                closes.incrementAndGet();
            }
        };
    }

    @Override
    public RpcTransportServer createServer(RpcTransportOptions options) {
        serverCreates.incrementAndGet();
        return new RpcTransportServer() {
            @Override
            public CompletionStage<RpcEndpoint> start(RpcEndpoint bind, RpcRequestHandler handler) {
                starts.incrementAndGet();
                return CompletableFuture.failedFuture(new AssertionError("Preflight must not listen"));
            }

            @Override
            public RpcTransportSecurityDescriptor securityDescriptor() {
                return unknownServer ? RpcTransportSecurityDescriptor.unknown() : descriptor(options);
            }

            @Override
            public void close() {
                closes.incrementAndGet();
            }
        };
    }

    private static RpcTransportSecurityDescriptor descriptor(RpcTransportOptions options) {
        return new RpcTransportSecurityDescriptor(RpcTransportSecurityDescriptor.State.EFFECTIVE,
                options.security().mode(), options.security().identity().flatMap(identity ->
                        identity.certificateBindings().stream()
                                .filter(binding -> binding.application().equals(identity.localApplication()))
                                .findFirst()),
                Set.copyOf(EnumSet.allOf(RpcTransportSecurityDescriptor.Capability.class)), options.codecIds());
    }
}
