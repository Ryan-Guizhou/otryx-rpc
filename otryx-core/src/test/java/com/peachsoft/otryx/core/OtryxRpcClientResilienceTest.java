package com.peachsoft.otryx.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertSame;

import com.peachsoft.otryx.api.OtryxRpcIdempotent;
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
import com.peachsoft.otryx.observability.RpcObserver;
import com.peachsoft.otryx.observability.RpcMetadataPropagator;
import com.peachsoft.otryx.api.RpcTimeoutException;
import com.peachsoft.otryx.observability.RpcRetryExhaustionReason;
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
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * 验证 Consumer 的重试、过载处理与容错策略边界。
 *
 * @Author Ryan
 * @Version 1.0.0
 * @CreateTime 2026/9/24 11:44
 */
class OtryxRpcClientResilienceTest {

    @Test
    void downstreamBudgetMustNotExceedRemainingParentBudget() throws Exception {
        AtomicReference<Long> wireBudget = new AtomicReference<>();
        var method = RetryService.class.getMethod("find", String.class);
        try (OtryxRpcClient client = OtryxRpcClient.builder()
                .serviceDiscovery(new StaticDiscovery())
                .transportClient(new TestTransport((endpoint, frame, duration) -> {
                    wireBudget.set(RpcProtocolCodec.view(frame).timeoutBudgetMillis());
                    return CompletableFuture.completedFuture(successResponse(frame, "ok"));
                }))
                .codecRegistry(RpcCodecRegistry.of(new StringCodec()))
                .timeout(Duration.ofSeconds(3))
                .methodTimeouts(Map.of(method.toGenericString(), Duration.ofMillis(1_500)))
                .build()) {
            RetryService service = client.refer(RetryService.class, "1.0.0", "test");
            assertEquals("ok", service.find("method"));
            assertTrue(wireBudget.get() > 0L && wireBudget.get() <= 1_500L);
            try (RpcCallScope ignored = RpcCallContext.open(
                    new RpcCallOptions(Duration.ofMillis(700), Map.of("tenant", "a")))) {
                assertEquals("ok", service.find("override"));
                assertTrue(wireBudget.get() > 0L && wireBudget.get() <= 700L,
                        "A call override must never enlarge its parent deadline");
            }
        }
    }

    @Test
    void retryMustConsumeSameLogicalBudgetAndRestoreCapturedContext() {
        AtomicInteger attempts = new AtomicInteger();
        AtomicReference<Long> firstBudget = new AtomicReference<>();
        AtomicReference<Long> secondBudget = new AtomicReference<>();
        AtomicInteger propagated = new AtomicInteger();
        try (OtryxRpcClient client = client((endpoint, frame, timeout) -> {
            int attempt = attempts.incrementAndGet();
            long budget = RpcProtocolCodec.view(frame).timeoutBudgetMillis();
            if (RpcCallContext.current() != null) {
                propagated.incrementAndGet();
            }
            if (attempt == 1) {
                firstBudget.set(budget);
                try {
                    Thread.sleep(50L);
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    return CompletableFuture.failedFuture(error);
                }
                return CompletableFuture.failedFuture(new RpcUnavailableException("retry"));
            }
            secondBudget.set(budget);
            return CompletableFuture.completedFuture(successResponse(frame, "ok"));
        })) {
            RetryService service = client.refer(RetryService.class, "1.0.0", "test");
            try (RpcCallScope ignored = RpcCallContext.open(
                    new RpcCallOptions(Duration.ofMillis(800), Map.of("tenant", "alpha")))) {
                assertEquals("ok", service.find("retry"));
            }
            assertEquals(2, attempts.get());
            assertEquals(2, propagated.get());
            assertTrue(secondBudget.get() < firstBudget.get(),
                    "A retry must use the original deadline rather than refreshing it");
        }
    }

    @Test
    void shouldRetryOnlyIdempotentMethodWithinBudget() {
        AtomicInteger attempts = new AtomicInteger();
        try (OtryxRpcClient client = client((endpoint, frame, timeout) -> {
            if (attempts.incrementAndGet() == 1) {
                return CompletableFuture.failedFuture(
                        new RpcUnavailableException("temporary"));
            }
            return CompletableFuture.completedFuture(
                    successResponse(frame, "ok"));
        })) {
            RetryService service = client.refer(
                    RetryService.class,
                    "1.0.0",
                    "test");

            assertEquals("ok", service.find("42"));
            assertEquals(2, attempts.get());
        }
    }

