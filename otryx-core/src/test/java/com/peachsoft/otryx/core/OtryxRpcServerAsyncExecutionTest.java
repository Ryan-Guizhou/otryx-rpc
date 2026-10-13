package com.peachsoft.otryx.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.peachsoft.otryx.api.OtryxRpcExecution;
import com.peachsoft.otryx.api.RpcEndpoint;
import com.peachsoft.otryx.api.RpcExecutionMode;
import com.peachsoft.otryx.api.RpcIds;
import com.peachsoft.otryx.api.RpcStatus;
import com.peachsoft.otryx.api.ServiceInstance;
import com.peachsoft.otryx.api.ServiceKey;
import com.peachsoft.otryx.observability.RpcObserver;
import com.peachsoft.otryx.codec.RpcCodec;
import com.peachsoft.otryx.codec.RpcCodecRegistry;
import com.peachsoft.otryx.protocol.RpcProtocolCodec;
import com.peachsoft.otryx.registry.ServiceRegistrar;
import com.peachsoft.otryx.transport.RpcRequestHandler;
import com.peachsoft.otryx.transport.RpcTransportServer;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * 验证 Provider 异步执行、完成与资源释放语义。
 *
 * @Author Ryan
 * @Version 1.0.0
 * @CreateTime 2026/9/30 18:13
 */
public class OtryxRpcServerAsyncExecutionTest {

    @Test
    void providerDeadlineMustNotReleaseLeaseBeforeBusinessStops() throws Exception {
        CapturingTransportServer transport = new CapturingTransportServer();
        UncooperativeServiceImpl business = new UncooperativeServiceImpl();
        CountDownLatch leaseReleased = new CountDownLatch(1);
        RpcObserver observer = new RpcObserver() {
            @Override
            public void onServerInflightChanged(int delta) {
                if (delta < 0) {
                    leaseReleased.countDown();
                }
            }
        };
        OtryxRpcServer server = OtryxRpcServer.builder()
                .serviceRegistrar(new NoopRegistry())
                .transportServer(transport)
                .codecRegistry(RpcCodecRegistry.of(new NoopCodec()))
                .bindEndpoint(new RpcEndpoint("127.0.0.1", 19109))
                .maxConcurrent(1)
                .executionOptions(new RpcProviderExecutionOptions(false, 1, 1))
                .observer(observer)
                .build()
                .registerService(UncooperativeService.class, business, "1.0.0", "default");
        try {
            server.start().toCompletableFuture().join();
            byte[] request = requestWithBudget(
                    UncooperativeService.class, "stall", 500L, Map.of("tenant", "alpha"));
            CompletableFuture<byte[]> response = transport.handle(request).toCompletableFuture();
            assertTrue(business.entered.await(2, TimeUnit.SECONDS));
            assertEquals(RpcStatus.DEADLINE_EXCEEDED,
                    RpcProtocolCodec.view(response.get(3, TimeUnit.SECONDS)).status());
            assertEquals(1L, leaseReleased.getCount(),
                    "An expired response cannot release a running business lease");
            assertEquals(RpcStatus.OVERLOADED,
                    RpcProtocolCodec.view(transport.handle(
                            requestFor(UncooperativeService.class, "fast"))
                            .toCompletableFuture().get(2, TimeUnit.SECONDS)).status());
            business.release.countDown();
            assertTrue(leaseReleased.await(2, TimeUnit.SECONDS));
        } finally {
            business.release.countDown();
            server.close();
        }
    }

