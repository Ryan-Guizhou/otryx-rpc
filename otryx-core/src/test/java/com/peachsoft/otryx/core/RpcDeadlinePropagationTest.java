package com.peachsoft.otryx.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.peachsoft.otryx.api.OtryxRpcIdempotent;
import com.peachsoft.otryx.api.OtryxRpcTimeout;
import com.peachsoft.otryx.api.RpcCallContext;
import com.peachsoft.otryx.api.RpcCallOptions;
import com.peachsoft.otryx.api.RpcCallScope;
import com.peachsoft.otryx.api.RpcCompatibilityMetadata;
import com.peachsoft.otryx.api.RpcEndpoint;
import com.peachsoft.otryx.api.RpcMethodDescriptor;
import com.peachsoft.otryx.api.RpcStatus;
import com.peachsoft.otryx.api.RpcUnavailableException;
import com.peachsoft.otryx.api.ServiceInstance;
import com.peachsoft.otryx.api.ServiceKey;
import com.peachsoft.otryx.codec.RpcCodec;
import com.peachsoft.otryx.codec.RpcCodecRegistry;
import com.peachsoft.otryx.codec.RpcMethodCodec;
import com.peachsoft.otryx.protocol.RpcFrameView;
import com.peachsoft.otryx.protocol.RpcProtocolCodec;
import com.peachsoft.otryx.registry.RegistryListener;
import com.peachsoft.otryx.registry.RegistrySnapshot;
import com.peachsoft.otryx.registry.RegistrySubscription;
import com.peachsoft.otryx.registry.ServiceDiscovery;
import com.peachsoft.otryx.transport.RpcTransportClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/**
 * 验证单次/方法/Reference/Client 超时优先级及父级预算的真实 Wire 发送值。
 *
 * @Author OpenAI
 * @Version 1.0.0-SNAPSHOT
 * @CreateTime 2026/10/13 16:19
 */
class RpcDeadlinePropagationTest {

    private static final Duration CLIENT_DEFAULT = Duration.ofSeconds(3);
    private static final Duration REFERENCE_DEFAULT = Duration.ofMillis(1500);
    private static final long METHOD_TIMEOUT_MILLIS = 800L;
    private static final long SINGLE_CALL_TIMEOUT_MILLIS = 500L;
    private static final long PARENT_TIMEOUT_MILLIS = 250L;
    private static final long SAFETY_MARGIN_MILLIS = 15L;

    @Test
    void selectedTimeoutPriorityMustApplyToGeneratedOrProxyBinding() {
        AtomicLong sentTimeout = new AtomicLong();
        try (OtryxRpcClient client = client((endpoint, frame, timeout) -> {
            sentTimeout.set(RpcProtocolCodec.view(frame).timeoutBudgetMillis());
            return CompletableFuture.completedFuture(response(frame));
        })) {
            BudgetService service = client.refer(
                    BudgetService.class, "1.0.0", "demo", REFERENCE_DEFAULT);
            assertEquals("ok", service.timed("hello"));
            assertTrue(sentTimeout.get() > 0L
                    && sentTimeout.get() <= METHOD_TIMEOUT_MILLIS);

            assertEquals("ok", service.plain("hello"));
            assertTrue(sentTimeout.get() > METHOD_TIMEOUT_MILLIS
                    && sentTimeout.get() <= REFERENCE_DEFAULT.toMillis());

            try (RpcCallScope ignored = RpcCallContext.open(
                    new RpcCallOptions(
                            Duration.ofMillis(SINGLE_CALL_TIMEOUT_MILLIS),
                            Map.of("x-request", "U08")))) {
                assertEquals("ok", service.timed("hello"));
                assertTrue(sentTimeout.get() > 0L
                        && sentTimeout.get() <= SINGLE_CALL_TIMEOUT_MILLIS);
            }
        }
    }

    @Test
    void downstreamBudgetMustNotExceedRemainingParentBudget() throws Exception {
        AtomicLong sentTimeout = new AtomicLong();
        try (OtryxRpcClient client = client((endpoint, frame, timeout) -> {
            RpcFrameView view = RpcProtocolCodec.view(frame);
            sentTimeout.set(view.timeoutBudgetMillis());
            assertEquals("passed", view.metadataCopy().get("x-parent"));
            return CompletableFuture.completedFuture(response(frame));
        })) {
            BudgetService service = client.refer(
                    BudgetService.class, "1.0.0", "demo");
            try (RpcCallScope parent = RpcCallContext.open(
                    new RpcCallOptions(Duration.ofMillis(PARENT_TIMEOUT_MILLIS),
                            Map.of("x-parent", "passed")))) {
                Thread.sleep(50L);
                assertEquals("ok", service.timed("hello"));
                assertTrue(sentTimeout.get() > 0L);
                assertTrue(sentTimeout.get()
                        <= PARENT_TIMEOUT_MILLIS - SAFETY_MARGIN_MILLIS);
            }
        }
    }

