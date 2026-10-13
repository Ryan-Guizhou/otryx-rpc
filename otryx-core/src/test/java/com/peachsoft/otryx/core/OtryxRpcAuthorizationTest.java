package com.peachsoft.otryx.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.peachsoft.otryx.api.RpcEndpoint;
import com.peachsoft.otryx.api.RpcIds;
import com.peachsoft.otryx.api.RpcPeerIdentity;
import com.peachsoft.otryx.api.RpcStatus;
import com.peachsoft.otryx.api.ServiceInstance;
import com.peachsoft.otryx.api.ServiceKey;
import com.peachsoft.otryx.codec.RpcCodec;
import com.peachsoft.otryx.codec.RpcCodecRegistry;
import com.peachsoft.otryx.protocol.RpcProtocolCodec;
import com.peachsoft.otryx.registry.ServiceRegistrar;
import com.peachsoft.otryx.transport.RpcRequestHandler;
import com.peachsoft.otryx.transport.RpcTransportServer;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * Provider 连接身份、异步授权和解码前拒绝的完整回归。
 *
 * @Author OpenAI
 * @Version 1.0.0-SNAPSHOT
 * @CreateTime 2026/10/13 13:43
 */
class OtryxRpcAuthorizationTest {

    private static final RpcEndpoint REMOTE = new RpcEndpoint("127.0.0.1", 20000);

    @Test
    void authorizationDeniedMustNotInvokeOrDecodeBusinessPayload()
            throws Exception {
        TestTransport transport = new TestTransport();
        TestCodec codec = new TestCodec();
        CountingService service = new CountingService();
        OtryxRpcServer server = server(transport, codec, service)
                .authorizer(RpcAuthorizer.requireMutualTls())
                .build();
        try {
            server.registerService(Api.class, service, "1.0.0", "default");
            server.start().toCompletableFuture().join();

            byte[] reply = transport.call(
                            RpcPeerIdentity.anonymous(),
                            request(Map.of("x-claimed-identity", "CN=admin")))
                    .toCompletableFuture().get(3, TimeUnit.SECONDS);

            assertEquals(RpcStatus.UNAVAILABLE, RpcProtocolCodec.view(reply).status());
            assertEquals(0, codec.decodes.get());
            assertEquals(0, service.invocations.get());
        } finally {
            server.close();
        }
    }

    @Test
    void certificateIdentityMustReachServiceAndMethodPolicy()
            throws Exception {
        TestTransport transport = new TestTransport();
        TestCodec codec = new TestCodec();
        CountingService service = new CountingService();
        ServiceKey key = serviceKey();
        int methodId = RpcIds.methodId(Api.class.getMethod("call"));
        RpcPeerIdentity peer = RpcPeerIdentity.verifiedClientCertificate(
                "CN=rpc-client", "cert-sha256-test");
        OtryxRpcServer server = server(transport, codec, service)
                .authorizer(context -> CompletableFuture.completedFuture(
                        context.serviceKey().equals(key)
                                && context.methodId() == methodId
                                && context.peer().equals(peer)
                                && context.remote().equals(REMOTE)))
                .build();
        try {
            server.registerService(Api.class, service, "1.0.0", "default");
            server.start().toCompletableFuture().join();

            byte[] accepted = transport.call(peer, request(Map.of()))
                    .toCompletableFuture().get(3, TimeUnit.SECONDS);
            assertEquals(RpcStatus.OK, RpcProtocolCodec.view(accepted).status());
            assertEquals(1, codec.decodes.get());
            assertEquals(1, service.invocations.get());

            byte[] rejected = transport.call(RpcPeerIdentity.anonymous(), request(Map.of()))
                    .toCompletableFuture().get(3, TimeUnit.SECONDS);
            assertEquals(RpcStatus.UNAVAILABLE, RpcProtocolCodec.view(rejected).status());
            assertEquals(1, codec.decodes.get());
            assertEquals(1, service.invocations.get());
        } finally {
            server.close();
        }
    }

    @Test
    void expiredAuthorizationMustNotReleaseUnfinishedPolicyPermit()
            throws Exception {
        TestTransport transport = new TestTransport();
        TestCodec codec = new TestCodec();
        CountingService service = new CountingService();
        CompletableFuture<Boolean> unfinished = new CompletableFuture<>();
        OtryxRpcServer server = server(transport, codec, service)
                .authorizer(context -> unfinished)
                .maxPendingAuthorizations(1)
                .authorizationTimeout(Duration.ofMillis(60))
                .build();
        try {
            server.registerService(Api.class, service, "1.0.0", "default");
            server.start().toCompletableFuture().join();

            CompletionStage<byte[]> first = transport.call(
                    RpcPeerIdentity.anonymous(), request(Map.of()));
            byte[] expired = first.toCompletableFuture().get(3, TimeUnit.SECONDS);
            assertEquals(RpcStatus.UNAVAILABLE, RpcProtocolCodec.view(expired).status());

            byte[] full = transport.call(RpcPeerIdentity.anonymous(), request(Map.of()))
                    .toCompletableFuture().get(3, TimeUnit.SECONDS);
            assertEquals(RpcStatus.OVERLOADED, RpcProtocolCodec.view(full).status());
            assertEquals(0, codec.decodes.get());
            assertEquals(0, service.invocations.get());

            unfinished.complete(true);
            byte[] accepted = transport.call(RpcPeerIdentity.anonymous(), request(Map.of()))
                    .toCompletableFuture().get(3, TimeUnit.SECONDS);
            assertEquals(RpcStatus.OK, RpcProtocolCodec.view(accepted).status());
            assertEquals(1, service.invocations.get());
            assertFalse(first.toCompletableFuture().isCancelled());
        } finally {
            unfinished.complete(false);
            server.close();
        }
    }