    @Test
    void providerMustRestoreInboundDeadlineAndRejectForgedPeerMetadata() throws Exception {
        CapturingTransportServer transport = new CapturingTransportServer();
        ContextServiceImpl service = new ContextServiceImpl();
        OtryxRpcServer server = OtryxRpcServer.builder()
                .serviceRegistrar(new NoopRegistry())
                .transportServer(transport)
                .codecRegistry(RpcCodecRegistry.of(new NoopCodec()))
                .bindEndpoint(new RpcEndpoint("127.0.0.1", 19110))
                .maxConcurrent(1)
                .build()
                .registerService(ContextService.class, service, "1.0.0", "default");
        try {
            server.start().toCompletableFuture().join();
            byte[] valid = requestWithBudget(
                    ContextService.class, "read", 2_000L, Map.of("tenant", "alpha"));
            assertEquals(RpcStatus.OK, RpcProtocolCodec.view(
                    transport.handle(valid).toCompletableFuture()
                            .get(2, TimeUnit.SECONDS)).status());
            assertEquals("alpha", service.context.get().metadata().get("tenant"));
            assertTrue(service.context.get().remainingNanos()
                    <= TimeUnit.SECONDS.toNanos(2L));
            byte[] forged = requestWithBudget(
                    ContextService.class, "read", 2_000L, Map.of("rpc-peer-id", "admin"));
            assertEquals(RpcStatus.BAD_REQUEST, RpcProtocolCodec.view(
                    transport.handle(forged).toCompletableFuture()
                            .get(2, TimeUnit.SECONDS)).status());
            assertEquals(1, service.invocations.get());
        } finally {
            server.close();
        }
    }

    private static byte[] requestWithBudget(
            Class<?> api, String methodName, long budgetMillis,
            Map<String, String> metadata) throws Exception {
        ServiceKey key = new ServiceKey(api.getName(), "1.0.0", "default");
        Method method = api.getMethod(methodName);
        return RpcProtocolCodec.encodeRequest(
                (byte) 1, RpcIds.serviceId(key), RpcIds.methodId(method),
                System.currentTimeMillis() - TimeUnit.DAYS.toMillis(1L), budgetMillis,
                metadata, new byte[0]);
    }

    public interface ContextService {
        @OtryxRpcExecution(RpcExecutionMode.CPU)
        String read();
    }

    public static final class ContextServiceImpl implements ContextService {
        private final AtomicReference<RpcCallContext> context = new AtomicReference<>();
        private final AtomicInteger invocations = new AtomicInteger();

        @Override
        public String read() {
            context.set(RpcCallContext.current());
            invocations.incrementAndGet();
            return "ok";
        }
    }

    @Test
    void failingProviderObserverMustNotLeakAdmissionOrMaskOverload()
            throws Exception {
        CapturingTransportServer transport = new CapturingTransportServer();
        AsyncServiceImpl service = new AsyncServiceImpl();
        AtomicInteger activeCalls = new AtomicInteger();
        AtomicLong activeFrameBytes = new AtomicLong();
        CountDownLatch completedMetrics = new CountDownLatch(2);
        CountDownLatch firstLeaseReleased = new CountDownLatch(1);
        RpcObserver brokenObserver = new RpcObserver() {
            @Override
            public void onServerInflightChanged(int delta) {
                activeCalls.addAndGet(delta);
                if (delta < 0) {
                    completedMetrics.countDown();
                    firstLeaseReleased.countDown();
                }
                throw new IllegalStateException("Metrics backend unavailable");
            }

            @Override
            public void onServerInflightBytesChanged(long delta) {
                activeFrameBytes.addAndGet(delta);
                throw new IllegalStateException("Byte metrics unavailable");
            }

            @Override
            public void onServerAdmissionRejected(
                    int serviceId,
                    int methodId,
                    String reason) {
                throw new IllegalStateException("Rejection metrics unavailable");
            }

            @Override
            public void onServerInvocationCompleted(
                    int serviceId,
                    int methodId,
                    RpcExecutionMode executionMode,
                    long durationNanos,
                    RpcStatus status,
                    Throwable error) {
                throw new IllegalStateException("Invocation metrics unavailable");
            }
        };

        OtryxRpcServer server = OtryxRpcServer.builder()
                .serviceRegistrar(new NoopRegistry())
                .transportServer(transport)
                .codecRegistry(RpcCodecRegistry.of(new NoopCodec()))
                .bindEndpoint(new RpcEndpoint("127.0.0.1", 19098))
                .maxConcurrent(1)
                .executionOptions(new RpcProviderExecutionOptions(
                        false, 1, 1))
                .observer(brokenObserver)
                .build()
                .registerService(
                        AsyncService.class,
                        service,
                        "1.0.0",
                        "default");
        try {
            server.start().toCompletableFuture().join();

            CompletableFuture<byte[]> outstanding =
                    transport.handle(request("slow")).toCompletableFuture();
            assertTrue(service.slowInvoked.await(2, TimeUnit.SECONDS));
            assertFalse(outstanding.isDone());

            byte[] overloaded = transport.handle(request("fast"))
                    .toCompletableFuture()
                    .get(3, TimeUnit.SECONDS);
            assertEquals(
                    RpcStatus.OVERLOADED,
                    RpcProtocolCodec.view(overloaded).status());

            service.slow.complete("slow");
            assertEquals(
                    RpcStatus.OK,
                    RpcProtocolCodec.view(
                            outstanding.get(3, TimeUnit.SECONDS)).status());
            assertTrue(firstLeaseReleased.await(2, TimeUnit.SECONDS));

            byte[] recovered = transport.handle(request("fast"))
                    .toCompletableFuture()
                    .get(3, TimeUnit.SECONDS);
            assertEquals(
                    RpcStatus.OK,
                    RpcProtocolCodec.view(recovered).status());
            assertTrue(completedMetrics.await(2, TimeUnit.SECONDS));
            assertEquals(0, activeCalls.get());
            assertEquals(0L, activeFrameBytes.get());
        } finally {
            server.close();
        }
    }

