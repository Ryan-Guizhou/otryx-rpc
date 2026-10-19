package com.peachsoft.otryx.transport.vertx;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.peachsoft.otryx.api.ApplicationKey;
import com.peachsoft.otryx.api.RpcAuthenticationException;
import com.peachsoft.otryx.api.RpcEndpoint;
import com.peachsoft.otryx.api.RpcTarget;
import com.peachsoft.otryx.api.RpcPeerIdentity;
import com.peachsoft.otryx.api.RpcStatus;
import com.peachsoft.otryx.codec.RpcCodecIds;
import com.peachsoft.otryx.observability.RpcCertificateReloadOutcome;
import com.peachsoft.otryx.observability.RpcConnectionRole;
import com.peachsoft.otryx.observability.RpcObserver;
import com.peachsoft.otryx.observability.RpcSecurityMode;
import com.peachsoft.otryx.protocol.RpcFrame;
import com.peachsoft.otryx.protocol.RpcMessageType;
import com.peachsoft.otryx.protocol.RpcProtocolCodec;
import com.peachsoft.otryx.transport.RpcRequestHandler;
import com.peachsoft.otryx.transport.RpcTransportOptions;
import com.peachsoft.otryx.transport.RpcTransportResponse;
import com.peachsoft.otryx.transport.RpcTransportSecurityOptions;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * Vert.x TLS/mTLS 真实网络集成测试。
 *
 * @Author Ryan
 * @Version 1.0.0
 * @CreateTime 2026/9/29 15:06
 */
class VertxRpcTransportTlsTest {

    @Test
    void tlsShouldCompleteRpcWithTrustedServer()
            throws Exception {
        try (TlsTestCertificates certs =
                     TlsTestCertificates.create()) {
            int port = findFreePort();
            RpcTransportOptions serverOptions =
                    baseOptions().withSecurity(
                            serverSecurity(
                                    certs,
                                    RpcSecurityMode.TLS));
            RpcTransportOptions clientOptions =
                    baseOptions().withSecurity(
                            clientSecurity(
                                    certs,
                                    RpcSecurityMode.TLS,
                                    certs.caCertificate.toString(),
                                    true));
            assertRoundTrip(
                    port,
                    serverOptions,
                    clientOptions,
                    "localhost");
        }
    }

    @Test
    void tlsShouldRejectWrongCa() throws Exception {
        try (TlsTestCertificates certs =
                     TlsTestCertificates.create()) {
            int port = findFreePort();
            AtomicInteger handshakeFailures =
                    new AtomicInteger();
            RpcObserver observer = new RpcObserver() {
                @Override
                public void onTlsHandshakeCompleted(
                        RpcConnectionRole role,
                        RpcEndpoint endpoint,
                        RpcSecurityMode mode,
                        long durationNanos,
                        Throwable error) {
                    if (role == RpcConnectionRole.CLIENT
                            && error != null) {
                        handshakeFailures.incrementAndGet();
                    }
                }
            };
            VertxRpcTransportServer server =
                    server(
                            port,
                            baseOptions().withSecurity(
                                    serverSecurity(
                                            certs,
                                            RpcSecurityMode.TLS)));
            VertxRpcTransportClient client =
                    new VertxRpcTransportClient(
                            baseOptions()
                                    .withObserver(observer)
                                    .withSecurity(
                                            clientSecurity(
                                                    certs,
                                                    RpcSecurityMode.TLS,
                                                    certs.wrongCaCertificate
                                                            .toString(),
                                                    true)));
            try {
                CompletionException error =
                        assertThrows(
                                CompletionException.class,
                                () -> client.request(
                                                target(new RpcEndpoint(
                                                        "localhost",
                                                        port)),
                                                request(),
                                                Duration.ofSeconds(2))
                                        .toCompletableFuture()
                                        .join().frame());
                assertCertificateRejected(error);
                assertTrue(await(
                        Duration.ofSeconds(2),
                        () -> handshakeFailures.get() >= 1));
            } finally {
                client.close();
                server.close();
            }
        }
    }