    @Test
    void pendingAuthorizationFramesMustHaveIndependentGlobalByteLimit()
            throws Exception {
        TestTransport transport = new TestTransport();
        TestCodec codec = new TestCodec();
        CountingService service = new CountingService();
        CompletableFuture<Boolean> firstPolicy = new CompletableFuture<>();
        AtomicInteger decisions = new AtomicInteger();
        byte[] fullRequest = request(Map.of());
        OtryxRpcServer server = server(transport, codec, service)
                .authorizer(context -> decisions.incrementAndGet() == 1
                        ? firstPolicy
                        : CompletableFuture.completedFuture(true))
                .maxPendingAuthorizations(2)
                .maxPendingAuthorizationBytes(fullRequest.length)
                .build();
        try {
            server.registerService(Api.class, service, "1.0.0", "default");
            server.start().toCompletableFuture().join();

            CompletionStage<byte[]> first =
                    transport.call(RpcPeerIdentity.anonymous(), fullRequest);
            byte[] overBudget = transport.call(
                            RpcPeerIdentity.anonymous(), fullRequest)
                    .toCompletableFuture().get(3, TimeUnit.SECONDS);
            assertEquals(
                    RpcStatus.OVERLOADED,
                    RpcProtocolCodec.view(overBudget).status());
            assertEquals(1, decisions.get());
            assertEquals(0, codec.decodes.get());

            firstPolicy.complete(true);
            assertEquals(RpcStatus.OK, RpcProtocolCodec.view(
                    first.toCompletableFuture().get(3, TimeUnit.SECONDS)).status());

            byte[] recovered = transport.call(
                            RpcPeerIdentity.anonymous(), fullRequest)
                    .toCompletableFuture().get(3, TimeUnit.SECONDS);
            assertEquals(RpcStatus.OK, RpcProtocolCodec.view(recovered).status());
            assertEquals(2, service.invocations.get());
        } finally {
            firstPolicy.complete(false);
            server.close();
        }
    }

    @Test
    void canceledAuthorizationMustReleaseFrameBytesAfterLogicalTimeout()
            throws Exception {
        TestTransport transport = new TestTransport();
        TestCodec codec = new TestCodec();
        CountingService service = new CountingService();
        CompletableFuture<Boolean> firstPolicy = new CompletableFuture<>();
        AtomicInteger decisions = new AtomicInteger();
        byte[] frame = request(Map.of());
        OtryxRpcServer server = server(transport, codec, service)
                .authorizer(context -> decisions.incrementAndGet() == 1
                        ? firstPolicy
                        : CompletableFuture.completedFuture(true))
                .maxPendingAuthorizations(2)
                .maxPendingAuthorizationBytes(frame.length)
                .authorizationTimeout(Duration.ofMillis(80))
                .build();
        try {
            server.registerService(Api.class, service, "1.0.0", "default");
            server.start().toCompletableFuture().join();
            CompletableFuture<byte[]> canceled = transport.call(
                    RpcPeerIdentity.anonymous(), frame).toCompletableFuture();
            canceled.cancel(true);

            byte[] initiallyOverloaded = transport.call(
                            RpcPeerIdentity.anonymous(), frame)
                    .toCompletableFuture().get(3, TimeUnit.SECONDS);
            assertEquals(RpcStatus.OVERLOADED,
                    RpcProtocolCodec.view(initiallyOverloaded).status());

            Thread.sleep(200L);
            byte[] recovered = transport.call(
                            RpcPeerIdentity.anonymous(), frame)
                    .toCompletableFuture().get(3, TimeUnit.SECONDS);
            assertEquals(RpcStatus.OK, RpcProtocolCodec.view(recovered).status());
            assertEquals(1, service.invocations.get());
        } finally {
            firstPolicy.complete(false);
            server.close();
        }
    }

    @Test
    void authorizationMustNotExtendRelativeRequestDeadline()
            throws Exception {
        assertAuthorizationDeadlineExpires(
                System.currentTimeMillis() + 10_000L, 20L);
    }

