package com.peachsoft.otryx.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.peachsoft.otryx.api.ApplicationKey;
import com.peachsoft.otryx.api.OtryxRpcExecution;
import com.peachsoft.otryx.api.RpcAuthorizationDecision;
import com.peachsoft.otryx.api.RpcCertificateBinding;
import com.peachsoft.otryx.api.RpcEndpoint;
import com.peachsoft.otryx.api.RpcExecutionMode;
import com.peachsoft.otryx.core.RpcProviderExecutionOptions;
import com.peachsoft.otryx.api.RpcIds;
import com.peachsoft.otryx.api.RpcPeerIdentity;
import com.peachsoft.otryx.api.RpcSecurityProfile;
import com.peachsoft.otryx.api.RpcStatus;
import com.peachsoft.otryx.api.ServiceKey;
import com.peachsoft.otryx.codec.RpcCodec;
import com.peachsoft.otryx.codec.RpcCodecRegistry;
import com.peachsoft.otryx.interceptor.RpcInterceptor;
import com.peachsoft.otryx.interceptor.RpcInvocation;
import com.peachsoft.otryx.protocol.RpcProtocolCodec;
import com.peachsoft.otryx.transport.RpcRequestHandler;
import com.peachsoft.otryx.transport.RpcTransportServer;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Provider 的可插拔 Hook 必须晚于实际身份/授权和准入，并与 DIRECT EventLoop 隔离。
 *
 * @Author OpenAI
 * @Version 1.0.0-SNAPSHOT
 * @CreateTime 2026/10/21 16:10
 */
class RpcProviderInterceptorIntegrationTest {
    private static final RpcEndpoint REMOTE = new RpcEndpoint("127.0.0.1", 22000);
    private static final ServiceKey SERVICE = new ServiceKey(Api.class.getName(), "1.0.0", "default");
    private final ApplicationTestControlPlane control = new ApplicationTestControlPlane();

    @AfterEach
    void cleanup() {
        control.close();
    }

    @Test
    void unauthorizedCallMustNeverReachHookOrPayloadDecoder() throws Exception {
        CapturingTransport transport = new CapturingTransport();
        CountingCodec codec = new CountingCodec();
        AtomicInteger hookCalls = new AtomicInteger();
        RpcInterceptor interceptor = new RpcInterceptor() {
            @Override
            public void before(RpcInvocation call) {
                hookCalls.incrementAndGet();
            }
        };
        OtryxRpcServer server = builder(transport, codec)
                .authorizer(RpcAuthorizer.denyAll())
                .interceptors(List.of(interceptor)).interceptorExecutionLimits(2, 2)
                .build().registerService(Api.class, new Service(), "1.0.0", "default");
        try {
            server.start().toCompletableFuture().join();
            byte[] bytes = transport.call(peer(), request()).toCompletableFuture().get(3, TimeUnit.SECONDS);
            assertEquals(RpcStatus.BAD_REQUEST, RpcProtocolCodec.view(bytes).status());
            assertEquals(0, hookCalls.get());
            assertEquals(0, codec.decodes.get());
        } finally {
            server.close();
        }
    }

    @Test
    void acceptedDirectServiceAndHooksMustRunOutsideEventLoopWithVerifiedCaller() throws Exception {
        CapturingTransport transport = new CapturingTransport();
        CountingCodec codec = new CountingCodec();
        AtomicReference<String> businessThread = new AtomicReference<>();
        Service business = new Service(businessThread);
        CountDownLatch after = new CountDownLatch(1);
        AtomicInteger hooks = new AtomicInteger();
        RpcInterceptor interceptor = new RpcInterceptor() {
            @Override
            public void before(RpcInvocation call) {
                assertEquals(RpcInvocation.Side.PROVIDER, call.side());
                assertEquals(SERVICE, call.service());
                assertEquals(new ApplicationKey(ApplicationTestControlPlane.NAMESPACE, "consumer"),
                        call.caller().orElseThrow());
                assertTrue(Thread.currentThread().getName().startsWith("otryx-interceptor-provider-"));
                hooks.incrementAndGet();
            }

            @Override
            public void after(RpcInvocation call) {
                hooks.incrementAndGet();
                after.countDown();
            }
        };
        OtryxRpcServer server = builder(transport, codec)
                .authorizer(context -> CompletableFuture.completedFuture(
                        RpcAuthorizationDecision.allowUntil(context.maximumValidUntil())))
                .interceptors(List.of(interceptor)).interceptorExecutionLimits(2, 2)
                .build().registerService(Api.class, business, "1.0.0", "default");
        ExecutorService eventLoop = Executors.newSingleThreadExecutor(
                task -> new Thread(task, "otryx-test-eventloop"));
        try {
            server.start().toCompletableFuture().join();
            CompletableFuture<byte[]> reply = new CompletableFuture<>();
            eventLoop.execute(() -> transport.callWithDispatcher(peer(), requestUnchecked(), eventLoop)
                    .whenComplete((bytes, error) -> {
                        if (error != null) {
                            reply.completeExceptionally(error);
                        } else {
                            reply.complete(bytes);
                        }
                    }));
            assertEquals(RpcStatus.OK, RpcProtocolCodec.view(reply.get(3, TimeUnit.SECONDS)).status());
            assertTrue(after.await(3, TimeUnit.SECONDS));
            assertEquals(2, hooks.get());
            assertEquals(1, codec.decodes.get());
            assertTrue(businessThread.get().startsWith("otryx-interceptor-provider-"));
        } finally {
            server.close();
            eventLoop.shutdownNow();
        }
    }