    @Test
    void retryMustConsumeSameLogicalBudget() {
        AtomicInteger attempts = new AtomicInteger();
        AtomicLong firstAttemptBudget = new AtomicLong();
        AtomicLong secondAttemptBudget = new AtomicLong();
        try (OtryxRpcClient client = client((endpoint, frame, timeout) -> {
            long budget = RpcProtocolCodec.view(frame).timeoutBudgetMillis();
            if (attempts.incrementAndGet() == 1) {
                firstAttemptBudget.set(budget);
                try {
                    Thread.sleep(90L);
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    return CompletableFuture.failedFuture(error);
                }
                return CompletableFuture.failedFuture(
                        new RpcUnavailableException("transient"));
            }
            secondAttemptBudget.set(budget);
            return CompletableFuture.completedFuture(response(frame));
        })) {
            BudgetService service = client.refer(
                    BudgetService.class, "1.0.0", "demo");
            assertEquals("ok", service.timed("hello"));
            assertEquals(2, attempts.get());
            assertTrue(firstAttemptBudget.get() <= METHOD_TIMEOUT_MILLIS);
            assertTrue(secondAttemptBudget.get() < firstAttemptBudget.get() - 60L);
            assertTrue(secondAttemptBudget.get() > 0L);
        }
    }

    @Test
    void invalidMethodTimeoutMustFailAtReferBeforeNetwork() {
        try (OtryxRpcClient client = client((endpoint, frame, timeout) ->
                CompletableFuture.completedFuture(response(frame)))) {
            IllegalArgumentException error = assertThrows(
                    IllegalArgumentException.class,
                    () -> client.refer(BadBudgetService.class, "1.0.0", "demo"));
            assertTrue(error.getMessage().contains("Invalid RPC method timeout"));
        }
    }

    private static OtryxRpcClient client(RequestFunction behavior) {
        return OtryxRpcClient.builder()
                .serviceDiscovery(new Discovery())
                .transportClient(new Transport(behavior))
                .codecRegistry(RpcCodecRegistry.of(new StringCodec()))
                .timeout(CLIENT_DEFAULT)
                .resilienceOptions(new RpcClientResilienceOptions(
                        2, 0.0d, 1, 1,
                        Duration.ZERO, Duration.ZERO,
                        100, Duration.ofSeconds(1),
                        100, Duration.ofSeconds(1)))
                .build();
    }

    private static byte[] response(byte[] request) {
        RpcFrameView view = RpcProtocolCodec.view(request);
        return RpcProtocolCodec.encodeResponse(
                (byte) 1, RpcStatus.OK,
                view.requestId(), view.serviceId(), view.methodId(),
                "ok".getBytes(StandardCharsets.UTF_8));
    }

    public interface BudgetService {
        @OtryxRpcTimeout(millis = METHOD_TIMEOUT_MILLIS)
        @OtryxRpcIdempotent
        String timed(String value);

        String plain(String value);
    }

    public interface BadBudgetService {
        @OtryxRpcTimeout(millis = -1)
        String invalid(String value);
    }

    @FunctionalInterface
    private interface RequestFunction {
        CompletionStage<byte[]> invoke(
                RpcEndpoint endpoint, byte[] frame, Duration timeout);
    }

    private record Transport(RequestFunction behavior) implements RpcTransportClient {
        @Override
        public CompletionStage<byte[]> request(
                RpcEndpoint endpoint, byte[] frame, Duration timeout) {
            return behavior.invoke(endpoint, frame, timeout);
        }

        @Override
        public void close() {
        }
    }

    private static final class Discovery implements ServiceDiscovery {
        @Override
        public CompletionStage<RegistrySnapshot> lookup(ServiceKey key) {
            return CompletableFuture.completedFuture(snapshot(key));
        }

        @Override
        public RegistrySubscription subscribe(
                ServiceKey key, RegistryListener listener) {
            listener.onSnapshot(snapshot(key));
            return () -> { };
        }

        private static RegistrySnapshot snapshot(ServiceKey key) {
            Class<?> contract = key.interfaceName().equals(
                    BudgetService.class.getName())
                    ? BudgetService.class
                    : BadBudgetService.class;
            return new RegistrySnapshot(
                    List.of(new ServiceInstance(
                            "node1", key, new RpcEndpoint("127.0.0.1", 19090),
                            100, RpcCompatibilityMetadata.providerMetadata(
                                    key, contract))),
                    1L);
        }
    }

    private static final class StringCodec implements RpcCodec {
        @Override
        public byte code() {
            return 1;
        }

        @Override
        public byte[] encode(Object value) {
            return value == null ? new byte[0]
                    : value.toString().getBytes(StandardCharsets.UTF_8);
        }

        @Override
        public <T> T decode(byte[] payload, Class<T> type) {
            throw new UnsupportedOperationException();
        }

        @Override
        public RpcMethodCodec bind(RpcMethodDescriptor descriptor) {
            return new RpcMethodCodec() {
                @Override
                public byte codecId() {
                    return 1;
                }

                @Override
                public byte[] encodeArguments(Object[] args) {
                    return args == null || args.length == 0
                            ? new byte[0]
                            : args[0].toString().getBytes(StandardCharsets.UTF_8);
                }

                @Override
                public Object[] decodeArguments(byte[] bytes) {
                    return new Object[] {
                            new String(bytes, StandardCharsets.UTF_8)
                    };
                }

                @Override
                public byte[] encodeResult(Object value) {
                    return encode(value);
                }

                @Override
                public Object decodeResult(byte[] bytes) {
                    return new String(bytes, StandardCharsets.UTF_8);
                }
            };
        }
    }
}
