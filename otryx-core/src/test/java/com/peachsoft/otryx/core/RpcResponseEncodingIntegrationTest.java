package com.peachsoft.otryx.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.peachsoft.otryx.api.OtryxRpcExecution;
import com.peachsoft.otryx.api.RpcEndpoint;
import com.peachsoft.otryx.api.RpcExecutionMode;
import com.peachsoft.otryx.api.RpcIds;
import com.peachsoft.otryx.api.RpcSecurityProfile;
import com.peachsoft.otryx.api.RpcStatus;
import com.peachsoft.otryx.api.ServiceKey;
import com.peachsoft.otryx.codec.RpcCodec;
import com.peachsoft.otryx.codec.RpcCodecRegistry;
import com.peachsoft.otryx.observability.RpcObserver;
import com.peachsoft.otryx.protocol.RpcProtocolCodec;
import com.peachsoft.otryx.transport.RpcRequestHandler;
import com.peachsoft.otryx.transport.RpcTransportServer;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * 验证同步和异步 Provider 输出编码容量失败归类与真实字节资源归还。
 *
 * @Author OpenAI
 * @Version 1.0.0-SNAPSHOT
 * @CreateTime 2026/10/21 15:05
 */
class RpcResponseEncodingIntegrationTest {
    private final ApplicationTestControlPlane control = new ApplicationTestControlPlane();

    @AfterEach
    void closeControlPlane() {
        control.close();
    }

    @Test
    void oversizedSynchronousAndAsynchronousResultsMustNotLeakEncodingCapacity() throws Exception {
        CapturingTransport transport = new CapturingTransport();
        ResponseServiceImpl business = new ResponseServiceImpl();
        OtryxRpcServer server = OtryxRpcServer.builder()
                .securityProfile(RpcSecurityProfile.TRUSTED_DEVELOPMENT)
                .applicationMembership(control.membership())
                .transportServer(transport)
                .codecRegistry(RpcCodecRegistry.of(new SizedCodec()))
                .bindEndpoint(new RpcEndpoint("127.0.0.1", 19881))
                .responseEncodingLimits(128L, 64)
                .executionOptions(new RpcProviderExecutionOptions(false, 1, 8))
                .build()
                .registerService(ResponseService.class, business, "1.0.0", "default");
        try {
            server.start().toCompletableFuture().join();

            assertEquals(RpcStatus.OVERLOADED, call(transport, "large"));
            assertEquals(0L, server.responseEncodingBytes());

            CompletableFuture<byte[]> deferred = transport.handle(request("deferred"))
                    .toCompletableFuture();
            business.deferred.complete("oversized");
            assertEquals(RpcStatus.OVERLOADED,
                    RpcProtocolCodec.view(deferred.get(3, TimeUnit.SECONDS)).status());
            assertEquals(0L, server.responseEncodingBytes());

            assertEquals(RpcStatus.OK, call(transport, "small"));
            assertEquals(0L, server.responseEncodingBytes());
            assertTrue(server.responseEncodingHighWaterBytes() <= 128L);
        } finally {
            business.deferred.complete("stopped");
            server.close();
        }
    }

    @Test
    void cancelledResponseMustRetainBusinessAdmissionUntilOriginalStageActuallyCompletes() throws Exception {
        CapturingTransport transport = new CapturingTransport();
        ResponseServiceImpl business = new ResponseServiceImpl();
        AtomicInteger executing = new AtomicInteger();
        RpcObserver observer = new RpcObserver() {
            @Override
            public void onServerInflightChanged(int delta) {
                executing.addAndGet(delta);
            }
        };
        OtryxRpcServer server = OtryxRpcServer.builder()
                .securityProfile(RpcSecurityProfile.TRUSTED_DEVELOPMENT)
                .applicationMembership(control.membership())
                .transportServer(transport)
                .codecRegistry(RpcCodecRegistry.of(new SizedCodec()))
                .bindEndpoint(new RpcEndpoint("127.0.0.1", 19882))
                .maxConcurrent(1)
                .responseEncodingLimits(128L, 64)
                .executionOptions(new RpcProviderExecutionOptions(false, 1, 8))
                .observer(observer)
                .build()
                .registerService(ResponseService.class, business, "1.0.0", "default");
        try {
            server.start().toCompletableFuture().join();
            CompletableFuture<byte[]> pending = transport.handle(request("deferred")).toCompletableFuture();
            assertTrue(business.deferredStarted.await(3, TimeUnit.SECONDS));
            assertEquals(1, executing.get());
            assertTrue(pending.cancel(true));
            assertEquals(1, executing.get(),
                    "Cancelling the response must not release an unfinished business Stage");
            assertEquals(RpcStatus.OVERLOADED, call(transport, "small"));
            business.deferred.complete("oversized");
            await(() -> executing.get() == 0);
            assertEquals(0L, server.responseEncodingBytes());
            assertEquals(RpcStatus.OK, call(transport, "small"));
        } finally {
            business.deferred.complete("cancelled");
            server.close();
        }
    }

    private static void await(BooleanSupplier condition) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(2));
        }
        assertTrue(condition.getAsBoolean(), "Provider resource ownership did not settle");
    }

    private static RpcStatus call(CapturingTransport transport, String methodName) throws Exception {
        return RpcProtocolCodec.view(transport.handle(request(methodName))
                .toCompletableFuture().get(3, TimeUnit.SECONDS)).status();
    }

    private static byte[] request(String methodName) throws Exception {
        ServiceKey service = new ServiceKey(ResponseService.class.getName(), "1.0.0", "default");
        Method method = ResponseService.class.getMethod(methodName);
        return RpcProtocolCodec.encodeRequest((byte) 1,
                RpcIds.serviceId(service), RpcIds.methodId(method), 0L, 0L, Map.of(), new byte[0]);
    }

    public interface ResponseService {
        @OtryxRpcExecution(RpcExecutionMode.CPU)
        String large();

        @OtryxRpcExecution(RpcExecutionMode.CPU)
        CompletionStage<String> deferred();

        @OtryxRpcExecution(RpcExecutionMode.CPU)
        String small();
    }

    public static final class ResponseServiceImpl implements ResponseService {
        private final CompletableFuture<String> deferred = new CompletableFuture<>();
        private final CountDownLatch deferredStarted = new CountDownLatch(1);

        @Override
        public String large() {
            return "oversized";
        }

        @Override
        public CompletionStage<String> deferred() {
            deferredStarted.countDown();
            return deferred;
        }

        @Override
        public String small() {
            return "small";
        }
    }

    private static final class SizedCodec implements RpcCodec {
        @Override
        public byte code() {
            return 1;
        }

        @Override
        public byte[] encode(Object value) {
            return "oversized".equals(value) ? new byte[256] : new byte[8];
        }

        @Override
        public <T> T decode(byte[] data, Class<T> type) {
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

        CompletionStage<byte[]> handle(byte[] request) {
            return handler.handle(new RpcEndpoint("127.0.0.1", 20000), request);
        }

        @Override
        public void close() {
        }
    }
}