    @Test
    void shouldObserveRetryExhaustionAtMaxAttempts() {
        AtomicInteger attempts =
                new AtomicInteger();
        AtomicReference<RpcRetryExhaustionReason> reason =
                new AtomicReference<>();
        RpcObserver observer = new RpcObserver() {
            @Override
            public void onClientRetryExhausted(
                    ServiceKey serviceKey,
                    int methodId,
                    RpcRetryExhaustionReason exhaustionReason,
                    Throwable cause) {
                reason.set(exhaustionReason);
            }
        };

        try (OtryxRpcClient client = client(
                (endpoint, frame, timeout) -> {
                    attempts.incrementAndGet();
                    return CompletableFuture.failedFuture(
                            new RpcUnavailableException(
                                    "temporary"));
                },
                observer)) {
            RetryService service = client.refer(
                    RetryService.class,
                    "1.0.0",
                    "test");

            CompletionException error = assertThrows(
                    CompletionException.class,
                    () -> service.find("42"));

            assertInstanceOf(
                    RpcUnavailableException.class,
                    error.getCause());
            assertEquals(
                    2,
                    attempts.get());
            assertEquals(
                    RpcRetryExhaustionReason.MAX_ATTEMPTS,
                    reason.get());
        }
    }

    @Test
    void shouldNotRetryMethodWithoutIdempotentContract() {
        AtomicInteger attempts = new AtomicInteger();
        try (OtryxRpcClient client = client((endpoint, frame, timeout) -> {
            attempts.incrementAndGet();
            return CompletableFuture.failedFuture(
                    new RpcUnavailableException("temporary"));
        })) {
            RetryService service = client.refer(
                    RetryService.class,
                    "1.0.0",
                    "test");

            CompletionException error = assertThrows(
                    CompletionException.class,
                    () -> service.create("42"));
            assertInstanceOf(
                    RpcUnavailableException.class,
                    error.getCause());
            assertEquals(1, attempts.get());
        }
    }

    @Test
    void singleFailingObserverMustNotBreakRpcSuccessOrInflightBalance()
            throws Exception {
        AtomicInteger attemptCount = new AtomicInteger();
        AtomicInteger inflightBalance = new AtomicInteger();
        CountDownLatch completedCalls = new CountDownLatch(2);
        RpcObserver failingObserver = new RpcObserver() {
            @Override
            public void onClientInflightChanged(int delta) {
                inflightBalance.addAndGet(delta);
                if (delta < 0) {
                    completedCalls.countDown();
                }
                throw new IllegalStateException("Metrics backend unavailable");
            }

            @Override
            public void onClientAttemptCompleted(
                    ServiceKey serviceKey,
                    int methodId,
                    RpcEndpoint endpoint,
                    int attempt,
                    long durationNanos,
                    RpcStatus status,
                    Throwable error) {
                throw new IllegalStateException("Attempt metrics failed");
            }

            @Override
            public void onClientCallCompleted(
                    ServiceKey serviceKey,
                    int methodId,
                    long durationNanos,
                    RpcStatus status,
                    Throwable error) {
                throw new IllegalStateException("Completion metrics failed");
            }
        };

        try (OtryxRpcClient client = client(
                (endpoint, frame, timeout) -> {
                    attemptCount.incrementAndGet();
                    return CompletableFuture.completedFuture(
                            successResponse(frame, "ok"));
                },
                failingObserver)) {
            RetryService service = client.refer(
                    RetryService.class, "1.0.0", "test");
            assertEquals("ok", service.find("one"));
            assertEquals("ok", service.create("two"));
            assertEquals(2, attemptCount.get());
            assertTrue(completedCalls.await(2, TimeUnit.SECONDS));
            assertEquals(0, inflightBalance.get());
        }
    }