    @Test
    void tlsShouldRejectHostnameMismatch()
            throws Exception {
        try (TlsTestCertificates certs =
                     TlsTestCertificates.create()) {
            int port = findFreePort();
            VertxRpcTransportServer server =
                    server(
                            port,
                            baseOptions().withSecurity(
                                    serverSecurity(
                                            certs,
                                            RpcSecurityMode.TLS)));
            VertxRpcTransportClient client =
                    new VertxRpcTransportClient(
                            baseOptions().withSecurity(
                                    clientSecurity(
                                            certs,
                                            RpcSecurityMode.TLS,
                                            certs.caCertificate.toString(),
                                            true)));
            try {
                CompletionException error = assertThrows(
                        CompletionException.class,
                        () -> client.request(
                                        target(new RpcEndpoint(
                                                "127.0.0.1",
                                                port)),
                                        request(),
                                        Duration.ofSeconds(2))
                                .toCompletableFuture()
                                .join().frame());
                assertCertificateRejected(error);
            } finally {
                client.close();
                server.close();
            }
        }
    }

    @Test
    void mtlsShouldAuthenticateBothSides()
            throws Exception {
        try (TlsTestCertificates certs =
                     TlsTestCertificates.create()) {
            int port = findFreePort();
            RpcTransportSecurityOptions serverSecurity =
                    new RpcTransportSecurityOptions(
                            RpcSecurityMode.MTLS,
                            certs.serverCertificate.toString(),
                            certs.serverPrivateKey.toString(),
                            certs.caCertificate.toString(),
                            true,
                            Duration.ofSeconds(2),
                            Duration.ofMillis(50),
                            Duration.ofDays(7), java.util.Optional.empty());
            RpcTransportSecurityOptions clientSecurity =
                    new RpcTransportSecurityOptions(
                            RpcSecurityMode.MTLS,
                            certs.clientCertificate.toString(),
                            certs.clientPrivateKey.toString(),
                            certs.caCertificate.toString(),
                            true,
                            Duration.ofSeconds(2),
                            Duration.ofMillis(50),
                            Duration.ofDays(7), java.util.Optional.empty());

            assertRoundTrip(
                    port,
                    baseOptions().withSecurity(serverSecurity),
                    baseOptions().withSecurity(clientSecurity),
                    "localhost");
        }
    }

    @Test
    void mtlsServerShouldRejectClientWithoutCertificate()
            throws Exception {
        try (TlsTestCertificates certs =
                     TlsTestCertificates.create()) {
            int port = findFreePort();
            RpcTransportSecurityOptions serverSecurity =
                    new RpcTransportSecurityOptions(
                            RpcSecurityMode.MTLS,
                            certs.serverCertificate.toString(),
                            certs.serverPrivateKey.toString(),
                            certs.caCertificate.toString(),
                            true,
                            Duration.ofSeconds(2),
                            Duration.ofMillis(50),
                            Duration.ofDays(7), java.util.Optional.empty());
            VertxRpcTransportServer server =
                    server(
                            port,
                            baseOptions().withSecurity(
                                    serverSecurity));
            VertxRpcTransportClient client =
                    new VertxRpcTransportClient(
                            baseOptions().withSecurity(
                                    clientSecurity(
                                            certs,
                                            RpcSecurityMode.TLS,
                                            certs.caCertificate.toString(),
                                            true)));
            try {
                assertThrows(
                        CompletionException.class,
                        () -> client.request(
                                        target(new RpcEndpoint(
                                                "localhost",
                                                port)),
                                        request(),
                                        Duration.ofSeconds(2))
                                .toCompletableFuture()
                                .join().frame());
            } finally {
                client.close();
                server.close();
            }
        }
    }

