package com.peachsoft.otryx.transport.vertx;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.peachsoft.otryx.api.ApplicationKey;
import com.peachsoft.otryx.api.RpcEndpoint;
import com.peachsoft.otryx.api.RpcTarget;
import com.peachsoft.otryx.api.RpcStatus;
import com.peachsoft.otryx.api.RpcTimeoutException;
import com.peachsoft.otryx.codec.RpcCodecIds;
import com.peachsoft.otryx.observability.RpcConnectionCloseReason;
import com.peachsoft.otryx.observability.RpcConnectionRole;
import com.peachsoft.otryx.observability.RpcObserver;
import com.peachsoft.otryx.protocol.RpcFrame;
import com.peachsoft.otryx.protocol.RpcHandshakeCodec;
import com.peachsoft.otryx.protocol.RpcMessageType;
import com.peachsoft.otryx.protocol.RpcProtocolCodec;
import com.peachsoft.otryx.protocol.RpcProtocolException;
import com.peachsoft.otryx.transport.RpcTransportOptions;
import com.peachsoft.otryx.transport.RpcTransportResponse;
import java.io.ByteArrayOutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * 验证 Vert.x Transport 的往返调用及连接生命周期。
 *
 * @Author Ryan
 * @Version 1.0.0
 * @CreateTime 2026/9/23 10:51
 */
class VertxRpcTransportTest {

    @Test
    void clientAndServerShouldExchangeMultiplexedFrame()
            throws Exception {
        int port = findFreePort();
        RpcTransportOptions options = new RpcTransportOptions(
                32,
                1024 * 1024,
                1024 * 1024,
                Duration.ofSeconds(2));
        VertxRpcTransportServer server =
                new VertxRpcTransportServer(options);
        VertxRpcTransportClient client =
                new VertxRpcTransportClient(options);
        RpcEndpoint endpoint =
                new RpcEndpoint("127.0.0.1", port);

        byte[] responsePayload = new byte[] {9, 8, 7};
        try {
            server.start(
                            endpoint,
                            (remote, requestBytes) ->
                                    CompletableFuture.completedFuture(
                                            response(
                                                    requestBytes,
                                                    responsePayload)))
                    .toCompletableFuture()
                    .join();

            byte[] response = client.request(
                            target(endpoint),
                            request(),
                            Duration.ofSeconds(2))
                    .toCompletableFuture()
                    .join().frame();

            RpcFrame decoded = RpcProtocolCodec.decode(response);
            assertArrayEquals(
                    responsePayload,
                    decoded.payload());
            assertTrue(decoded.requestId() > 0L);
        } finally {
            client.close();
            server.close();
        }
    }

    @Test
    void subMillisecondBudgetMustNeverBeRoundedUpOnWire() throws Exception {
        int port = findFreePort();
        RpcTransportOptions options = new RpcTransportOptions(
                32, 1024 * 1024, 1024 * 1024, Duration.ofSeconds(2));
        VertxRpcTransportServer server = new VertxRpcTransportServer(options);
        VertxRpcTransportClient client = new VertxRpcTransportClient(options);
        RpcEndpoint endpoint = new RpcEndpoint("127.0.0.1", port);
        AtomicInteger executions = new AtomicInteger();
        try {
            server.start(endpoint, (remote, bytes) -> {
                executions.incrementAndGet();
                return CompletableFuture.completedFuture(
                        response(bytes, new byte[] {1}));
            }).toCompletableFuture().join();
            client.request(target(endpoint), request(), Duration.ofSeconds(2))
                    .toCompletableFuture().join().frame();
            assertEquals(1, executions.get());

            CompletionException failed = assertThrows(
                    CompletionException.class,
                    () -> client.request(target(endpoint), request(), Duration.ofNanos(1L))
                            .toCompletableFuture().join().frame());
            assertInstanceOf(RpcTimeoutException.class, failed.getCause());
            assertEquals(1, executions.get(),
                    "Submillisecond budgets cannot dispatch a request with refreshed 1ms");
        } finally {
            client.close();
            server.close();
        }
    }

    @Test
    void requestTimeoutMustFinishWhileHandshakeIsStillPending()
            throws Exception {
        RpcTransportOptions options = new RpcTransportOptions(
                32,
                1024 * 1024,
                1024 * 1024,
                Duration.ofSeconds(2));
        try (ServerSocket silentPeer = new ServerSocket(0);
             VertxRpcTransportClient client =
                     new VertxRpcTransportClient(options)) {
            CountDownLatch accepted = new CountDownLatch(1);
            Thread peer = Thread.ofVirtual().start(() -> {
                try (Socket ignored = silentPeer.accept()) {
                    accepted.countDown();
                    Thread.sleep(400L);
                } catch (java.io.IOException error) {
                    throw new AssertionError(error);
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                }
            });
            RpcEndpoint endpoint =
                    new RpcEndpoint("127.0.0.1", silentPeer.getLocalPort());
            long started = System.nanoTime();
            CompletionException failure = assertThrows(
                    CompletionException.class,
                    () -> client.request(
                                    target(endpoint),
                                    request(),
                                    Duration.ofMillis(70))
                            .toCompletableFuture()
                            .join().frame());

            assertInstanceOf(RpcTimeoutException.class, failure.getCause());
            assertTrue(accepted.await(2, TimeUnit.SECONDS));
            assertTrue(System.nanoTime() - started
                            < Duration.ofSeconds(1).toNanos(),
                    "The RPC deadline must not wait for handshake timeout");
            peer.join(1000L);
        }
    }