    @Test
    void failingObserverMustNotSuppressRetryOrOriginalFailure() {
        AtomicInteger attempts = new AtomicInteger();
        RpcObserver failingObserver = new RpcObserver() {
            @Override
            public void onClientRetryScheduled(
                    ServiceKey serviceKey,
                    int methodId,
                    int nextAttempt,
                    long delayMillis,
                    Throwable cause) {
                throw new IllegalStateException("Retry metrics failed");
            }

            @Override
            public void onClientRetryExhausted(
                    ServiceKey serviceKey,
                    int methodId,
                    RpcRetryExhaustionReason reason,
                    Throwable cause) {
                throw new IllegalStateException("Exhaustion metrics failed");
            }
        };

        try (OtryxRpcClient client = client(
                (endpoint, frame, timeout) -> {
                    attempts.incrementAndGet();
                    return CompletableFuture.failedFuture(
                            new RpcUnavailableException("Endpoint unavailable"));
                },
                failingObserver)) {
            RetryService service = client.refer(
                    RetryService.class, "1.0.0", "test");
            CompletionException failure = assertThrows(
                    CompletionException.class,
                    () -> service.find("retry"));
            assertInstanceOf(
                    RpcUnavailableException.class,
                    failure.getCause());
            assertEquals(2, attempts.get());
        }
    }