    @Test
    void asyncStageShouldNotBlockCpuWorker() throws Exception {
        CapturingTransportServer transport = new CapturingTransportServer();
        AsyncServiceImpl service = new AsyncServiceImpl();
        OtryxRpcServer server = createServer(
                transport,
                service,
                2,
                19093);

        try {
            server.start().toCompletableFuture().join();

            CompletionStage<byte[]> slowResponse =
                    transport.handle(request("slow"));
            assertFalse(slowResponse.toCompletableFuture().isDone());

            byte[] fastResponse = transport.handle(request("fast"))
                    .toCompletableFuture()
                    .get(1, TimeUnit.SECONDS);
            assertEquals(
                    RpcStatus.OK,
                    RpcProtocolCodec.view(fastResponse).status());

            service.slow.complete("slow");
            byte[] completedSlowResponse =
                    slowResponse.toCompletableFuture()
                            .get(1, TimeUnit.SECONDS);
            assertEquals(
                    RpcStatus.OK,
                    RpcProtocolCodec.view(completedSlowResponse).status());
        } finally {
            server.close();
        }
    }

    @Test
    void asyncCompletionShouldEncodeOnProviderExecutor() throws Exception {
        CapturingTransportServer transport = new CapturingTransportServer();
        AsyncServiceImpl service = new AsyncServiceImpl();
        TrackingCodec codec = new TrackingCodec();
        OtryxRpcServer server = createServer(
                transport,
                service,
                2,
                19095,
                codec);

        try {
            server.start().toCompletableFuture().join();

            CompletionStage<byte[]> response =
                    transport.handle(request("slow"));
            assertFalse(response.toCompletableFuture().isDone());
            assertTrue(
                    service.slowInvoked.await(
                            1,
                            TimeUnit.SECONDS));

            Thread completer = Thread.ofPlatform()
                    .name("foreign-async-completion")
                    .start(() -> service.slow.complete("slow"));
            completer.join();

            byte[] completed = response.toCompletableFuture()
                    .get(1, TimeUnit.SECONDS);
            assertEquals(
                    RpcStatus.OK,
                    RpcProtocolCodec.view(completed).status());
            assertTrue(
                    codec.encodeThreadName.get()
                            .startsWith("otryx-cpu-"));
        } finally {
            server.close();
        }
    }