    @Test
    void idleEndpointPoolShouldBeRetiredAndReconnectOnNextRequest()
            throws Exception {
        int port = findFreePort();
        RpcTransportOptions options = new RpcTransportOptions(
                32,
                1024 * 1024,
                1024 * 1024,
                Duration.ofSeconds(2));
        VertxRpcTransportServer server =
                new VertxRpcTransportServer(options);
        VertxRpcTransportClient client =
                new VertxRpcTransportClient(options);
        RpcEndpoint endpoint = new RpcEndpoint("127.0.0.1", port);
        try {
            server.start(
                            endpoint,
                            (remote, bytes) ->
                                    CompletableFuture.completedFuture(
                                            response(bytes, new byte[] {1})))
                    .toCompletableFuture()
                    .join();
            client.request(target(endpoint), request(), Duration.ofSeconds(2))
                    .toCompletableFuture()
                    .join().frame();
            assertEquals(1, client.connectionGroupCount());

            client.evictIdleGroups(
                    System.nanoTime() + Duration.ofMinutes(6).toNanos());
            assertEquals(0, client.connectionGroupCount());

            byte[] reply = client.request(
                            target(endpoint), request(), Duration.ofSeconds(2))
                    .toCompletableFuture()
                    .join().frame();
            assertEquals(RpcStatus.OK, RpcProtocolCodec.view(reply).status());
            assertEquals(1, client.connectionGroupCount());
        } finally {
            client.close();
            server.close();
        }
    }

    @Test
    void shouldFailHandshakeWhenNoCommonCodecExists()
            throws Exception {
        int port = findFreePort();
        RpcTransportOptions serverOptions = options(
                Set.of(RpcCodecIds.FORY_NATIVE),
                1);
        RpcTransportOptions clientOptions = options(
                Set.of(RpcCodecIds.PROTOBUF),
                1);
        VertxRpcTransportServer server =
                new VertxRpcTransportServer(serverOptions);
        VertxRpcTransportClient client =
                new VertxRpcTransportClient(clientOptions);
        RpcEndpoint endpoint =
                new RpcEndpoint("127.0.0.1", port);
        AtomicInteger handled = new AtomicInteger();

        try {
            server.start(
                            endpoint,
                            (remote, requestBytes) -> {
                                handled.incrementAndGet();
                                return CompletableFuture.completedFuture(
                                        response(
                                                requestBytes,
                                                new byte[] {1}));
                            })
                    .toCompletableFuture()
                    .join();

            CompletionException error = assertThrows(
                    CompletionException.class,
                    () -> client.request(
                                    target(endpoint),
                                    request(),
                                    Duration.ofSeconds(2))
                            .toCompletableFuture()
                            .join().frame());

            assertInstanceOf(
                    RpcProtocolException.class,
                    error.getCause());
            assertEquals(0, handled.get());
        } finally {
            client.close();
            server.close();
        }
    }

    @Test
    void shouldShardRequestsAcrossConfiguredConnections()
            throws Exception {
        int port = findFreePort();
        RpcTransportOptions options = options(
                Set.of(RpcCodecIds.FORY_NATIVE),
                2);
        VertxRpcTransportServer server =
                new VertxRpcTransportServer(options);
        VertxRpcTransportClient client =
                new VertxRpcTransportClient(options);
        RpcEndpoint endpoint =
                new RpcEndpoint("127.0.0.1", port);
        Set<Integer> remotePorts =
                ConcurrentHashMap.newKeySet();

        try {
            server.start(
                            endpoint,
                            (remote, requestBytes) -> {
                                remotePorts.add(remote.port());
                                return CompletableFuture.completedFuture(
                                        response(
                                                requestBytes,
                                                new byte[] {1}));
                            })
                    .toCompletableFuture()
                    .join();

            for (int index = 0; index < 4; index++) {
                client.request(
                                target(endpoint),
                                request(),
                                Duration.ofSeconds(2))
                        .toCompletableFuture()
                        .join().frame();
            }

            assertEquals(2, remotePorts.size());
        } finally {
            client.close();
            server.close();
        }
    }