    @Test
    void authorizationMustRecheckLegacyEpochOnlyDeadline()
            throws Exception {
        assertAuthorizationDeadlineExpires(
                System.currentTimeMillis() + 30L, 0L);
    }

    private static void assertAuthorizationDeadlineExpires(
            long epochDeadline, long remainingBudgetMillis) throws Exception {
        TestTransport transport = new TestTransport();
        TestCodec codec = new TestCodec();
        CountingService service = new CountingService();
        CompletableFuture<Boolean> authorization = new CompletableFuture<>();
        OtryxRpcServer server = server(transport, codec, service)
                .authorizer(context -> authorization)
                .authorizationTimeout(Duration.ofSeconds(2))
                .build();
        try {
            server.registerService(Api.class, service, "1.0.0", "default");
            server.start().toCompletableFuture().join();
            byte[] request = RpcProtocolCodec.encodeRequest(
                    (byte) 1,
                    RpcIds.serviceId(serviceKey()),
                    RpcIds.methodId(Api.class.getMethod("call")),
                    epochDeadline,
                    remainingBudgetMillis,
                    Map.of(),
                    new byte[] {1, 2, 3});
            CompletionStage<byte[]> waiting = transport.call(
                    RpcPeerIdentity.anonymous(), request);
            Thread.sleep(100L);
            authorization.complete(true);
            byte[] denied = waiting.toCompletableFuture().get(3, TimeUnit.SECONDS);
            assertEquals(RpcStatus.DEADLINE_EXCEEDED,
                    RpcProtocolCodec.view(denied).status());
            assertEquals(0, codec.decodes.get());
            assertEquals(0, service.invocations.get());
        } finally {
            authorization.complete(false);
            server.close();
        }
    }

    @Test
    void exceptionalAuthorizerMustFailClosedWithoutBusinessExecution()
            throws Exception {
        TestTransport transport = new TestTransport();
        TestCodec codec = new TestCodec();
        CountingService service = new CountingService();
        OtryxRpcServer server = server(transport, codec, service)
                .authorizer(context -> CompletableFuture.failedFuture(
                        new IllegalStateException("sensitive-token-must-not-leak")))
                .build();
        try {
            server.registerService(Api.class, service, "1.0.0", "default");
            server.start().toCompletableFuture().join();
            byte[] response = transport.call(RpcPeerIdentity.anonymous(), request(Map.of()))
                    .toCompletableFuture().get(3, TimeUnit.SECONDS);
            assertEquals(RpcStatus.UNAVAILABLE, RpcProtocolCodec.view(response).status());
            assertEquals(0, codec.decodes.get());
            assertEquals(0, service.invocations.get());
        } finally {
            server.close();
        }
    }

    private static OtryxRpcServer.Builder server(
            TestTransport transport, TestCodec codec, CountingService service) {
        return OtryxRpcServer.builder()
                .serviceRegistrar(new NoopRegistrar())
                .transportServer(transport)
                .codecRegistry(RpcCodecRegistry.of(codec))
                .bindEndpoint(new RpcEndpoint("127.0.0.1", 19091));
    }

    private static ServiceKey serviceKey() {
        return new ServiceKey(Api.class.getName(), "1.0.0", "default");
    }

    private static byte[] request(Map<String, String> metadata) throws Exception {
        Method method = Api.class.getMethod("call");
        return RpcProtocolCodec.encodeRequest(
                (byte) 1,
                RpcIds.serviceId(serviceKey()),
                RpcIds.methodId(method),
                0L,
                0L,
                metadata,
                new byte[] {1, 2, 3});
    }

    public interface Api {
        String call();
    }

    public static final class CountingService implements Api {
        private final AtomicInteger invocations = new AtomicInteger();

        @Override
        public String call() {
            invocations.incrementAndGet();
            return "ok";
        }
    }

    private static final class TestCodec implements RpcCodec {
        private final AtomicInteger decodes = new AtomicInteger();

        @Override
        public byte code() {
            return 1;
        }

        @Override
        public byte[] encode(Object value) {
            return new byte[0];
        }

        @Override
        public <T> T decode(byte[] payload, Class<T> type) {
            decodes.incrementAndGet();
            if (type == Object[].class) {
                return type.cast(new Object[0]);
            }
            return null;
        }
    }

    private static final class NoopRegistrar implements ServiceRegistrar {
        @Override
        public CompletionStage<Void> register(ServiceInstance instance) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletionStage<Void> unregister(ServiceInstance instance) {
            return CompletableFuture.completedFuture(null);
        }
    }

    private static final class TestTransport implements RpcTransportServer {
        private RpcRequestHandler handler;

        @Override
        public CompletionStage<RpcEndpoint> start(
                RpcEndpoint bind, RpcRequestHandler requestHandler) {
            handler = requestHandler;
            return CompletableFuture.completedFuture(bind);
        }

        CompletionStage<byte[]> call(RpcPeerIdentity peer, byte[] frame) {
            return handler.handle(REMOTE, peer, frame);
        }

        @Override
        public void close() {
        }
    }
}
