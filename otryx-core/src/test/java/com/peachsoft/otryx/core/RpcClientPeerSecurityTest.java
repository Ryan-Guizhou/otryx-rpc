package com.peachsoft.otryx.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.peachsoft.otryx.api.ApplicationKey;
import com.peachsoft.otryx.api.OtryxRpcIdempotent;
import com.peachsoft.otryx.api.RpcAuthenticationException;
import com.peachsoft.otryx.api.RpcPeerIdentity;
import com.peachsoft.otryx.api.RpcSecurityProfile;
import com.peachsoft.otryx.api.RpcTarget;
import com.peachsoft.otryx.api.ServiceKey;
import com.peachsoft.otryx.codec.RpcCodecRegistry;
import com.peachsoft.otryx.transport.RpcTransportResponse;
import java.lang.reflect.Field;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * Core 在实际响应解码与交付边界复核同一连接身份，且安全失败不污染健康统计或自动重试。
 *
 * @Author OpenAI
 * @Version 1.0.0-SNAPSHOT
 * @CreateTime 2026/10/19 11:18
 */
class RpcClientPeerSecurityTest {
    private static final ServiceKey KEY = new ServiceKey(Api.class.getName(), "1.0.0", "default");

    @Test
    void responseExpiringInCompletionQueueMustNotEnterItsDecoder() throws Exception {
        try (var control = new RpcSecurityTestFixtures.ControlPlane()) {
            var transport = new RpcSecurityTestFixtures.ClientTransport(control.clock);
            var codec = new RpcSecurityTestFixtures.TestCodec();
            CountDownLatch decodingFirst = new CountDownLatch(1);
            CountDownLatch releaseFirst = new CountDownLatch(1);
            AtomicInteger sequence = new AtomicInteger();
            codec.decodeResult = () -> {
                if (codec.resultDecodes.get() == 1) {
                    decodingFirst.countDown();
                    await(releaseFirst);
                }
            };
            transport.responder = (target, frame, timeout) -> CompletableFuture.completedFuture(
                    new RpcTransportResponse(RpcSecurityTestFixtures.success(frame),
                            RpcSecurityTestFixtures.peer(target.application(), control.clock,
                            Duration.ofSeconds(sequence.incrementAndGet() == 1 ? 60 : 1))));
            ExecutorService callers = Executors.newFixedThreadPool(2);
            try (OtryxRpcClient client = control.clientBuilder(transport, RpcCodecRegistry.of(codec))
                    .securityProfile(RpcSecurityProfile.NETWORK_MTLS).responseCompletionThreads(1)
                    .responseCompletionQueueCapacity(2).timeout(Duration.ofSeconds(10)).build()) {
                Api api = reference(control, client);
                CompletableFuture<String> first = CompletableFuture.supplyAsync(api::call, callers);
                assertTrue(decodingFirst.await(2, TimeUnit.SECONDS));
                CompletableFuture<String> second = CompletableFuture.supplyAsync(api::call, callers);
                ApplicationTestControlPlane.await(() -> completionQueueSize(client) == 1,
                        "Second valid response did not enter the completion queue");
                control.clock.advance(Duration.ofSeconds(1));
                releaseFirst.countDown();
                assertEquals("ok", first.get(2, TimeUnit.SECONDS));
                assertEquals(RpcAuthenticationException.Reason.IDENTITY_EXPIRED,
                        authenticationFailure(second).reason());
                assertEquals(1, codec.resultDecodes.get());
                assertEquals(2, transport.requests.get());
                assertInflightZero(client);
            } finally {
                releaseFirst.countDown();
                callers.shutdownNow();
                assertTrue(callers.awaitTermination(2, TimeUnit.SECONDS));
            }
        }
    }