    @Test
    void clientCancellationShouldCancelProviderRequest()
            throws Exception {
        int port = findFreePort();
        RpcTransportOptions options = options(
                Set.of(RpcCodecIds.FORY_NATIVE),
                1);
        VertxRpcTransportServer server =
                new VertxRpcTransportServer(options);
        VertxRpcTransportClient client =
                new VertxRpcTransportClient(options);
        RpcEndpoint endpoint =
                new RpcEndpoint("127.0.0.1", port);
        CountDownLatch handled =
                new CountDownLatch(1);
        AtomicReference<CompletableFuture<byte[]>> provider =
                new AtomicReference<>();

        try {
            server.start(endpoint, (remote, requestBytes) -> {
                        CompletableFuture<byte[]> pending =
                                new CompletableFuture<>();
                        provider.set(pending);
                        handled.countDown();
                        return pending;
                    })
                    .toCompletableFuture()
                    .join();

            CompletableFuture<RpcTransportResponse> call = client.request(
                            target(endpoint),
                            request(),
                            Duration.ofSeconds(5))
                    .toCompletableFuture();
            assertTrue(handled.await(2, TimeUnit.SECONDS));
            assertTrue(call.cancel(true));

            long deadline = System.nanoTime()
                    + TimeUnit.SECONDS.toNanos(2);
            while (!provider.get().isCancelled()
                    && System.nanoTime() < deadline) {
                Thread.sleep(10);
            }
            assertTrue(provider.get().isCancelled());
        } finally {
            client.close();
            server.close();
        }
    }

    @Test
    void responseAndCancellationRaceShouldNotPoisonConnection()
            throws Exception {
        int port = findFreePort();
        RpcTransportOptions options = options(
                Set.of(RpcCodecIds.FORY_NATIVE),
                1);
        VertxRpcTransportServer server =
                new VertxRpcTransportServer(options);
        VertxRpcTransportClient client =
                new VertxRpcTransportClient(options);
        RpcEndpoint endpoint =
                new RpcEndpoint("127.0.0.1", port);

        try {
            server.start(endpoint, (remote, requestBytes) -> {
                        CompletableFuture<byte[]> pending =
                                new CompletableFuture<>();
                        CompletableFuture.delayedExecutor(
                                        2,
                                        TimeUnit.MILLISECONDS)
                                .execute(() -> pending.complete(
                                        response(
                                                requestBytes,
                                                new byte[] {7})));
                        return pending;
                    })
                    .toCompletableFuture()
                    .join();

            for (int index = 0; index < 30; index++) {
                CompletableFuture<RpcTransportResponse> call = client.request(
                                target(endpoint),
                                request(),
                                Duration.ofSeconds(1))
                        .toCompletableFuture();
                CompletableFuture.delayedExecutor(
                                index % 3,
                                TimeUnit.MILLISECONDS)
                        .execute(() -> call.cancel(true));
                try {
                    call.join().frame();
                } catch (RuntimeException ignored) {
                    // Either response or cancellation may win this race.
                }
                Thread.sleep(3L);
            }

            byte[] finalResponse = client.request(
                            target(endpoint),
                            request(),
                            Duration.ofSeconds(2))
                    .toCompletableFuture()
                    .join().frame();
            assertArrayEquals(
                    new byte[] {7},
                    RpcProtocolCodec.decode(
                            finalResponse)
                            .payload());
        } finally {
            client.close();
            server.close();
        }
    }

    @Test
    void responseAndTimeoutRaceShouldNotPoisonConnection()
            throws Exception {
        int port = findFreePort();
        RpcTransportOptions options = options(
                Set.of(RpcCodecIds.FORY_NATIVE),
                1);
        VertxRpcTransportServer server =
                new VertxRpcTransportServer(options);
        VertxRpcTransportClient client =
                new VertxRpcTransportClient(options);
        RpcEndpoint endpoint =
                new RpcEndpoint("127.0.0.1", port);

        try {
            server.start(endpoint, (remote, requestBytes) -> {
                        CompletableFuture<byte[]> pending =
                                new CompletableFuture<>();
                        CompletableFuture.delayedExecutor(
                                        5,
                                        TimeUnit.MILLISECONDS)
                                .execute(() -> pending.complete(
                                        response(
                                                requestBytes,
                                                new byte[] {8})));
                        return pending;
                    })
                    .toCompletableFuture()
                    .join();

            for (int index = 0; index < 20; index++) {
                try {
                    client.request(
                                    target(endpoint),
                                    request(),
                                    Duration.ofMillis(5))
                            .toCompletableFuture()
                            .join().frame();
                } catch (RuntimeException ignored) {
                    // Either response or timeout may win this race.
                }
                Thread.sleep(6L);
            }

            byte[] finalResponse = client.request(
                            target(endpoint),
                            request(),
                            Duration.ofSeconds(2))
                    .toCompletableFuture()
                    .join().frame();
            assertArrayEquals(
                    new byte[] {8},
                    RpcProtocolCodec.decode(
                            finalResponse)
                            .payload());
        } finally {
            client.close();
            server.close();
        }
    }

