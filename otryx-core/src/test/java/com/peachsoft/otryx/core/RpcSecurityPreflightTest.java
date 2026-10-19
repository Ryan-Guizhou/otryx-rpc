package com.peachsoft.otryx.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.peachsoft.otryx.api.ApplicationKey;
import com.peachsoft.otryx.api.RpcAuthenticationException;
import com.peachsoft.otryx.api.RpcMethodDescriptor;
import com.peachsoft.otryx.api.RpcSecurityProfile;
import com.peachsoft.otryx.codec.RpcCodecRegistry;
import com.peachsoft.otryx.codec.RpcCodecSecurityDescriptor;
import com.peachsoft.otryx.codec.RpcMethodCodec;
import com.peachsoft.otryx.observability.RpcSecurityMode;
import com.peachsoft.otryx.transport.RpcTransportSecurityDescriptor;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Builder 必须基于实际组件在成员发布、监听、请求及 Runtime 创建之前拒绝不完整安全装配。
 *
 * @Author OpenAI
 * @Version 1.0.0-SNAPSHOT
 * @CreateTime 2026/10/19 11:18
 */
class RpcSecurityPreflightTest {
    @Test
    void bothBuildersRequireAnExplicitProfileBeforeAnyRuntimeSideEffect() throws Exception {
        try (var control = new RpcSecurityTestFixtures.ControlPlane()) {
            var client = new RpcSecurityTestFixtures.ClientTransport(control.clock);
            var server = new RpcSecurityTestFixtures.ServerTransport(control.clock);
            var codecs = RpcCodecRegistry.of(new RpcSecurityTestFixtures.TestCodec());
            assertThrows(IllegalStateException.class, () -> control.clientBuilder(client, codecs).build());
            assertThrows(IllegalStateException.class, () -> control.serverBuilder(server, codecs).build());
            assertEquals(0, client.requests.get());
            assertEquals(0, server.starts.get());
            assertEquals(0, client.closes.get());
            assertEquals(0, server.closes.get());
            assertTrue(control.registry.lookup(RpcSecurityTestFixtures.CONSUMER)
                    .toCompletableFuture().join().instances().isEmpty());
            assertTrue(control.registry.lookup(RpcSecurityTestFixtures.PROVIDER)
                    .toCompletableFuture().join().instances().isEmpty());
        }
    }

    @Test
    void networkProviderRequiresExplicitPolicyAndValidateHasNoSideEffects() throws Exception {
        try (var control = new RpcSecurityTestFixtures.ControlPlane()) {
            var transport = new RpcSecurityTestFixtures.ServerTransport(control.clock);
            var builder = control.serverBuilder(transport, RpcCodecRegistry.of(new RpcSecurityTestFixtures.TestCodec()))
                    .securityProfile(RpcSecurityProfile.NETWORK_MTLS);
            assertThrows(IllegalStateException.class, builder::validate);
            builder.authorizer(RpcAuthorizer.denyAll()).validate();
            builder.validate();
            assertEquals(0, transport.starts.get());
            assertTrue(control.registry.lookup(RpcSecurityTestFixtures.PROVIDER)
                    .toCompletableFuture().join().instances().isEmpty());
        }
    }

    @Test
    void unknownOneWayTlsAndMissingPeerDeliveryAreRejected() throws Exception {
        try (var control = new RpcSecurityTestFixtures.ControlPlane()) {
            var transport = new RpcSecurityTestFixtures.ClientTransport(control.clock);
            var builder = control.clientBuilder(transport, RpcCodecRegistry.of(new RpcSecurityTestFixtures.TestCodec()))
                    .securityProfile(RpcSecurityProfile.NETWORK_MTLS);
            var secure = transport.descriptor;
            transport.descriptor = RpcTransportSecurityDescriptor.unknown();
            assertThrows(IllegalStateException.class, builder::validate);
            transport.descriptor = new RpcTransportSecurityDescriptor(secure.state(), RpcSecurityMode.TLS,
                    secure.localIdentity(), secure.capabilities(), secure.enabledCodecIds());
            assertThrows(IllegalStateException.class, builder::validate);
            var capabilities = new java.util.HashSet<>(secure.capabilities());
            capabilities.remove(RpcTransportSecurityDescriptor.Capability.PEER_IDENTITY_DELIVERY);
            transport.descriptor = new RpcTransportSecurityDescriptor(secure.state(), secure.mode(),
                    secure.localIdentity(), capabilities, secure.enabledCodecIds());
            assertThrows(IllegalStateException.class, builder::validate);
            assertEquals(0, transport.requests.get());
        }
    }