    @Test
    void asyncCompletionShouldFailFastWhenCpuCompletionQueueIsFull()
            throws Exception {
        CapturingTransportServer transport = new CapturingTransportServer();
        AsyncServiceImpl service = new AsyncServiceImpl();
        CountDownLatch leasesReleased = new CountDownLatch(3);
        RpcObserver observer = new RpcObserver() {
            @Override
            public void onServerInflightChanged(int delta) {
                if (delta < 0) {
                    leasesReleased.countDown();
                }
            }
        };
        OtryxRpcServer server = createServer(
                transport,
                service,
                3,
                19096,
                new NoopCodec(),
                observer);

        try {
            server.start().toCompletableFuture().join();

            CompletionStage<byte[]> slowResponse =
                    transport.handle(request("slow"));
            assertFalse(slowResponse.toCompletableFuture().isDone());
            assertTrue(
                    service.slowInvoked.await(
                            1,
                            TimeUnit.SECONDS));

            CompletionStage<byte[]> blockingResponse =
                    transport.handle(request("blocking"));
            assertTrue(
                    service.blockingEntered.await(
                            1,
                            TimeUnit.SECONDS));

            CompletionStage<byte[]> queuedResponse =
                    transport.handle(request("queued"));
            assertFalse(queuedResponse.toCompletableFuture().isDone());

            service.slow.complete("slow");
            byte[] overloaded = slowResponse.toCompletableFuture()
                    .get(1, TimeUnit.SECONDS);
            assertEquals(
                    RpcStatus.OVERLOADED,
                    RpcProtocolCodec.view(overloaded).status());

            service.releaseBlocking.countDown();

            assertEquals(
                    RpcStatus.OK,
                    RpcProtocolCodec.view(
                            blockingResponse.toCompletableFuture()
                                    .get(1, TimeUnit.SECONDS))
                            .status());
            assertEquals(
                    RpcStatus.OK,
                    RpcProtocolCodec.view(
                            queuedResponse.toCompletableFuture()
                                    .get(1, TimeUnit.SECONDS))
                            .status());
            assertTrue(leasesReleased.await(2, TimeUnit.SECONDS));

            byte[] recovered = transport.handle(request("fast"))
                    .toCompletableFuture()
                    .get(1, TimeUnit.SECONDS);
            assertEquals(
                    RpcStatus.OK,
                    RpcProtocolCodec.view(recovered).status());
        } finally {
            service.releaseBlocking.countDown();
            server.close();
        }
    }

    @Test
    void errorFromUserMethodMustSettleResponseAndReleaseAdmission()
            throws Exception {
        CapturingTransportServer transport = new CapturingTransportServer();
        AsyncServiceImpl service = new AsyncServiceImpl();
        CountDownLatch leaseReleased = new CountDownLatch(1);
        RpcObserver observer = new RpcObserver() {
            @Override
            public void onServerInflightChanged(int delta) {
                if (delta < 0) {
                    leaseReleased.countDown();
                }
            }
        };
        OtryxRpcServer server = createServer(
                transport,
                service,
                1,
                19098,
                new NoopCodec(),
                observer);

        try {
            server.start().toCompletableFuture().join();

            byte[] failed = transport.handle(request("failing"))
                    .toCompletableFuture()
                    .get(3, TimeUnit.SECONDS);
            assertEquals(
                    RpcStatus.BUSINESS_ERROR,
                    RpcProtocolCodec.view(failed).status());
            assertTrue(leaseReleased.await(2, TimeUnit.SECONDS));

            byte[] recovered = transport.handle(request("fast"))
                    .toCompletableFuture()
                    .get(3, TimeUnit.SECONDS);
            assertEquals(
                    RpcStatus.OK,
                    RpcProtocolCodec.view(recovered).status());
        } finally {
            server.close();
        }
    }

    @Test
    void userExceptionMessageMustNotBeExposedInProviderWarningLog()
            throws Exception {
        CapturingTransportServer transport = new CapturingTransportServer();
        AsyncServiceImpl service = new AsyncServiceImpl();
        OtryxRpcServer server = createServer(
                transport,
                service,
                1,
                19101);
        Logger logger =
                (Logger) LoggerFactory.getLogger(OtryxRpcServer.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);

        try {
            server.start().toCompletableFuture().join();
            byte[] response = transport.handle(request("failing"))
                    .toCompletableFuture()
                    .get(3, TimeUnit.SECONDS);
            assertEquals(
                    RpcStatus.BUSINESS_ERROR,
                    RpcProtocolCodec.view(response).status());

            List<ILoggingEvent> warnings = appender.list.stream()
                    .filter(event -> event.getFormattedMessage()
                            .startsWith("RPC service invocation failed."))
                    .toList();
            assertEquals(1, warnings.size());
            ILoggingEvent warning = warnings.get(0);
            assertTrue(warning.getFormattedMessage()
                    .contains("errorType=java.lang.AssertionError"));
            assertFalse(warning.getFormattedMessage()
                    .contains("SENSITIVE_TEST_DATA_DO_NOT_LOG"));
            assertTrue(warning.getThrowableProxy() == null);
        } finally {
            logger.detachAppender(appender);
            appender.stop();
            server.close();
        }
    }