    @Test
    void expiredServerCertificateShouldFailFast()
            throws Exception {
        try (TlsTestCertificates certs =
                     TlsTestCertificates.create()) {
            RpcTransportSecurityOptions security =
                    new RpcTransportSecurityOptions(
                            RpcSecurityMode.TLS,
                            certs.expiredServerCertificate
                                    .toString(),
                            certs.expiredServerPrivateKey
                                    .toString(),
                            "",
                            true,
                            Duration.ofSeconds(2),
                            Duration.ofMillis(50),
                            Duration.ofDays(7), java.util.Optional.empty());

            assertThrows(
                    IllegalArgumentException.class,
                    () -> new VertxRpcTransportServer(
                            baseOptions()
                                    .withSecurity(security)));
        }
    }

    @Test
    void tlsShouldSurviveHeartbeatAndReconnectAfterProviderRestart()
            throws Exception {
        try (TlsTestCertificates certs =
                     TlsTestCertificates.create()) {
            int port = findFreePort();
            RpcTransportSecurityOptions serverSecurity =
                    serverSecurity(
                            certs,
                            RpcSecurityMode.TLS);
            RpcTransportSecurityOptions clientSecurity =
                    clientSecurity(
                            certs,
                            RpcSecurityMode.TLS,
                            certs.caCertificate.toString(),
                            true);
            RpcTransportOptions serverOptions =
                    haOptions().withSecurity(serverSecurity);
            RpcTransportOptions clientOptions =
                    haOptions().withSecurity(clientSecurity);
            RpcEndpoint endpoint =
                    new RpcEndpoint("localhost", port);
            VertxRpcTransportClient client =
                    new VertxRpcTransportClient(
                            clientOptions);
            VertxRpcTransportServer first =
                    server(port, serverOptions);
            try {
                byte[] initial = client.request(
                                target(endpoint),
                                request(),
                                Duration.ofSeconds(2))
                        .toCompletableFuture()
                        .join().frame();
                assertArrayEquals(
                        new byte[] {7},
                        RpcProtocolCodec.decode(initial)
                                .payload());

                Thread.sleep(250L);

                byte[] afterHeartbeat = client.request(
                                target(endpoint),
                                request(),
                                Duration.ofSeconds(2))
                        .toCompletableFuture()
                        .join().frame();
                assertArrayEquals(
                        new byte[] {7},
                        RpcProtocolCodec.decode(
                                        afterHeartbeat)
                                .payload());

                first.close();
                Thread.sleep(150L);

                VertxRpcTransportServer replacement =
                        server(port, serverOptions);
                try {
                    byte[] recovered = awaitResponse(
                            client,
                            endpoint,
                            Duration.ofSeconds(4));
                    assertArrayEquals(
                            new byte[] {7},
                            RpcProtocolCodec.decode(
                                            recovered)
                                    .payload());
                } finally {
                    replacement.close();
                }
            } finally {
                client.close();
                first.close();
            }
        }
    }

    @Test
    void validCertificateReloadShouldBeObserved()
            throws Exception {
        try (TlsTestCertificates certs =
                     TlsTestCertificates.create()) {
            int port = findFreePort();
            AtomicReference<RpcCertificateReloadOutcome>
                    outcome = new AtomicReference<>();
            RpcObserver observer = reloadObserver(outcome);
            RpcTransportOptions serverOptions =
                    baseOptions()
                            .withObserver(observer)
                            .withSecurity(
                                    serverSecurity(
                                            certs,
                                            RpcSecurityMode.TLS));
            VertxRpcTransportServer server =
                    server(port, serverOptions);
            try {
                certs.rotateServerMaterial();
                assertTrue(await(
                        Duration.ofSeconds(3),
                        () -> outcome.get()
                                == RpcCertificateReloadOutcome.SUCCESS));
            } finally {
                server.close();
            }
        }
    }