    @Test
    void identityExpiringInsideDecoderMustNotDeliverSuccess() throws Exception {
        try (var control = new RpcSecurityTestFixtures.ControlPlane()) {
            var transport = new RpcSecurityTestFixtures.ClientTransport(control.clock);
            var codec = new RpcSecurityTestFixtures.TestCodec();
            CountDownLatch entered = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            codec.decodeResult = () -> {
                entered.countDown();
                await(release);
            };
            transport.responder = (target, frame, timeout) -> CompletableFuture.completedFuture(
                    new RpcTransportResponse(RpcSecurityTestFixtures.success(frame),
                            RpcSecurityTestFixtures.peer(target.application(), control.clock,
                            Duration.ofSeconds(1))));
            ExecutorService caller = Executors.newSingleThreadExecutor();
            try (OtryxRpcClient client = control.clientBuilder(transport, RpcCodecRegistry.of(codec))
                    .securityProfile(RpcSecurityProfile.NETWORK_MTLS).timeout(Duration.ofSeconds(10)).build()) {
                Api api = reference(control, client);
                CompletableFuture<String> response = CompletableFuture.supplyAsync(api::call, caller);
                AtomicInteger successes = new AtomicInteger();
                response.thenAccept(value -> successes.incrementAndGet());
                assertTrue(entered.await(2, TimeUnit.SECONDS));
                control.clock.advance(Duration.ofSeconds(1));
                release.countDown();
                assertEquals(RpcAuthenticationException.Reason.IDENTITY_EXPIRED,
                        authenticationFailure(response).reason());
                assertEquals(0, successes.get());
                assertEquals(1, codec.resultDecodes.get());
                assertEquals(1, transport.requests.get());
                assertInflightZero(client);
            } finally {
                release.countDown();
                caller.shutdownNow();
                assertTrue(caller.awaitTermination(2, TimeUnit.SECONDS));
            }
        }
    }

    @Test
    void wrongApplicationNamespaceAndAnonymousResponsesNeverReachDecoder() throws Exception {
        for (int scenario = 0; scenario < 3; scenario++) {
            try (var control = new RpcSecurityTestFixtures.ControlPlane()) {
                var transport = new RpcSecurityTestFixtures.ClientTransport(control.clock);
                var codec = new RpcSecurityTestFixtures.TestCodec();
                RpcPeerIdentity peer = scenario == 0 ? RpcPeerIdentity.anonymous()
                        : RpcSecurityTestFixtures.peer(scenario == 1
                                ? new ApplicationKey(RpcSecurityTestFixtures.NAMESPACE, "imposter")
                                : new ApplicationKey("foreign", "provider"), control.clock, Duration.ofSeconds(10));
                transport.responder = (target, frame, timeout) -> CompletableFuture.completedFuture(
                        new RpcTransportResponse(RpcSecurityTestFixtures.success(frame), peer));
                try (OtryxRpcClient client = control.clientBuilder(transport, RpcCodecRegistry.of(codec))
                        .securityProfile(RpcSecurityProfile.NETWORK_MTLS).build()) {
                    Api api = reference(control, client);
                    RpcAuthenticationException failure = authenticationFailure(api::call);
                    RpcAuthenticationException.Reason expected = switch (scenario) {
                        case 0 -> RpcAuthenticationException.Reason.IDENTITY_UNBOUND;
                        case 1 -> RpcAuthenticationException.Reason.APPLICATION_MISMATCH;
                        default -> RpcAuthenticationException.Reason.NAMESPACE_MISMATCH;
                    };
                    assertEquals(expected, failure.reason());
                    assertEquals(0, codec.resultDecodes.get());
                    assertEquals(1, transport.requests.get());
                    assertInflightZero(client);
                }
            }
        }
    }