    @Test
    void exceptionalAsyncStageMustReleaseAdmissionAfterCompletion()
            throws Exception {
        CapturingTransportServer transport = new CapturingTransportServer();
        AsyncServiceImpl service = new AsyncServiceImpl();
        CountDownLatch leaseReleased = new CountDownLatch(1);
        RpcObserver observer = new RpcObserver() {
            @Override
            public void onServerInflightChanged(int delta) {
                if (delta < 0) {
                    leaseReleased.countDown();
                }
            }
        };
        OtryxRpcServer server = createServer(
                transport,
                service,
                1,
                19099,
                new NoopCodec(),
                observer);

        try {
            server.start().toCompletableFuture().join();
            CompletableFuture<byte[]> response =
                    transport.handle(request("slow")).toCompletableFuture();
            assertTrue(service.slowInvoked.await(3, TimeUnit.SECONDS));

            service.slow.completeExceptionally(
                    new AssertionError("Async callback failure"));
            assertEquals(
                    RpcStatus.BUSINESS_ERROR,
                    RpcProtocolCodec.view(
                            response.get(3, TimeUnit.SECONDS)).status());
            assertTrue(leaseReleased.await(2, TimeUnit.SECONDS));

            byte[] recovered = transport.handle(request("fast"))
                    .toCompletableFuture()
                    .get(3, TimeUnit.SECONDS);
            assertEquals(
                    RpcStatus.OK,
                    RpcProtocolCodec.view(recovered).status());
        } finally {
            server.close();
        }
    }

    @Test
    void asyncStageShouldRetainAdmissionUntilCompletion() throws Exception {
        CapturingTransportServer transport = new CapturingTransportServer();
        AsyncServiceImpl service = new AsyncServiceImpl();
        CountDownLatch leaseReleased = new CountDownLatch(1);
        RpcObserver observer = new RpcObserver() {
            @Override
            public void onServerInflightChanged(int delta) {
                if (delta < 0) {
                    leaseReleased.countDown();
                }
            }
        };
        OtryxRpcServer server = createServer(
                transport,
                service,
                1,
                19094,
                new NoopCodec(),
                observer);

        try {
            server.start().toCompletableFuture().join();

            CompletionStage<byte[]> slowResponse =
                    transport.handle(request("slow"));
            assertFalse(slowResponse.toCompletableFuture().isDone());

            byte[] overloaded = transport.handle(request("fast"))
                    .toCompletableFuture()
                    .get(1, TimeUnit.SECONDS);
            assertEquals(
                    RpcStatus.OVERLOADED,
                    RpcProtocolCodec.view(overloaded).status());

            service.slow.complete("slow");
            byte[] completedSlowResponse =
                    slowResponse.toCompletableFuture()
                            .get(1, TimeUnit.SECONDS);
            assertEquals(
                    RpcStatus.OK,
                    RpcProtocolCodec.view(completedSlowResponse).status());
            assertTrue(leaseReleased.await(2, TimeUnit.SECONDS));

            byte[] recovered = transport.handle(request("fast"))
                    .toCompletableFuture()
                    .get(1, TimeUnit.SECONDS);
            assertEquals(
                    RpcStatus.OK,
                    RpcProtocolCodec.view(recovered).status());
        } finally {
            server.close();
        }
    }