    private OtryxRpcServer.Builder builder(CapturingTransport transport, CountingCodec codec) {
        return OtryxRpcServer.builder()
                .securityProfile(RpcSecurityProfile.TRUSTED_DEVELOPMENT)
                .applicationMembership(control.membership())
                .codecRegistry(RpcCodecRegistry.of(codec))
                .transportServer(transport)
                .executionOptions(new RpcProviderExecutionOptions(true, 2, 8))
                .bindEndpoint(new RpcEndpoint("127.0.0.1", 19090));
    }

    private static RpcPeerIdentity peer() {
        Instant now = Instant.now();
        return RpcPeerIdentity.verifiedMutualTls(new RpcCertificateBinding("a".repeat(64),
                new ApplicationKey(ApplicationTestControlPlane.NAMESPACE, "consumer"), now.plusSeconds(30)),
                now.minusSeconds(30), now.plusSeconds(40), now, System.nanoTime());
    }

    private static byte[] request() throws Exception {
        return RpcProtocolCodec.encodeRequest((byte) 1, RpcIds.serviceId(SERVICE),
                RpcIds.methodId(Api.class.getMethod("call")), 0L, 0L,
                Map.of("x-claimed-identity", "forged-caller"), new byte[] {1});
    }

    private static byte[] requestUnchecked() {
        try {
            return request();
        } catch (Exception failure) {
            throw new IllegalStateException("Test RPC request cannot be encoded", failure);
        }
    }

    public interface Api {
        @OtryxRpcExecution(RpcExecutionMode.DIRECT)
        String call();
    }

    public static final class Service implements Api {
        private final AtomicReference<String> executionThread;

        public Service() {
            this(new AtomicReference<>());
        }

        public Service(AtomicReference<String> executionThread) {
            this.executionThread = executionThread;
        }

        @Override
        public String call() {
            executionThread.set(Thread.currentThread().getName());
            return "ok";
        }
    }

    private static final class CountingCodec implements RpcCodec {
        private final AtomicInteger decodes = new AtomicInteger();

        @Override
        public byte code() {
            return 1;
        }

        @Override
        public byte[] encode(Object value) {
            return new byte[] {1};
        }

        @Override
        public <T> T decode(byte[] bytes, Class<T> type) {
            decodes.incrementAndGet();
            if (type == Object[].class) {
                return type.cast(new Object[0]);
            }
            return null;
        }
    }

    private static final class CapturingTransport implements RpcTransportServer {
        private RpcRequestHandler handler;

        @Override
        public CompletionStage<RpcEndpoint> start(RpcEndpoint endpoint, RpcRequestHandler requestHandler) {
            handler = requestHandler;
            return CompletableFuture.completedFuture(endpoint);
        }

        CompletionStage<byte[]> call(RpcPeerIdentity peer, byte[] frame) {
            return handler.handle(REMOTE, peer, frame);
        }

        CompletionStage<byte[]> callWithDispatcher(RpcPeerIdentity peer, byte[] frame,
                java.util.concurrent.Executor dispatcher) {
            return handler.handle(REMOTE, peer, frame, dispatcher);
        }

        @Override
        public void close() {
        }
    }
}