    @Test
    void typedSecurityContextChangesAreNeverRetriedOrRecordedAsEndpointFailures() throws Exception {
        for (int scenario = 0; scenario < 3; scenario++) {
            int delivery = scenario;
            try (var control = new RpcSecurityTestFixtures.ControlPlane()) {
                var transport = new RpcSecurityTestFixtures.ClientTransport(control.clock);
                var codec = new RpcSecurityTestFixtures.TestCodec();
                transport.responder = (target, frame, timeout) -> {
                    var rejected = new RpcAuthenticationException(
                            RpcAuthenticationException.Reason.SECURITY_CONTEXT_CHANGED);
                    if (delivery == 1) {
                        throw rejected;
                    }
                    if (delivery == 2) {
                        throw new CompletionException(new CompletionException(rejected));
                    }
                    return CompletableFuture.failedFuture(new CompletionException(new CompletionException(rejected)));
                };
                try (OtryxRpcClient client = control.clientBuilder(transport, RpcCodecRegistry.of(codec))
                        .securityProfile(RpcSecurityProfile.NETWORK_MTLS).build()) {
                    Api api = reference(control, client);
                    assertEquals(RpcAuthenticationException.Reason.SECURITY_CONTEXT_CHANGED,
                            authenticationFailure(api::call).reason());
                    EndpointStats stats = endpointStats(client);
                    assertEquals(1_000_000L, stats.ewma());
                    assertTrue(stats.available());
                    assertEquals(0, stats.inflight());
                    assertEquals(0, codec.resultDecodes.get());
                    assertEquals(1, transport.requests.get());
                    transport.responder = (target, frame, timeout) -> CompletableFuture.completedFuture(
                            new RpcTransportResponse(RpcSecurityTestFixtures.success(frame),
                                    RpcSecurityTestFixtures.peer(target.application(),
                                            control.clock, Duration.ofSeconds(10))));
                    assertEquals("ok", api.call(), "Security rejection must not open the method circuit");
                    assertEquals(2, transport.requests.get());
                }
            }
        }
    }

    @Test
    void securityFailureDoesNotResetPriorFailureHistoryOrChangeLatency() {
        EndpointStats stats = new EndpointStats();
        RpcClientResilienceOptions options = new RpcClientResilienceOptions(1, 0, 0, 0,
                Duration.ZERO, Duration.ZERO, 2, Duration.ofSeconds(10), 20, Duration.ofSeconds(10));
        stats.begin();
        assertFalse(stats.endFailure(8_000_000L, options));
        long latency = stats.ewma();
        stats.begin();
        stats.endSecurityRejected();
        assertEquals(latency, stats.ewma());
        assertEquals(0, stats.inflight());
        stats.begin();
        assertTrue(stats.endFailure(8_000_000L, options), "Prior endpoint failure must survive neutral rejection");
    }

    private static Api reference(RpcSecurityTestFixtures.ControlPlane control, OtryxRpcClient client) {
        control.publish(Api.class);
        Api api = client.refer(Api.class, "1.0.0", "default");
        ApplicationTestControlPlane.awaitReady(client, KEY);
        return api;
    }

    private static RpcAuthenticationException authenticationFailure(Runnable call) {
        CompletionException wrapper = assertThrows(CompletionException.class, call::run);
        Throwable cause = wrapper;
        while (cause instanceof CompletionException && cause.getCause() != null) {
            cause = cause.getCause();
        }
        assertTrue(cause instanceof RpcAuthenticationException, "Expected a typed authentication failure");
        return (RpcAuthenticationException) cause;
    }

    private static RpcAuthenticationException authenticationFailure(CompletableFuture<?> result) throws Exception {
        ExecutionException failure = assertThrows(ExecutionException.class, () -> result.get(2, TimeUnit.SECONDS));
        Throwable cause = failure.getCause();
        while (cause instanceof CompletionException && cause.getCause() != null) {
            cause = cause.getCause();
        }
        assertTrue(cause instanceof RpcAuthenticationException, "Expected a typed authentication failure");
        return (RpcAuthenticationException) cause;
    }

    private static int completionQueueSize(OtryxRpcClient client) {
        try {
            Field field = OtryxRpcClient.class.getDeclaredField("responseCompletionExecutor");
            field.setAccessible(true);
            return ((ThreadPoolExecutor) field.get(client)).getQueue().size();
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }

    private static EndpointStats endpointStats(OtryxRpcClient client) throws Exception {
        Field field = OtryxRpcClient.class.getDeclaredField("stats");
        field.setAccessible(true);
        Map<?, ?> stats = (Map<?, ?>) field.get(client);
        return (EndpointStats) stats.get(
                new RpcTarget(RpcSecurityTestFixtures.PROVIDER, RpcSecurityTestFixtures.ENDPOINT));
    }

    private static void assertInflightZero(OtryxRpcClient client) throws Exception {
        assertEquals(0, endpointStats(client).inflight());
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("Controlled decoder was not released");
            }
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new AssertionError(failure);
        }
    }

    public interface Api {
        @OtryxRpcIdempotent
        String call();
    }
}