    @Test
    void busyServiceCannotBlockAnotherServiceAndCancellationReleasesAdmission()
            throws Exception {
        CapturingTransportServer transport = new CapturingTransportServer();
        AsyncServiceImpl busy = new AsyncServiceImpl();
        CountDownLatch leasesReleased = new CountDownLatch(2);
        RpcObserver observer = new RpcObserver() {
            @Override
            public void onServerInflightChanged(int delta) {
                if (delta < 0) {
                    leasesReleased.countDown();
                }
            }
        };
        OtryxRpcServer server = OtryxRpcServer.builder()
                .serviceRegistrar(new NoopRegistry())
                .transportServer(transport)
                .codecRegistry(RpcCodecRegistry.of(new NoopCodec()))
                .bindEndpoint(new RpcEndpoint("127.0.0.1", 19097))
                .maxConcurrent(2)
                .admissionOptions(new RpcProviderAdmissionOptions(
                        1024L, 0, 0, 0L, 0L))
                .executionOptions(new RpcProviderExecutionOptions(
                        false, 1, 4))
                .observer(observer)
                .build()
                .registerService(
                        AsyncService.class,
                        busy,
                        "1.0.0",
                        "default")
                .registerService(
                        AnotherService.class,
                        new AnotherServiceImpl(),
                        "1.0.0",
                        "default");
        try {
            server.start().toCompletableFuture().join();

            CompletableFuture<byte[]> outstanding =
                    transport.handle(request("slow")).toCompletableFuture();
            assertTrue(busy.slowInvoked.await(1, TimeUnit.SECONDS));
            assertFalse(outstanding.isDone());

            byte[] firstServiceOverload = transport.handle(request("fast"))
                    .toCompletableFuture()
                    .get(1, TimeUnit.SECONDS);
            assertEquals(
                    RpcStatus.OVERLOADED,
                    RpcProtocolCodec.view(firstServiceOverload).status());

            ServiceKey otherKey = new ServiceKey(
                    AnotherService.class.getName(), "1.0.0", "default");
            byte[] secondServiceRequest = RpcProtocolCodec.encodeRequest(
                    (byte) 1,
                    RpcIds.serviceId(otherKey),
                    RpcIds.methodId(AnotherService.class.getMethod("fast")),
                    0L,
                    0L,
                    Map.of(),
                    new byte[0]);
            byte[] otherResponse = transport.handle(secondServiceRequest)
                    .toCompletableFuture()
                    .get(1, TimeUnit.SECONDS);
            assertEquals(
                    RpcStatus.OK,
                    RpcProtocolCodec.view(otherResponse).status());

            assertTrue(outstanding.cancel(true));
            busy.slow.complete("cancelled");
            assertTrue(leasesReleased.await(2, TimeUnit.SECONDS));
            byte[] recovered = transport.handle(request("fast"))
                    .toCompletableFuture()
                    .get(1, TimeUnit.SECONDS);
            assertEquals(
                    RpcStatus.OK,
                    RpcProtocolCodec.view(recovered).status());
        } finally {
            busy.releaseBlocking.countDown();
            server.close();
        }
    }

    @Test
    void cancelledCpuRequestMustRetainAdmissionWhileBusinessStillRuns()
            throws Exception {
        CapturingTransportServer transport = new CapturingTransportServer();
        UncooperativeServiceImpl business = new UncooperativeServiceImpl();
        CountDownLatch leaseReleased = new CountDownLatch(1);
        RpcObserver observer = new RpcObserver() {
            @Override
            public void onServerInflightChanged(int delta) {
                if (delta < 0) {
                    leaseReleased.countDown();
                }
            }
        };
        OtryxRpcServer server = OtryxRpcServer.builder()
                .serviceRegistrar(new NoopRegistry())
                .transportServer(transport)
                .codecRegistry(RpcCodecRegistry.of(new NoopCodec()))
                .bindEndpoint(new RpcEndpoint("127.0.0.1", 19102))
                .maxConcurrent(1)
                .executionOptions(new RpcProviderExecutionOptions(false, 1, 2))
                .observer(observer)
                .build()
                .registerService(
                        UncooperativeService.class,
                        business,
                        "1.0.0",
                        "default");
        try {
            server.start().toCompletableFuture().join();
            CompletableFuture<byte[]> cancelled =
                    transport.handle(requestFor(
                            UncooperativeService.class, "stall"))
                            .toCompletableFuture();
            assertTrue(business.entered.await(2, TimeUnit.SECONDS));

            assertTrue(cancelled.cancel(true));
            byte[] overloaded = transport.handle(requestFor(
                            UncooperativeService.class, "fast"))
                    .toCompletableFuture()
                    .get(2, TimeUnit.SECONDS);
            assertEquals(RpcStatus.OVERLOADED,
                    RpcProtocolCodec.view(overloaded).status());
            assertEquals(1L, leaseReleased.getCount(),
                    "Cancellation must not release active business capacity");

            business.release.countDown();
            assertTrue(leaseReleased.await(2, TimeUnit.SECONDS));
            byte[] recovered = transport.handle(requestFor(
                            UncooperativeService.class, "fast"))
                    .toCompletableFuture()
                    .get(2, TimeUnit.SECONDS);
            assertEquals(RpcStatus.OK,
                    RpcProtocolCodec.view(recovered).status());
        } finally {
            business.release.countDown();
            server.close();
        }
    }