    @Test
    void responseDecoderRunsOutsideTransportCompletionThread() throws Exception {
        CompletableFuture<byte[]> transportResponse = new CompletableFuture<>();
        AtomicReference<byte[]> capturedRequest = new AtomicReference<>();
        CountDownLatch requestSent = new CountDownLatch(1);
        CountDownLatch decodeEntered = new CountDownLatch(1);
        CountDownLatch releaseDecode = new CountDownLatch(1);
        AtomicReference<String> decodeThread = new AtomicReference<>();

        RpcCodec codec = new StringCodec(() -> {
            decodeThread.set(Thread.currentThread().getName());
            decodeEntered.countDown();
            try {
                if (!releaseDecode.await(3, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Decoder timed out");
                }
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(error);
            }
        });

        try (OtryxRpcClient client = OtryxRpcClient.builder()
                .serviceDiscovery(new StaticDiscovery())
                .transportClient(new TestTransport((endpoint, frame, timeout) -> {
                    capturedRequest.set(frame);
                    requestSent.countDown();
                    return transportResponse;
                }))
                .codecRegistry(RpcCodecRegistry.of(codec))
                .timeout(Duration.ofSeconds(3))
                .build()) {
            RetryService service = client.refer(RetryService.class, "1.0.0", "test");
            CompletableFuture<String> call =
                    CompletableFuture.supplyAsync(() -> service.find("42"));
            assertTrue(requestSent.await(2, TimeUnit.SECONDS));

            Thread eventLoop = Thread.ofPlatform()
                    .name("simulated-transport-eventloop")
                    .start(() -> transportResponse.complete(
                            successResponse(capturedRequest.get(), "ok")));
            try {
                assertTrue(decodeEntered.await(2, TimeUnit.SECONDS));
                eventLoop.join(250);
                assertFalse(eventLoop.isAlive(),
                        "Transport completion must not block on decode");
                assertTrue(decodeThread.get()
                        .startsWith("otryx-client-completion-"));
            } finally {
                releaseDecode.countDown();
            }
            assertEquals("ok", call.get(2, TimeUnit.SECONDS));
        }
    }

    @Test
    void shouldRejectNewCallWhenCompletionCapacityIsExhausted()
            throws Exception {
        java.util.concurrent.CopyOnWriteArrayList<byte[]> frames =
                new java.util.concurrent.CopyOnWriteArrayList<>();
        java.util.concurrent.CopyOnWriteArrayList<CompletableFuture<byte[]>> replies =
                new java.util.concurrent.CopyOnWriteArrayList<>();
        CountDownLatch admitted = new CountDownLatch(2);
        AtomicInteger encodedCalls = new AtomicInteger();
        try (OtryxRpcClient client = OtryxRpcClient.builder()
                .serviceDiscovery(new StaticDiscovery())
                .transportClient(new TestTransport(
                        (endpoint, frame, timeout) -> {
                            frames.add(frame);
                            CompletableFuture<byte[]> response =
                                    new CompletableFuture<>();
                            replies.add(response);
                            admitted.countDown();
                            return response;
                        }))
                .codecRegistry(RpcCodecRegistry.of(
                        new StringCodec(() -> { }, encodedCalls::incrementAndGet)))
                .timeout(Duration.ofSeconds(5))
                .responseCompletionThreads(1)
                .responseCompletionQueueCapacity(1)
                .build()) {
            RetryService service = client.refer(
                    RetryService.class, "1.0.0", "test");
            CompletableFuture<String> first =
                    CompletableFuture.supplyAsync(() -> service.find("one"));
            CompletableFuture<String> second =
                    CompletableFuture.supplyAsync(() -> service.find("two"));
            assertTrue(admitted.await(2, TimeUnit.SECONDS));

            CompletionException rejected = assertThrows(
                    CompletionException.class,
                    () -> service.find("three"));
            assertInstanceOf(
                    com.peachsoft.otryx.api.RpcOverloadedException.class,
                    rejected.getCause());
            assertEquals(2, frames.size());
            assertEquals(2, encodedCalls.get(),
                    "Rejected calls must not serialize arguments");

            for (int i = 0; i < replies.size(); i++) {
                replies.get(i).complete(
                        successResponse(frames.get(i), "ok"));
            }
            assertEquals("ok", first.get(2, TimeUnit.SECONDS));
            assertEquals("ok", second.get(2, TimeUnit.SECONDS));
        }
    }

    @Test
    void argumentSerializationFailureMustReturnReservedCapacity() {
        AtomicInteger encodes = new AtomicInteger();
        RpcCodec codec = new StringCodec(
                () -> { },
                () -> {
                    encodes.incrementAndGet();
                    throw new IllegalStateException("Argument encoder failed");
                });
        try (OtryxRpcClient client = OtryxRpcClient.builder()
                .serviceDiscovery(new StaticDiscovery())
                .transportClient(new TestTransport((endpoint, frame, timeout) ->
                        CompletableFuture.completedFuture(
                                successResponse(frame, "ok"))))
                .codecRegistry(RpcCodecRegistry.of(codec))
                .responseCompletionThreads(1)
                .responseCompletionQueueCapacity(1)
                .build()) {
            RetryService service =
                    client.refer(RetryService.class, "1.0.0", "test");

            for (int attempt = 0; attempt < 4; attempt++) {
                assertThrows(IllegalStateException.class,
                        () -> service.find("fail"));
            }
            assertEquals(4, encodes.get(),
                    "Every attempt must re-enter the encoder");
        }
    }

    @Test
    void failedMetadataExtensionShouldReleaseConsumerCapacity() {
        AtomicInteger metadataCalls = new AtomicInteger();
        RpcMetadataPropagator broken = new RpcMetadataPropagator() {
            @Override
            public void inject(Map<String, String> metadata) {
                metadataCalls.incrementAndGet();
                throw new IllegalStateException("broken custom metadata");
            }
        };
        AtomicInteger sent = new AtomicInteger();
        try (OtryxRpcClient client = OtryxRpcClient.builder()
                .serviceDiscovery(new StaticDiscovery())
                .transportClient(new TestTransport((endpoint, frame, timeout) -> {
                    sent.incrementAndGet();
                    return CompletableFuture.completedFuture(successResponse(frame, "ok"));
                }))
                .codecRegistry(RpcCodecRegistry.of(new StringCodec()))
                .metadataPropagator(broken)
                .responseCompletionThreads(1)
                .responseCompletionQueueCapacity(1)
                .build()) {
            RetryService service = client.refer(RetryService.class, "1.0.0", "test");
            for (int attempt = 0; attempt < 4; attempt++) {
                CompletionException failure = assertThrows(
                        CompletionException.class, () -> service.find("test"));
                assertInstanceOf(IllegalStateException.class, failure.getCause());
            }
            assertEquals(4, metadataCalls.get());
            assertEquals(0, sent.get());
        }
    }

    @Test
    void hangingTransportShouldFinishAtLogicalDeadline() throws Exception {
        CompletableFuture<byte[]> neverFinishes = new CompletableFuture<>();
        try (OtryxRpcClient client = OtryxRpcClient.builder()
                .serviceDiscovery(new StaticDiscovery())
                .transportClient(new TestTransport(
                        (endpoint, frame, timeout) -> neverFinishes))
                .codecRegistry(RpcCodecRegistry.of(new StringCodec()))
                .timeout(Duration.ofMillis(80))
                .build()) {
            RetryService service = client.refer(RetryService.class, "1.0.0", "test");
            CompletionException failure = assertThrows(CompletionException.class,
                    () -> service.find("test"));
            assertInstanceOf(RpcTimeoutException.class, failure.getCause());
            assertTrue(neverFinishes.isCancelled(), "Deadline must cancel the pending transport");
        }
    }

    @Test
    void staleDirectoryMustRejectDispatchAndAllowExplicitRecovery() {
        MutableDiscovery discovery = new MutableDiscovery();
        AtomicInteger requests = new AtomicInteger();
        try (OtryxRpcClient client = OtryxRpcClient.builder()
                .serviceDiscovery(discovery)
                .transportClient(new TestTransport((endpoint, frame, timeout) -> {
                    requests.incrementAndGet();
                    return CompletableFuture.completedFuture(successResponse(frame, "ok"));
                }))
                .codecRegistry(RpcCodecRegistry.of(new StringCodec()))
                .directoryFreshnessOptions(
                        new RpcDirectoryFreshnessOptions(Duration.ofNanos(1L)))
                .build()) {
            RetryService service = client.refer(RetryService.class, "1.0.0", "test");
            assertEquals("ok", service.find("initial"));
            discovery.fail();
            CompletionException failure = assertThrows(CompletionException.class,
                    () -> service.find("stale"));
            assertInstanceOf(RpcUnavailableException.class, failure.getCause());
            assertTrue(failure.getCause().getMessage().contains("freshness budget exceeded"));
            assertEquals(1, requests.get(), "Expired LKG must not dispatch");
            discovery.healthy();
            assertEquals("ok", service.find("recovered"));
            assertEquals(2, requests.get());
        }
    }

    @Test
    void retiredEndpointsShouldNotAccumulateConsumerStatistics() {
        MutableDiscovery discovery = new MutableDiscovery();
        try (OtryxRpcClient client = OtryxRpcClient.builder()
                .serviceDiscovery(discovery)
                .transportClient(new TestTransport((endpoint, frame, timeout) ->
                        CompletableFuture.completedFuture(
                                successResponse(frame, "ok"))))
                .codecRegistry(RpcCodecRegistry.of(new StringCodec()))
                .build()) {
            RetryService service = client.refer(
                    RetryService.class, "1.0.0", "test");

            assertEquals("ok", service.find("before"));
            assertEquals(1, client.trackedEndpointCount());

            discovery.publishEmpty();
            client.evictUnusedEndpointStats();

            assertEquals(0, client.trackedEndpointCount());
        }
    }

    @Test
    void closingClientMustSettlePendingCallsAndCancelTransport()
            throws Exception {
        CountDownLatch dispatched = new CountDownLatch(1);
        CompletableFuture<byte[]> pendingTransport = new CompletableFuture<>();
        OtryxRpcClient client = client((endpoint, frame, timeout) -> {
            dispatched.countDown();
            return pendingTransport;
        });
        AtomicReference<Throwable> failure = new AtomicReference<>();
        try {
            RetryService service =
                    client.refer(RetryService.class, "1.0.0", "test");
            Thread invocation = Thread.ofVirtual().start(() -> {
                try {
                    service.find("pending");
                } catch (Throwable error) {
                    failure.set(error);
                }
            });
            assertTrue(dispatched.await(2, TimeUnit.SECONDS));

            client.close();
            client.close();
            invocation.join(2_000L);
            assertFalse(invocation.isAlive(), "Client close must settle pending calls");
            assertTrue(pendingTransport.isCancelled(),
                    "Client close must propagate cancellation to the transport");
            Throwable error = failure.get();
            assertNotNull(error);
            if (error instanceof CompletionException wrapped) {
                error = wrapped.getCause();
            }
            assertInstanceOf(RpcUnavailableException.class, error);
        } finally {
            client.close();
        }
    }

    @Test
    void closedClientMustRejectNewReferencesAndCalls() {
        AtomicInteger requests = new AtomicInteger();
        OtryxRpcClient client = client((endpoint, frame, timeout) -> {
            requests.incrementAndGet();
            return CompletableFuture.completedFuture(
                    successResponse(frame, "ok"));
        });
        RetryService service =
                client.refer(RetryService.class, "1.0.0", "test");
        client.close();
        client.close();

        assertThrows(RpcUnavailableException.class,
                () -> client.refer(RetryService.class, "1.0.0", "test"));
        CompletionException rejected = assertThrows(
                CompletionException.class, () -> service.find("after-close"));
        assertInstanceOf(RpcUnavailableException.class, rejected.getCause());
        assertEquals(0, requests.get(),
                "Closed client must not send new network requests");
    }

    @Test
    void clientShutdownMustReturnBudgetReportForUncooperativeTransport()
            throws Exception {
        UncooperativeCloseTransport transport =
                new UncooperativeCloseTransport();
        OtryxRpcClient client = OtryxRpcClient.builder()
                .serviceDiscovery(new StaticDiscovery())
                .transportClient(transport)
                .codecRegistry(RpcCodecRegistry.of(new StringCodec()))
                .build();
        try {
            RetryService service = client.refer(
                    RetryService.class, "1.0.0", "test");
            assertEquals("ok", service.find("initial"));

            CompletionStage<RpcClientShutdownReport> first =
                    client.shutdown(
                            new RpcShutdownOptions(Duration.ofMillis(120)));
            assertTrue(transport.closeEntered.await(2, TimeUnit.SECONDS));
            CompletionStage<RpcClientShutdownReport> duplicate =
                    client.shutdown(
                            new RpcShutdownOptions(Duration.ofSeconds(1)));
            assertSame(first, duplicate);

            RpcClientShutdownReport report = first.toCompletableFuture()
                    .get(2, TimeUnit.SECONDS);
            assertTrue(report.deadlineExceeded());
            assertFalse(report.transportClosed());
            assertTrue(report.executorsTerminated());
            assertEquals(0, report.remainingLogicalCalls());
            assertThrows(RpcUnavailableException.class,
                    () -> client.refer(
                            RetryService.class, "1.0.0", "test"));
        } finally {
            transport.releaseClose.countDown();
            client.close();
        }
    }

    private static OtryxRpcClient client(
            RequestFunction request) {
        return client(
                request,
                RpcObserver.noop());
    }

    private static OtryxRpcClient client(
            RequestFunction request,
            RpcObserver observer) {
        RpcClientResilienceOptions resilience =
                new RpcClientResilienceOptions(
                        2,
                        0.0d,
                        1,
                        1,
                        Duration.ZERO,
                        Duration.ZERO,
                        100,
                        Duration.ofSeconds(1),
                        100,
                        Duration.ofSeconds(1));
        return OtryxRpcClient.builder()
                .serviceDiscovery(new StaticDiscovery())
                .transportClient(new TestTransport(request))
                .codecRegistry(RpcCodecRegistry.of(new StringCodec()))
                .timeout(Duration.ofSeconds(1))
                .resilienceOptions(resilience)
                .observer(observer)
                .build();
    }

    private static byte[] successResponse(
            byte[] requestBytes,
            String value) {
        RpcFrameView request = RpcProtocolCodec.view(requestBytes);
        return RpcProtocolCodec.encodeResponse(
                (byte) 1,
                RpcStatus.OK,
                request.requestId(),
                request.serviceId(),
                request.methodId(),
                value.getBytes(StandardCharsets.UTF_8));
    }

    interface RetryService {

        @OtryxRpcIdempotent
        String find(String value);

        String create(String value);
    }

    @FunctionalInterface
    private interface RequestFunction {

        CompletionStage<byte[]> request(
                RpcEndpoint endpoint,
                byte[] frame,
                Duration timeout);
    }

    private static final class TestTransport
            implements RpcTransportClient {

        private final RequestFunction request;

        private TestTransport(RequestFunction request) {
            this.request = request;
        }

        @Override
        public CompletionStage<byte[]> request(
                RpcEndpoint endpoint,
                byte[] frame,
                Duration timeout) {
            return request.request(endpoint, frame, timeout);
        }

        @Override
        public void close() {
        }
    }

    private static final class UncooperativeCloseTransport
            implements RpcTransportClient {
        private final CountDownLatch closeEntered = new CountDownLatch(1);
        private final CountDownLatch releaseClose = new CountDownLatch(1);

        @Override
        public CompletionStage<byte[]> request(
                RpcEndpoint endpoint,
                byte[] frame,
                Duration timeout) {
            return CompletableFuture.completedFuture(
                    successResponse(frame, "ok"));
        }

        @Override
        public void close() {
            closeEntered.countDown();
            for (;;) {
                try {
                    releaseClose.await();
                    return;
                } catch (InterruptedException ignored) {
                    // 模拟不配合中断的外部 Transport SPI。
                }
            }
        }
    }

    private static final class MutableDiscovery implements ServiceDiscovery {
        private final AtomicReference<RegistryListener> listener =
                new AtomicReference<>();

        @Override
        public CompletionStage<RegistrySnapshot> lookup(ServiceKey key) {
            return CompletableFuture.completedFuture(
                    StaticDiscovery.snapshot(key));
        }

        @Override
        public RegistrySubscription subscribe(
                ServiceKey key,
                RegistryListener updateListener) {
            listener.set(updateListener);
            updateListener.onSnapshot(StaticDiscovery.snapshot(key));
            return () -> listener.compareAndSet(updateListener, null);
        }

        private void fail() {
            RegistryListener observer = listener.get();
            if (observer != null) {
                observer.onSynchronizationFailed(
                        new RpcUnavailableException("Registry disconnected"));
            }
        }

        private void healthy() {
            RegistryListener observer = listener.get();
            if (observer != null) {
                observer.onSynchronizationSucceeded();
            }
        }

        private void publishEmpty() {
            RegistryListener updateListener = listener.get();
            if (updateListener == null) {
                throw new IllegalStateException(
                        "Registry listener has not been subscribed");
            }
            updateListener.onSnapshot(new RegistrySnapshot(List.of(), 2L));
        }
    }

    private static final class StaticDiscovery
            implements ServiceDiscovery {

        @Override
        public CompletionStage<RegistrySnapshot> lookup(ServiceKey key) {
            return CompletableFuture.completedFuture(snapshot(key));
        }

        @Override
        public RegistrySubscription subscribe(
                ServiceKey key,
                RegistryListener listener) {
            listener.onSnapshot(snapshot(key));
            return () -> { };
        }

        private static RegistrySnapshot snapshot(ServiceKey key) {
            return new RegistrySnapshot(
                    List.of(new ServiceInstance(
                            "node-1",
                            key,
                            new RpcEndpoint("127.0.0.1", 19090),
                            100,
                            RpcCompatibilityMetadata.providerMetadata(
                                    key, RetryService.class))),
                    1L);
        }
    }

    private static final class StringCodec
            implements RpcCodec {

        private final Runnable onDecode;
        private final Runnable onEncode;

        private StringCodec() {
            this(() -> { }, () -> { });
        }

        private StringCodec(Runnable onDecode) {
            this(onDecode, () -> { });
        }

        private StringCodec(Runnable onDecode, Runnable onEncode) {
            this.onDecode = onDecode;
            this.onEncode = onEncode;
        }

        @Override
        public byte code() {
            return 1;
        }

        @Override
        public byte[] encode(Object value) {
            return value == null
                    ? new byte[0]
                    : value.toString().getBytes(StandardCharsets.UTF_8);
        }

        @Override
        public <T> T decode(byte[] bytes, Class<T> type) {
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
                public byte[] encodeArguments(Object[] arguments) {
                    onEncode.run();
                    return arguments.length == 0
                            ? new byte[0]
                            : arguments[0].toString()
                                    .getBytes(StandardCharsets.UTF_8);
                }

                @Override
                public Object[] decodeArguments(byte[] payload) {
                    return new Object[] {
                            new String(payload, StandardCharsets.UTF_8)
                    };
                }

                @Override
                public byte[] encodeResult(Object value) {
                    return encode(value);
                }

                @Override
                public Object decodeResult(byte[] payload) {
                    onDecode.run();
                    return new String(
                            payload,
                            StandardCharsets.UTF_8);
                }
            };
        }
    }
}