    @Test
    void invalidCertificateReloadShouldKeepOldMaterial()
            throws Exception {
        try (TlsTestCertificates certs =
                     TlsTestCertificates.create()) {
            int port = findFreePort();
            AtomicReference<RpcCertificateReloadOutcome>
                    outcome = new AtomicReference<>();
            RpcObserver observer = reloadObserver(outcome);
            RpcTransportOptions serverOptions =
                    baseOptions()
                            .withObserver(observer)
                            .withSecurity(
                                    serverSecurity(
                                            certs,
                                            RpcSecurityMode.TLS));
            VertxRpcTransportServer server =
                    server(port, serverOptions);
            VertxRpcTransportClient first =
                    new VertxRpcTransportClient(
                            baseOptions().withSecurity(
                                    clientSecurity(
                                            certs,
                                            RpcSecurityMode.TLS,
                                            certs.caCertificate.toString(),
                                            true)));
            try {
                byte[] initial = first.request(
                                target(new RpcEndpoint(
                                        "localhost",
                                        port)),
                                request(),
                                Duration.ofSeconds(2))
                        .toCompletableFuture()
                        .join().frame();
                assertArrayEquals(
                        new byte[] {7},
                        RpcProtocolCodec.decode(initial)
                                .payload());

                certs.breakServerCertificate();
                assertTrue(await(
                        Duration.ofSeconds(3),
                        () -> outcome.get()
                                == RpcCertificateReloadOutcome.FAILURE));

                first.close();
                VertxRpcTransportClient second =
                        new VertxRpcTransportClient(
                                baseOptions().withSecurity(
                                        clientSecurity(
                                                certs,
                                                RpcSecurityMode.TLS,
                                                certs.caCertificate
                                                        .toString(),
                                                true)));
                try {
                    byte[] recovered = second.request(
                                    target(new RpcEndpoint(
                                            "localhost",
                                            port)),
                                    request(),
                                    Duration.ofSeconds(2))
                            .toCompletableFuture()
                            .join().frame();
                    assertArrayEquals(
                            new byte[] {7},
                            RpcProtocolCodec.decode(recovered)
                                    .payload());
                } finally {
                    second.close();
                }
            } finally {
                first.close();
                server.close();
            }
        }
    }

    @Test
    void mtlsMustDeliverVerifiedCertificateIdentity() throws Exception {
        try (TlsTestCertificates certs = TlsTestCertificates.create()) {
            int port = findFreePort();
            RpcTransportOptions serverOptions = baseOptions().withSecurity(
                    serverSecurity(certs, RpcSecurityMode.MTLS)
                            .withIdentity(certs.identities(TlsTestCertificates.PROVIDER)));
            RpcTransportOptions clientOptions = baseOptions().withSecurity(
                    clientSecurity(
                            certs,
                            RpcSecurityMode.MTLS,
                            certs.caCertificate.toString(),
                            true).withIdentity(certs.identities(TlsTestCertificates.CONSUMER)));
            AtomicReference<RpcPeerIdentity> observed = new AtomicReference<>();
            VertxRpcTransportServer server = new VertxRpcTransportServer(serverOptions);
            VertxRpcTransportClient client = new VertxRpcTransportClient(clientOptions);
            try {
                server.start(new RpcEndpoint("0.0.0.0", port), new RpcRequestHandler() {
                    @Override
                    public CompletionStage<byte[]> handle(
                            RpcEndpoint remote, byte[] frame) {
                        throw new AssertionError("Peer-aware callback was not used");
                    }

                    @Override
                    public CompletionStage<byte[]> handle(
                            RpcEndpoint remote, RpcPeerIdentity peer, byte[] frame) {
                        observed.set(peer);
                        return CompletableFuture.completedFuture(
                                response(frame, new byte[] {7}));
                    }
                }).toCompletableFuture().join();
                RpcTransportResponse reply = client.request(
                                target(new RpcEndpoint("localhost", port)),
                                request(),
                                Duration.ofSeconds(2))
                        .toCompletableFuture().get(3, TimeUnit.SECONDS);
                assertArrayEquals(new byte[] {7}, RpcProtocolCodec.decode(reply.frame()).payload());
                RpcPeerIdentity peer = observed.get();
                assertTrue(peer != null && peer.authenticated());
                assertEquals(TlsTestCertificates.CONSUMER, peer.application().orElseThrow());
                assertEquals(TlsTestCertificates.PROVIDER, reply.peer().application().orElseThrow());
                assertEquals(TlsTestCertificates.binding(certs.clientCertificate, TlsTestCertificates.CONSUMER,
                        peer.certificateBinding().orElseThrow().validUntil()), peer.certificateBinding().orElseThrow());
            } finally {
                client.close();
                server.close();
            }
        }
    }