    @Test
    void cancellationAndDisconnectRaceShouldComplete()
            throws Exception {
        int port = findFreePort();
        RpcTransportOptions options = options(
                Set.of(RpcCodecIds.FORY_NATIVE),
                1);
        VertxRpcTransportServer server =
                new VertxRpcTransportServer(options);
        VertxRpcTransportClient client =
                new VertxRpcTransportClient(options);
        RpcEndpoint endpoint =
                new RpcEndpoint("127.0.0.1", port);
        CountDownLatch handled =
                new CountDownLatch(1);
        CompletableFuture<byte[]> provider =
                new CompletableFuture<>();

        try {
            server.start(endpoint, (remote, requestBytes) -> {
                        handled.countDown();
                        return provider;
                    })
                    .toCompletableFuture()
                    .join();

            CompletableFuture<RpcTransportResponse> call =
                    client.request(
                                    target(endpoint),
                                    request(),
                                    Duration.ofSeconds(2))
                            .toCompletableFuture();
            assertTrue(
                    handled.await(
                            2,
                            TimeUnit.SECONDS));

            CountDownLatch start =
                    new CountDownLatch(1);
            Thread cancelThread =
                    Thread.ofVirtual().start(() -> {
                        awaitUnchecked(start);
                        call.cancel(true);
                    });
            Thread disconnectThread =
                    Thread.ofVirtual().start(() -> {
                        awaitUnchecked(start);
                        server.close();
                    });

            start.countDown();
            cancelThread.join();
            disconnectThread.join();

            long deadline =
                    System.nanoTime()
                            + TimeUnit.SECONDS.toNanos(2);
            while (!call.isDone()
                    && System.nanoTime() < deadline) {
                Thread.sleep(10L);
            }
            assertTrue(call.isDone());
        } finally {
            provider.cancel(true);
            client.close();
            server.close();
        }
    }

    @Test
    void drainShouldRejectNewRequestWhileInflightCompletes()
            throws Exception {
        int port = findFreePort();
        RpcTransportOptions options = options(
                Set.of(RpcCodecIds.FORY_NATIVE),
                1);
        VertxRpcTransportServer server =
                new VertxRpcTransportServer(options);
        VertxRpcTransportClient client =
                new VertxRpcTransportClient(options);
        RpcEndpoint endpoint =
                new RpcEndpoint("127.0.0.1", port);
        CountDownLatch handled =
                new CountDownLatch(1);
        AtomicReference<byte[]> firstRequest =
                new AtomicReference<>();
        CompletableFuture<byte[]> firstProvider =
                new CompletableFuture<>();

        try {
            server.start(endpoint, (remote, requestBytes) -> {
                        firstRequest.compareAndSet(
                                null,
                                requestBytes);
                        if (handled.getCount() > 0L) {
                            handled.countDown();
                            return firstProvider;
                        }
                        return CompletableFuture.completedFuture(
                                response(
                                        requestBytes,
                                        new byte[] {9}));
                    })
                    .toCompletableFuture()
                    .join();

            CompletableFuture<RpcTransportResponse> firstCall =
                    client.request(
                                    target(endpoint),
                                    request(),
                                    Duration.ofSeconds(3))
                            .toCompletableFuture();
            assertTrue(
                    handled.await(
                            2,
                            TimeUnit.SECONDS));

            CompletableFuture<Void> drain =
                    server.drain(
                                    Duration.ofSeconds(2))
                            .toCompletableFuture();

            CompletableFuture<RpcTransportResponse> secondCall =
                    client.request(
                                    target(endpoint),
                                    request(),
                                    Duration.ofMillis(500))
                            .toCompletableFuture();

            boolean rejected;
            try {
                byte[] secondResponse =
                        secondCall.join().frame();
                rejected = RpcProtocolCodec.decode(
                                secondResponse)
                        .status()
                        == RpcStatus.UNAVAILABLE;
            } catch (RuntimeException expected) {
                rejected = true;
            }
            assertTrue(rejected);

            firstProvider.complete(
                    response(
                            firstRequest.get(),
                            new byte[] {4}));
            assertArrayEquals(
                    new byte[] {4},
                    RpcProtocolCodec.decode(
                                    firstCall.join().frame())
                            .payload());
            drain.join();
        } finally {
            client.close();
            server.close();
        }
    }

    @Test
    void closeDuringHeartbeatShouldBeSafe()
            throws Exception {
        int port = findFreePort();
        RpcTransportOptions options =
                haOptions();
        VertxRpcTransportServer server =
                new VertxRpcTransportServer(options);
        VertxRpcTransportClient client =
                new VertxRpcTransportClient(options);
        RpcEndpoint endpoint =
                new RpcEndpoint("127.0.0.1", port);

        try {
            server.start(
                            endpoint,
                            (remote, requestBytes) ->
                                    CompletableFuture.completedFuture(
                                            response(
                                                    requestBytes,
                                                    new byte[] {1})))
                    .toCompletableFuture()
                    .join();

            client.request(
                            target(endpoint),
                            request(),
                            Duration.ofSeconds(2))
                    .toCompletableFuture()
                    .join().frame();

            Thread.sleep(75L);

            CountDownLatch start =
                    new CountDownLatch(1);
            Thread clientClose =
                    Thread.ofVirtual().start(() -> {
                        awaitUnchecked(start);
                        client.close();
                    });
            Thread serverClose =
                    Thread.ofVirtual().start(() -> {
                        awaitUnchecked(start);
                        server.close();
                    });
            start.countDown();
            clientClose.join();
            serverClose.join();
        } finally {
            client.close();
            server.close();
        }
    }