    @Test
    void shutdownMustReturnWithinBudgetForUninterruptibleWork()
            throws Exception {
        CapturingTransportServer transport = new CapturingTransportServer();
        UncooperativeServiceImpl business = new UncooperativeServiceImpl();
        CountDownLatch released = new CountDownLatch(1);
        RpcObserver observer = new RpcObserver() {
            @Override
            public void onServerInflightChanged(int delta) {
                if (delta < 0) {
                    released.countDown();
                }
            }
        };
        OtryxRpcServer server = OtryxRpcServer.builder()
                .serviceRegistrar(new NoopRegistry())
                .transportServer(transport)
                .codecRegistry(RpcCodecRegistry.of(new NoopCodec()))
                .bindEndpoint(new RpcEndpoint("127.0.0.1", 19107))
                .maxConcurrent(1)
                .executionOptions(new RpcProviderExecutionOptions(false, 1, 2))
                .observer(observer)
                .build()
                .registerService(
                        UncooperativeService.class,
                        business,
                        "1.0.0",
                        "default");
        try {
            server.start().toCompletableFuture().join();
            CompletableFuture<byte[]> response = transport.handle(
                            requestFor(UncooperativeService.class, "stall"))
                    .toCompletableFuture();
            assertTrue(business.entered.await(2, TimeUnit.SECONDS));
            long startedAtNanos = System.nanoTime();
            RpcShutdownReport report = server.shutdown(
                            new RpcShutdownOptions(Duration.ofMillis(120)))
                    .toCompletableFuture().get(2, TimeUnit.SECONDS);
            assertTrue(TimeUnit.NANOSECONDS.toMillis(
                    System.nanoTime() - startedAtNanos) < 1000L);
            assertTrue(report.remainingAdmissionLeases() >= 1,
                    "A still-running business method must retain its lease");
            assertFalse(report.executorsTerminated());
            assertTrue(response.isDone());
            assertEquals(1L, released.getCount(),
                    "Shutdown cannot release a lease just by cancelling a Future");

            business.release.countDown();
            assertTrue(released.await(2, TimeUnit.SECONDS));
        } finally {
            business.release.countDown();
            server.close();
        }
    }

    public interface UncooperativeService {
        @OtryxRpcExecution(RpcExecutionMode.CPU)
        String stall();

        @OtryxRpcExecution(RpcExecutionMode.CPU)
        String fast();
    }

    public static final class UncooperativeServiceImpl
            implements UncooperativeService {
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);

        @Override
        public String stall() {
            entered.countDown();
            boolean interrupted = false;
            for (;;) {
                try {
                    release.await();
                    break;
                } catch (InterruptedException ignored) {
                    // 模拟未遵循中断的业务，实现并发额度边界回归。
                    interrupted = true;
                }
            }
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
            return "finished";
        }