    @Test
    void tlsWithoutClientCertificateMustRemainAnonymous() throws Exception {
        try (TlsTestCertificates certs = TlsTestCertificates.create()) {
            int port = findFreePort();
            RpcTransportOptions serverOptions = baseOptions().withSecurity(
                    serverSecurity(certs, RpcSecurityMode.TLS));
            RpcTransportOptions clientOptions = baseOptions().withSecurity(
                    clientSecurity(
                            certs,
                            RpcSecurityMode.TLS,
                            certs.caCertificate.toString(),
                            true));
            AtomicReference<RpcPeerIdentity> observed = new AtomicReference<>();
            VertxRpcTransportServer server = new VertxRpcTransportServer(serverOptions);
            VertxRpcTransportClient client = new VertxRpcTransportClient(clientOptions);
            try {
                server.start(new RpcEndpoint("0.0.0.0", port), new RpcRequestHandler() {
                    @Override
                    public CompletionStage<byte[]> handle(
                            RpcEndpoint remote, byte[] frame) {
                        throw new AssertionError("Peer-aware callback was not used");
                    }

                    @Override
                    public CompletionStage<byte[]> handle(
                            RpcEndpoint remote, RpcPeerIdentity peer, byte[] frame) {
                        observed.set(peer);
                        return CompletableFuture.completedFuture(
                                response(frame, new byte[] {7}));
                    }
                }).toCompletableFuture().join();
                client.request(
                                target(new RpcEndpoint("localhost", port)),
                                request(),
                                Duration.ofSeconds(2))
                        .toCompletableFuture().get(3, TimeUnit.SECONDS).frame();
                assertEquals(RpcPeerIdentity.anonymous(), observed.get());
            } finally {
                client.close();
                server.close();
            }
        }
    }

    private static RpcObserver reloadObserver(
            AtomicReference<RpcCertificateReloadOutcome> outcome) {
        return new RpcObserver() {
            @Override
            public void onCertificateReloadCompleted(
                    RpcSecurityMode mode,
                    RpcCertificateReloadOutcome value,
                    long durationNanos,
                    Throwable error) {
                outcome.set(value);
            }
        };
    }

    private static RpcTransportSecurityOptions serverSecurity(
            TlsTestCertificates certs,
            RpcSecurityMode mode) {
        return new RpcTransportSecurityOptions(
                mode,
                certs.serverCertificate.toString(),
                certs.serverPrivateKey.toString(),
                mode == RpcSecurityMode.MTLS
                        ? certs.caCertificate.toString()
                        : "",
                true,
                Duration.ofSeconds(2),
                Duration.ofMillis(50),
                Duration.ofDays(7), java.util.Optional.empty());
    }

    private static RpcTransportSecurityOptions clientSecurity(
            TlsTestCertificates certs,
            RpcSecurityMode mode,
            String trustPath,
            boolean hostnameVerification) {
        return new RpcTransportSecurityOptions(
                mode,
                mode == RpcSecurityMode.MTLS
                        ? certs.clientCertificate.toString()
                        : "",
                mode == RpcSecurityMode.MTLS
                        ? certs.clientPrivateKey.toString()
                        : "",
                trustPath,
                hostnameVerification,
                Duration.ofSeconds(2),
                Duration.ofMillis(50),
                Duration.ofDays(7), java.util.Optional.empty());
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
        long deadline =
                System.nanoTime() + timeout.toNanos();
        Throwable lastFailure = null;
        while (System.nanoTime() < deadline) {
            try {
                return client.request(
                                target(endpoint),
                                request(),
                                Duration.ofMillis(750))
                        .toCompletableFuture()
                        .join().frame();
            } catch (RuntimeException error) {
                lastFailure = error;
                Thread.sleep(25L);
            }
        }
        throw new AssertionError(
                "TLS RPC client did not recover before timeout",
                lastFailure);
    }