    @Test
    void serverShouldRejectRequestBeforeHello()
            throws Exception {
        int port = findFreePort();
        RpcTransportOptions options = options(
                Set.of(RpcCodecIds.FORY_NATIVE),
                1);
        VertxRpcTransportServer server =
                new VertxRpcTransportServer(options);
        RpcEndpoint endpoint =
                new RpcEndpoint("127.0.0.1", port);

        try {
            server.start(
                            endpoint,
                            (remote, requestBytes) ->
                                    CompletableFuture.completedFuture(
                                            response(
                                                    requestBytes,
                                                    new byte[] {1})))
                    .toCompletableFuture()
                    .join();

            try (Socket socket =
                         new Socket(
                                 endpoint.host(),
                                 endpoint.port())) {
                socket.setSoTimeout(2000);
                byte[] request = request();
                RpcProtocolCodec.writeRequestId(
                        request,
                        1L);
                socket.getOutputStream().write(request);
                socket.getOutputStream().flush();

                assertProtocolGoAway(readFrame(socket));
            }
        } finally {
            server.close();
        }
    }

    @Test
    void serverShouldRejectMalformedFramesAfterHandshake()
            throws Exception {
        int port = findFreePort();
        RpcTransportOptions options = options(
                Set.of(RpcCodecIds.FORY_NATIVE),
                1);
        VertxRpcTransportServer server =
                new VertxRpcTransportServer(options);
        RpcEndpoint endpoint =
                new RpcEndpoint("127.0.0.1", port);

        try {
            server.start(
                            endpoint,
                            (remote, requestBytes) ->
                                    CompletableFuture.completedFuture(
                                            response(
                                                    requestBytes,
                                                    new byte[] {1})))
                    .toCompletableFuture()
                    .join();

            byte[] zeroRequestId = request();

            byte[] unknownCodec = request();
            RpcProtocolCodec.writeRequestId(
                    unknownCodec,
                    2L);
            unknownCodec[7] = 127;

            byte[] unsupportedCompression = request();
            RpcProtocolCodec.writeRequestId(
                    unsupportedCompression,
                    3L);
            unsupportedCompression[8] = 1;

            byte[] invalidCancel =
                    RpcProtocolCodec.encode(
                            new RpcFrame(
                                    RpcMessageType.CANCEL,
                                    RpcCodecIds.CONTROL,
                                    RpcStatus.OK,
                                    0L,
                                    0,
                                    0,
                                    Map.of(),
                                    new byte[0]));

            byte[] invalidHeartbeat =
                    RpcProtocolCodec.encode(
                            new RpcFrame(
                                    RpcMessageType.PING,
                                    RpcCodecIds.CONTROL,
                                    RpcStatus.OK,
                                    0L,
                                    0,
                                    0,
                                    Map.of(),
                                    new byte[] {1}));

            for (byte[] malformed :
                    new byte[][] {
                            zeroRequestId,
                            unknownCodec,
                            unsupportedCompression,
                            invalidCancel,
                            invalidHeartbeat
                    }) {
                assertRejectedAfterHandshake(
                        endpoint,
                        options,
                        malformed);
            }
        } finally {
            server.close();
        }
    }

    @Test
    void serverShouldRejectConcurrentDuplicateRequestId()
            throws Exception {
        int port = findFreePort();
        RpcTransportOptions options = options(
                Set.of(RpcCodecIds.FORY_NATIVE),
                1);
        VertxRpcTransportServer server =
                new VertxRpcTransportServer(options);
        RpcEndpoint endpoint =
                new RpcEndpoint("127.0.0.1", port);
        CompletableFuture<byte[]> first =
                new CompletableFuture<>();
        CountDownLatch handled =
                new CountDownLatch(1);
        AtomicInteger invocations =
                new AtomicInteger();

        try {
            server.start(endpoint, (remote, requestBytes) -> {
                        invocations.incrementAndGet();
                        handled.countDown();
                        return first;
                    })
                    .toCompletableFuture()
                    .join();

            try (Socket socket =
                         new Socket(
                                 endpoint.host(),
                                 endpoint.port())) {
                socket.setSoTimeout(2000);
                performRawHandshake(
                        socket,
                        options);

                byte[] duplicate = request();
                RpcProtocolCodec.writeRequestId(
                        duplicate,
                        77L);
                socket.getOutputStream().write(
                        duplicate);
                socket.getOutputStream().flush();
                assertTrue(
                        handled.await(
                                2,
                                TimeUnit.SECONDS));

                socket.getOutputStream().write(
                        duplicate);
                socket.getOutputStream().flush();

                assertProtocolGoAway(
                        readFrame(socket));
                assertEquals(
                        1,
                        invocations.get());
            }
        } finally {
            first.cancel(true);
            server.close();
        }
    }