    @Test
    void actualLocalBindingPreservesStableTypedRejectionReasons() throws Exception {
        try (var control = new RpcSecurityTestFixtures.ControlPlane()) {
            var transport = new RpcSecurityTestFixtures.ClientTransport(control.clock);
            var builder = control.clientBuilder(transport, RpcCodecRegistry.of(new RpcSecurityTestFixtures.TestCodec()))
                    .securityProfile(RpcSecurityProfile.NETWORK_MTLS);
            var secure = transport.descriptor;
            ApplicationKey[] applications = {new ApplicationKey("different", "consumer"),
                    new ApplicationKey(RpcSecurityTestFixtures.NAMESPACE, "different"),
                    RpcSecurityTestFixtures.CONSUMER};
            RpcAuthenticationException.Reason[] reasons = {RpcAuthenticationException.Reason.NAMESPACE_MISMATCH,
                    RpcAuthenticationException.Reason.APPLICATION_MISMATCH,
                    RpcAuthenticationException.Reason.IDENTITY_EXPIRED};
            for (int index = 0; index < applications.length; index++) {
                transport.descriptor = new RpcTransportSecurityDescriptor(secure.state(), secure.mode(),
                        Optional.of(RpcSecurityTestFixtures.binding(applications[index],
                                control.clock.instant().plusSeconds(index == 2 ? 0 : 60))),
                        secure.capabilities(), secure.enabledCodecIds());
                assertEquals(reasons[index],
                        assertThrows(RpcAuthenticationException.class, builder::validate).reason());
            }
            transport.descriptor = new RpcTransportSecurityDescriptor(secure.state(), secure.mode(), Optional.empty(),
                    secure.capabilities(), secure.enabledCodecIds());
            assertEquals(RpcAuthenticationException.Reason.IDENTITY_UNBOUND,
                    assertThrows(RpcAuthenticationException.class, builder::validate).reason());
        }
    }

    @Test
    void aSecondaryUnsafeCodecCannotHideBehindTheSafeDefault() throws Exception {
        try (var control = new RpcSecurityTestFixtures.ControlPlane()) {
            var transport = new RpcSecurityTestFixtures.ClientTransport(control.clock);
            RpcSecurityTestFixtures.TestCodec unsafe = new RpcSecurityTestFixtures.TestCodec() {
                @Override
                public byte code() {
                    return 2;
                }
            };
            unsafe.descriptor = RpcCodecSecurityDescriptor.unknown();
            var current = transport.descriptor;
            transport.descriptor = new RpcTransportSecurityDescriptor(current.state(), current.mode(),
                    current.localIdentity(), current.capabilities(), Set.of((byte) 1, (byte) 2));
            var builder = control.clientBuilder(transport,
                    RpcCodecRegistry.of(new RpcSecurityTestFixtures.TestCodec(), unsafe))
                    .securityProfile(RpcSecurityProfile.NETWORK_MTLS);
            assertThrows(IllegalStateException.class, builder::validate);
            assertEquals(0, transport.requests.get());
        }
    }