    private static RpcTransportOptions baseOptions() {
        return new RpcTransportOptions(
                32,
                1024 * 1024,
                1024 * 1024,
                Duration.ofSeconds(2),
                Duration.ofSeconds(2),
                Set.of(RpcCodecIds.FORY_NATIVE),
                1,
                Duration.ofSeconds(30),
                Duration.ofSeconds(10),
                Duration.ofMillis(10),
                Duration.ofMillis(100));
    }

    private static VertxRpcTransportServer server(
            int port,
            RpcTransportOptions options) {
        VertxRpcTransportServer server =
                new VertxRpcTransportServer(options);
        server.start(
                        new RpcEndpoint(
                                "0.0.0.0",
                                port),
                        (remote, requestBytes) ->
                                java.util.concurrent.CompletableFuture
                                        .completedFuture(
                                                response(
                                                        requestBytes,
                                                        new byte[] {7})))
                .toCompletableFuture()
                .join();
        return server;
    }

    private static void assertRoundTrip(
            int port,
            RpcTransportOptions serverOptions,
            RpcTransportOptions clientOptions,
            String host) {
        VertxRpcTransportServer server =
                server(port, serverOptions);
        VertxRpcTransportClient client =
                new VertxRpcTransportClient(
                        clientOptions);
        try {
            byte[] response = client.request(
                            target(new RpcEndpoint(
                                    host,
                                    port)),
                            request(),
                            Duration.ofSeconds(2))
                    .toCompletableFuture()
                    .join().frame();
            assertArrayEquals(
                    new byte[] {7},
                    RpcProtocolCodec.decode(response)
                            .payload());
        } finally {
            client.close();
            server.close();
        }
    }

    private static void assertCertificateRejected(CompletionException failed) {
        RpcAuthenticationException error = assertInstanceOf(RpcAuthenticationException.class, failed.getCause());
        assertEquals(RpcAuthenticationException.Reason.CERTIFICATE_REJECTED, error.reason());
        assertNull(error.getCause());
        assertEquals(0, error.getSuppressed().length);
    }

    private static RpcTarget target(RpcEndpoint endpoint) {
        return new RpcTarget(new ApplicationKey("tls-tests", "provider"), endpoint);
    }

    private static byte[] request() {
        return RpcProtocolCodec.encode(
                new RpcFrame(
                        RpcMessageType.REQUEST,
                        RpcCodecIds.FORY_NATIVE,
                        RpcStatus.OK,
                        0L,
                        100,
                        200,
                        Map.of(),
                        new byte[] {1}));
    }

    private static byte[] response(
            byte[] requestBytes,
            byte[] payload) {
        RpcFrame request =
                RpcProtocolCodec.decode(
                        requestBytes);
        return RpcProtocolCodec.encode(
                new RpcFrame(
                        RpcMessageType.RESPONSE,
                        request.codec(),
                        RpcStatus.OK,
                        request.requestId(),
                        request.serviceId(),
                        request.methodId(),
                        Map.of(),
                        payload));
    }

    private static boolean await(
            Duration timeout,
            CheckedBoolean condition)
            throws Exception {
        long deadline =
                System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.get()) {
                return true;
            }
            Thread.sleep(25L);
        }
        return condition.get();
    }

    private static int findFreePort()
            throws Exception {
        try (ServerSocket socket =
                     new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    @FunctionalInterface
    private interface CheckedBoolean {
        boolean get() throws Exception;
    }
}