    @Test
    void gracefulDrainShouldWaitForInflightRequest()
            throws Exception {
        int port = findFreePort();
        RpcTransportOptions options = options(
                Set.of(RpcCodecIds.FORY_NATIVE),
                1);
        VertxRpcTransportServer server =
                new VertxRpcTransportServer(options);
        VertxRpcTransportClient client =
                new VertxRpcTransportClient(options);
        RpcEndpoint endpoint =
                new RpcEndpoint("127.0.0.1", port);
        CountDownLatch handled = new CountDownLatch(1);
        AtomicReference<byte[]> requestBytes = new AtomicReference<>();
        CompletableFuture<byte[]> provider = new CompletableFuture<>();

        try {
            server.start(endpoint, (remote, bytes) -> {
                        requestBytes.set(bytes);
                        handled.countDown();
                        return provider;
                    })
                    .toCompletableFuture()
                    .join();

            CompletableFuture<RpcTransportResponse> call = client.request(
                            target(endpoint),
                            request(),
                            Duration.ofSeconds(5))
                    .toCompletableFuture();
            assertTrue(handled.await(2, TimeUnit.SECONDS));

            CompletableFuture<Void> drain = server.drain(
                            Duration.ofSeconds(2))
                    .toCompletableFuture();
            assertFalse(drain.isDone());

            provider.complete(response(
                    requestBytes.get(),
                    new byte[] {4, 5, 6}));
            assertArrayEquals(
                    new byte[] {4, 5, 6},
                    RpcProtocolCodec.decode(call.join().frame()).payload());
            drain.join();
        } finally {
            client.close();
            server.close();
        }
    }

    @Test
    void heartbeatShouldKeepIdleConnectionAlive()
            throws Exception {
        int port = findFreePort();
        RpcTransportOptions options = haOptions();
        VertxRpcTransportServer server =
                new VertxRpcTransportServer(options);
        VertxRpcTransportClient client =
                new VertxRpcTransportClient(options);
        RpcEndpoint endpoint =
                new RpcEndpoint("127.0.0.1", port);
        Set<Integer> remotePorts =
                ConcurrentHashMap.newKeySet();

        try {
            server.start(endpoint, (remote, requestBytes) -> {
                        remotePorts.add(remote.port());
                        return CompletableFuture.completedFuture(
                                response(
                                        requestBytes,
                                        new byte[] {1}));
                    })
                    .toCompletableFuture()
                    .join();

            client.request(
                            target(endpoint),
                            request(),
                            Duration.ofSeconds(2))
                    .toCompletableFuture()
                    .join().frame();

            Thread.sleep(350L);

            client.request(
                            target(endpoint),
                            request(),
                            Duration.ofSeconds(2))
                    .toCompletableFuture()
                    .join().frame();

            assertEquals(1, remotePorts.size());
        } finally {
            client.close();
            server.close();
        }
    }