        @Override
        public String fast() {
            return "fast";
        }
    }

    public interface AnotherService {
        String fast();
    }

    public static final class AnotherServiceImpl implements AnotherService {
        @Override
        public String fast() {
            return "other";
        }
    }

    private static OtryxRpcServer createServer(
            CapturingTransportServer transport,
            AsyncServiceImpl service,
            int maxConcurrent,
            int port) {
        return createServer(
                transport,
                service,
                maxConcurrent,
                port,
                new NoopCodec());
    }

    private static OtryxRpcServer createServer(
            CapturingTransportServer transport,
            AsyncServiceImpl service,
            int maxConcurrent,
            int port,
            RpcCodec codec) {
        return createServer(
                transport, service, maxConcurrent, port, codec,
                RpcObserver.noop());
    }

    private static OtryxRpcServer createServer(
            CapturingTransportServer transport,
            AsyncServiceImpl service,
            int maxConcurrent,
            int port,
            RpcCodec codec,
            RpcObserver observer) {
        return OtryxRpcServer.builder()
                .serviceRegistrar(new NoopRegistry())
                .transportServer(transport)
                .codecRegistry(RpcCodecRegistry.of(codec))
                .bindEndpoint(new RpcEndpoint("127.0.0.1", port))
                .maxConcurrent(maxConcurrent)
                .executionOptions(new RpcProviderExecutionOptions(
                        false,
                        1,
                        1))
                .observer(observer)
                .build()
                .registerService(
                        AsyncService.class,
                        service,
                        "1.0.0",
                        "default");
    }

    private static byte[] request(String methodName) throws Exception {
        return requestFor(AsyncService.class, methodName);
    }

    private static byte[] requestFor(Class<?> api, String methodName)
            throws Exception {
        ServiceKey key =
                new ServiceKey(
                        api.getName(),
                        "1.0.0",
                        "default");
        Method method = api.getMethod(methodName);
        return RpcProtocolCodec.encodeRequest(
                (byte) 1,
                RpcIds.serviceId(key),
                RpcIds.methodId(method),
                0L,
                0L,
                Map.of(),
                new byte[0]);
    }

    public interface AsyncService {

        @OtryxRpcExecution(RpcExecutionMode.CPU)
        CompletionStage<String> slow();

        @OtryxRpcExecution(RpcExecutionMode.CPU)
        String fast();

        @OtryxRpcExecution(RpcExecutionMode.CPU)
        String blocking();

        @OtryxRpcExecution(RpcExecutionMode.CPU)
        String queued();

        @OtryxRpcExecution(RpcExecutionMode.CPU)
        String failing();
    }

    public static final class AsyncServiceImpl implements AsyncService {
        private final CompletableFuture<String> slow =
                new CompletableFuture<>();
        private final CountDownLatch slowInvoked =
                new CountDownLatch(1);
        private final CountDownLatch blockingEntered =
                new CountDownLatch(1);
        private final CountDownLatch releaseBlocking =
                new CountDownLatch(1);

        @Override
        public CompletionStage<String> slow() {
            slowInvoked.countDown();
            return slow;
        }

        @Override
        public String fast() {
            return "fast";
        }

        @Override
        public String blocking() {
            blockingEntered.countDown();
            try {
                if (!releaseBlocking.await(
                        2,
                        TimeUnit.SECONDS)) {
                    throw new IllegalStateException(
                            "Blocking test method timed out");
                }
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(error);
            }
            return "blocking";
        }

        @Override
        public String queued() {
            return "queued";
        }

        @Override
        public String failing() {
            throw new AssertionError("SENSITIVE_TEST_DATA_DO_NOT_LOG");
        }
    }

    private static final class TrackingCodec implements RpcCodec {
        private final AtomicReference<String> encodeThreadName =
                new AtomicReference<>();

        @Override
        public byte code() {
            return 1;
        }

        @Override
        public byte[] encode(Object value) {
            encodeThreadName.set(Thread.currentThread().getName());
            return new byte[0];
        }

        @Override
        public <T> T decode(byte[] bytes, Class<T> type) {
            if (type == Object[].class) {
                return type.cast(new Object[0]);
            }
            return null;
        }
    }

    private static final class NoopCodec implements RpcCodec {

        @Override
        public byte code() {
            return 1;
        }

        @Override
        public byte[] encode(Object value) {
            return new byte[0];
        }

        @Override
        public <T> T decode(byte[] bytes, Class<T> type) {
            if (type == Object[].class) {
                return type.cast(new Object[0]);
            }
            return null;
        }
    }

    private static final class NoopRegistry implements ServiceRegistrar {

        @Override
        public CompletionStage<Void> register(ServiceInstance instance) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletionStage<Void> unregister(ServiceInstance instance) {
            return CompletableFuture.completedFuture(null);
        }
    }

    private static final class CapturingTransportServer
            implements RpcTransportServer {
        private final AtomicReference<RpcRequestHandler> handler =
                new AtomicReference<>();

        @Override
        public CompletionStage<RpcEndpoint> start(
                RpcEndpoint bind,
                RpcRequestHandler requestHandler) {
            handler.set(requestHandler);
            return CompletableFuture.completedFuture(bind);
        }

        CompletionStage<byte[]> handle(byte[] request) {
            return handler.get().handle(
                    new RpcEndpoint("127.0.0.1", 20000),
                    request);
        }

        @Override
        public void close() {
        }
    }
}