    @Test
    void unsafeBoundMethodCodecIsRejectedBeforeProviderPublication() throws Exception {
        try (var control = new RpcSecurityTestFixtures.ControlPlane()) {
            var transport = new RpcSecurityTestFixtures.ServerTransport(control.clock);
            RpcSecurityTestFixtures.TestCodec codec = new RpcSecurityTestFixtures.TestCodec() {
                @Override
                public RpcMethodCodec bind(RpcMethodDescriptor descriptor) {
                    return new MethodCodec(new RpcSecurityTestFixtures.TestCodec().bind(descriptor), (byte) 1,
                            RpcCodecSecurityDescriptor.unknown());
                }
            };
            try (OtryxRpcServer server = control.serverBuilder(transport, RpcCodecRegistry.of(codec))
                    .securityProfile(RpcSecurityProfile.NETWORK_MTLS).authorizer(RpcAuthorizer.denyAll()).build()) {
                assertThrows(IllegalStateException.class,
                        () -> server.registerService(Api.class, new Implementation(), "1.0.0", "default"));
                assertEquals(0, transport.starts.get());
                assertTrue(control.registry.lookup(RpcSecurityTestFixtures.PROVIDER)
                        .toCompletableFuture().join().instances().isEmpty());
            }
        }
    }

    @Test
    void startRevalidatesExpiredLocalIdentityBeforePublishing() throws Exception {
        try (var control = new RpcSecurityTestFixtures.ControlPlane()) {
            var transport = new RpcSecurityTestFixtures.ServerTransport(control.clock);
            try (OtryxRpcServer server = control.serverBuilder(transport,
                    RpcCodecRegistry.of(new RpcSecurityTestFixtures.TestCodec()))
                    .securityProfile(RpcSecurityProfile.NETWORK_MTLS).authorizer(RpcAuthorizer.denyAll()).build()) {
                server.registerService(Api.class, new Implementation(), "1.0.0", "default");
                control.clock.advance(java.time.Duration.ofHours(1));
                var failure = assertThrows(java.util.concurrent.CompletionException.class,
                        () -> server.start().toCompletableFuture().join());
                assertEquals(RpcAuthenticationException.Reason.IDENTITY_EXPIRED,
                        ((RpcAuthenticationException) failure.getCause()).reason());
                assertEquals(0, transport.starts.get());
                assertTrue(control.registry.lookup(RpcSecurityTestFixtures.PROVIDER)
                        .toCompletableFuture().join().instances().isEmpty());
            }
        }
    }

    @Test
    void nullProfileAndUnsafeMethodCodecIdFailClosed() throws Exception {
        assertThrows(NullPointerException.class, () -> OtryxRpcClient.builder().securityProfile(null));
        assertThrows(NullPointerException.class, () -> OtryxRpcServer.builder().securityProfile(null));
        var codec = new RpcSecurityTestFixtures.TestCodec();
        var descriptor = RpcMethodDescriptor.from(new com.peachsoft.otryx.api.ServiceKey(
                Api.class.getName(), "1.0.0", "default"), Api.class.getMethod("call"));
        assertThrows(IllegalStateException.class, () -> RpcSecurityChecks.method(RpcSecurityProfile.NETWORK_MTLS,
                new MethodCodec(codec.bind(descriptor), (byte) 2, codec.securityDescriptor()), (byte) 1));
    }

    public interface Api {
        String call();
    }

    public static final class Implementation implements Api {
        @Override
        public String call() {
            return "ok";
        }
    }

    private record MethodCodec(RpcMethodCodec delegate, byte codecId,
            RpcCodecSecurityDescriptor securityDescriptor) implements RpcMethodCodec {
        @Override
        public byte[] encodeArguments(Object[] arguments) {
            return delegate.encodeArguments(arguments);
        }

        @Override
        public Object[] decodeArguments(byte[] payload) {
            return delegate.decodeArguments(payload);
        }

        @Override
        public byte[] encodeResult(Object value) {
            return delegate.encodeResult(value);
        }

        @Override
        public Object decodeResult(byte[] payload) {
            return delegate.decodeResult(payload);
        }
    }
}