    @Test
    void clientShouldReconnectAfterServerRestart()
            throws Exception {
        int port = findFreePort();
        AtomicInteger reconnectScheduled =
                new AtomicInteger();
        AtomicInteger clientEstablished =
                new AtomicInteger();
        ConcurrentLinkedQueue<RpcConnectionCloseReason> clientCloses =
                new ConcurrentLinkedQueue<>();
        RpcObserver observer = new RpcObserver() {
            @Override
            public void onConnectionEstablished(
                    RpcConnectionRole role,
                    RpcEndpoint endpoint,
                    long durationNanos) {
                if (role == RpcConnectionRole.CLIENT) {
                    clientEstablished.incrementAndGet();
                }
            }

            @Override
            public void onConnectionReconnectScheduled(
                    RpcEndpoint endpoint,
                    int attempt,
                    long delayMillis) {
                reconnectScheduled.incrementAndGet();
            }

            @Override
            public void onConnectionClosed(
                    RpcConnectionRole role,
                    RpcEndpoint endpoint,
                    RpcConnectionCloseReason reason,
                    Throwable error) {
                if (role == RpcConnectionRole.CLIENT) {
                    clientCloses.add(reason);
                }
            }
        };
        RpcTransportOptions options =
                haOptions().withObserver(observer);
        VertxRpcTransportClient client =
                new VertxRpcTransportClient(options);
        RpcEndpoint endpoint =
                new RpcEndpoint("127.0.0.1", port);
        VertxRpcTransportServer first =
                new VertxRpcTransportServer(options);

        try {
            first.start(
                            endpoint,
                            (remote, requestBytes) ->
                                    CompletableFuture.completedFuture(
                                            response(
                                                    requestBytes,
                                                    new byte[] {1})))
                    .toCompletableFuture()
                    .join();
            client.request(
                            target(endpoint),
                            request(),
                            Duration.ofSeconds(2))
                    .toCompletableFuture()
                    .join().frame();

            first.close();
            Thread.sleep(200L);

            VertxRpcTransportServer replacement =
                    new VertxRpcTransportServer(options);
            try {
                replacement.start(
                                endpoint,
                                (remote, requestBytes) ->
                                        CompletableFuture.completedFuture(
                                                response(
                                                        requestBytes,
                                                        new byte[] {2})))
                        .toCompletableFuture()
                        .join();

                byte[] recovered = awaitResponse(
                        client,
                        endpoint,
                        Duration.ofSeconds(3));
                assertArrayEquals(
                        new byte[] {2},
                        RpcProtocolCodec.decode(recovered).payload());
                assertTrue(clientEstablished.get() >= 2);
                assertTrue(reconnectScheduled.get() >= 1);
                assertTrue(clientCloses.stream().anyMatch(
                        reason -> reason
                                        == RpcConnectionCloseReason.REMOTE_CLOSE
                                || reason
                                        == RpcConnectionCloseReason.TRANSPORT_ERROR));

                // Observe a local close while the recovered socket is still
                // active. Closing the replacement first would turn this
                // into a remote-close-only scenario.
                client.close();
                long closeDeadline =
                        System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
                while (!clientCloses.contains(
                                RpcConnectionCloseReason.LOCAL_CLOSE)
                        && System.nanoTime() < closeDeadline) {
                    Thread.sleep(10L);
                }
                assertTrue(clientCloses.contains(
                        RpcConnectionCloseReason.LOCAL_CLOSE));
            } finally {
                replacement.close();
            }
        } finally {
            client.close();
            first.close();
        }
    }

    @Test
    void clientShouldFailWhenHelloAckNeverArrives()
            throws Exception {
        int port = findFreePort();
        RpcTransportOptions options = new RpcTransportOptions(
                32,
                1024 * 1024,
                1024 * 1024,
                Duration.ofSeconds(2),
                Duration.ofMillis(150),
                Set.of(RpcCodecIds.FORY_NATIVE),
                1);
        VertxRpcTransportClient client =
                new VertxRpcTransportClient(options);
        RpcEndpoint endpoint =
                new RpcEndpoint("127.0.0.1", port);

        try (ServerSocket serverSocket = new ServerSocket(port)) {
            Thread peer = Thread.startVirtualThread(() -> {
                try (Socket ignored = serverSocket.accept()) {
                    Thread.sleep(1000);
                } catch (Exception ignored) {
                }
            });

            CompletionException error = assertThrows(
                    CompletionException.class,
                    () -> client.request(
                                    target(endpoint),
                                    request(),
                                    Duration.ofSeconds(2))
                            .toCompletableFuture()
                            .join().frame());

            assertInstanceOf(
                    com.peachsoft.otryx.api.RpcTimeoutException.class,
                    error.getCause());
            peer.join();
        } finally {
            client.close();
        }
    }

    @Test
    void serverShouldCloseConnectionWhenHelloNeverArrives()
            throws Exception {
        int port = findFreePort();
        RpcTransportOptions options = new RpcTransportOptions(
                32,
                1024 * 1024,
                1024 * 1024,
                Duration.ofSeconds(2),
                Duration.ofMillis(150),
                Set.of(RpcCodecIds.FORY_NATIVE),
                1);
        VertxRpcTransportServer server =
                new VertxRpcTransportServer(options);
        RpcEndpoint endpoint =
                new RpcEndpoint("127.0.0.1", port);

        try {
            server.start(
                            endpoint,
                            (remote, requestBytes) ->
                                    CompletableFuture.completedFuture(
                                            response(
                                                    requestBytes,
                                                    new byte[] {1})))
                    .toCompletableFuture()
                    .join();

            try (Socket socket = new Socket(
                    endpoint.host(),
                    endpoint.port())) {
                socket.setSoTimeout(2000);
                byte[] bytes = readUntilClosed(socket);
                RpcFrame frame = RpcProtocolCodec.decode(bytes);

                assertEquals(
                        RpcMessageType.GO_AWAY,
                        frame.messageType());
                assertEquals(
                        RpcStatus.BAD_REQUEST,
                        frame.status());
            }
        } finally {
            server.close();
        }
    }

    private static void awaitUnchecked(
            CountDownLatch latch) {
        try {
            if (!latch.await(
                    2,
                    TimeUnit.SECONDS)) {
                throw new AssertionError(
                        "Race barrier timed out");
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new AssertionError(error);
        }
    }

    private static void assertRejectedAfterHandshake(
            RpcEndpoint endpoint,
            RpcTransportOptions options,
            byte[] malformed) throws Exception {
        try (Socket socket =
                     new Socket(
                             endpoint.host(),
                             endpoint.port())) {
            socket.setSoTimeout(2000);
            performRawHandshake(
                    socket,
                    options);
            socket.getOutputStream().write(
                    malformed);
            socket.getOutputStream().flush();

            assertProtocolGoAway(
                    readFrame(socket));
        }
    }

    private static void performRawHandshake(
            Socket socket,
            RpcTransportOptions options) throws Exception {
        RpcFrame hello = new RpcFrame(
                RpcMessageType.HELLO,
                RpcCodecIds.CONTROL,
                RpcStatus.OK,
                0L,
                0,
                0,
                Map.of(),
                RpcHandshakeCodec.encode(
                        options.capabilities()));
        socket.getOutputStream().write(
                RpcProtocolCodec.encode(hello));
        socket.getOutputStream().flush();

        RpcFrame ack = RpcProtocolCodec.decode(
                readFrame(socket));
        assertEquals(
                RpcMessageType.HELLO_ACK,
                ack.messageType());
        assertEquals(
                RpcStatus.OK,
                ack.status());
    }

    private static void assertProtocolGoAway(
            byte[] bytes) {
        RpcFrame frame =
                RpcProtocolCodec.decode(bytes);
        assertEquals(
                RpcMessageType.GO_AWAY,
                frame.messageType());
        assertEquals(
                RpcStatus.BAD_REQUEST,
                frame.status());
    }

    private static byte[] readFrame(
            Socket socket) throws Exception {
        byte[] header =
                socket.getInputStream()
                        .readNBytes(
                                RpcProtocolCodec.HEADER_LENGTH);
        assertEquals(
                RpcProtocolCodec.HEADER_LENGTH,
                header.length);
        int frameLength =
                RpcProtocolCodec.expectedFrameLength(
                        header);
        byte[] frame =
                java.util.Arrays.copyOf(
                        header,
                        frameLength);
        int bodyLength =
                frameLength
                        - RpcProtocolCodec.HEADER_LENGTH;
        byte[] body =
                socket.getInputStream()
                        .readNBytes(bodyLength);
        assertEquals(
                bodyLength,
                body.length);
        System.arraycopy(
                body,
                0,
                frame,
                RpcProtocolCodec.HEADER_LENGTH,
                bodyLength);
        return frame;
    }

    private static RpcTransportOptions haOptions() {
        return new RpcTransportOptions(
                32,
                1024 * 1024,
                1024 * 1024,
                Duration.ofSeconds(2),
                Duration.ofSeconds(2),
                Set.of(RpcCodecIds.FORY_NATIVE),
                1,
                Duration.ofMillis(50),
                Duration.ofMillis(100),
                Duration.ofMillis(10),
                Duration.ofMillis(50));
    }

    private static byte[] awaitResponse(
            VertxRpcTransportClient client,
            RpcEndpoint endpoint,
            Duration timeout) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        Throwable lastFailure = null;
        while (System.nanoTime() < deadline) {
            try {
                return client.request(
                                target(endpoint),
                                request(),
                                Duration.ofMillis(500))
                        .toCompletableFuture()
                        .join().frame();
            } catch (RuntimeException error) {
                lastFailure = error;
                Thread.sleep(25L);
            }
        }
        throw new AssertionError(
                "RPC client did not reconnect before timeout",
                lastFailure);
    }

    private static RpcTransportOptions options(
            Set<Byte> codecs,
            int connections) {
        return new RpcTransportOptions(
                32,
                1024 * 1024,
                1024 * 1024,
                Duration.ofSeconds(2),
                codecs,
                connections);
    }

    private static RpcTarget target(RpcEndpoint endpoint) {
        return new RpcTarget(new ApplicationKey("tls-tests", "provider"), endpoint);
    }

    private static byte[] request() {
        return RpcProtocolCodec.encode(new RpcFrame(
                RpcMessageType.REQUEST,
                RpcCodecIds.FORY_NATIVE,
                RpcStatus.OK,
                0L,
                100,
                200,
                Map.of(),
                new byte[] {1, 2, 3}));
    }

    private static byte[] response(
            byte[] requestBytes,
            byte[] payload) {
        RpcFrame request =
                RpcProtocolCodec.decode(requestBytes);
        return RpcProtocolCodec.encode(new RpcFrame(
                RpcMessageType.RESPONSE,
                request.codec(),
                RpcStatus.OK,
                request.requestId(),
                request.serviceId(),
                request.methodId(),
                Map.of(),
                payload));
    }


    private static byte[] readUntilClosed(Socket socket)
            throws Exception {
        ByteArrayOutputStream output =
                new ByteArrayOutputStream();
        byte[] buffer = new byte[256];
        for (;;) {
            int read = socket.getInputStream().read(buffer);
            if (read < 0) {
                return output.toByteArray();
            }
            output.write(buffer, 0, read);
        }
    }

    private static int findFreePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
