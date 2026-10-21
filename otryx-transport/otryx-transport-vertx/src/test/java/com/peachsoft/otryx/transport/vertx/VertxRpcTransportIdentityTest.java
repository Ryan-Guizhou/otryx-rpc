package com.peachsoft.otryx.transport.vertx;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.peachsoft.otryx.api.ApplicationKey;
import com.peachsoft.otryx.api.RpcAuthenticationException;
import com.peachsoft.otryx.api.RpcEndpoint;
import com.peachsoft.otryx.api.RpcTarget;
import com.peachsoft.otryx.api.RpcUnavailableException;
import com.peachsoft.otryx.observability.RpcConnectionCloseReason;
import com.peachsoft.otryx.observability.RpcConnectionRole;
import com.peachsoft.otryx.observability.RpcObserver;
import com.peachsoft.otryx.observability.RpcSecurityMode;
import com.peachsoft.otryx.protocol.RpcMessageType;
import com.peachsoft.otryx.protocol.RpcProtocolCodec;
import com.peachsoft.otryx.transport.RpcTransportIdentityOptions;
import com.peachsoft.otryx.transport.RpcTransportOptions;
import com.peachsoft.otryx.transport.RpcTransportResponse;
import java.nio.file.Files;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * 真实 TLS 连接上的目标绑定、有限身份及惰性启动回归。
 *
 * @Author OpenAI
 * @Version 1.0.0-SNAPSHOT
 * @CreateTime 2026/10/19 11:34
 */
@Timeout(20)
class VertxRpcTransportIdentityTest {

    @Test
    void preparedTransportMustStartFromFrozenBytesAfterFilesDisappear() throws Exception {
        try (TlsTestCertificates certs = TlsTestCertificates.create()) {
            Instant until = Instant.now().plusSeconds(3600);
            VertxRpcTransportServer server = new VertxRpcTransportServer(TlsTestSupport.options(certs, false, until));
            VertxRpcTransportClient client = new VertxRpcTransportClient(TlsTestSupport.options(certs, true, until));
            try {
                assertInactive(server);
                assertInactive(client);
                Files.delete(certs.serverCertificate);
                Files.delete(certs.serverPrivateKey);
                Files.delete(certs.clientCertificate);
                Files.delete(certs.clientPrivateKey);
                Files.delete(certs.caCertificate);
                assertEquals(TlsTestCertificates.PROVIDER,
                        server.securityDescriptor().localIdentity().orElseThrow().application());
                assertEquals(TlsTestCertificates.CONSUMER,
                        client.securityDescriptor().localIdentity().orElseThrow().application());
                assertInactive(server);
                assertInactive(client);
                int port = start(server, new AtomicInteger());
                assertPayload(client.request(TlsTestSupport.target(port),
                        TlsTestSupport.request(), Duration.ofSeconds(5)));
            } finally {
                client.close();
                server.close();
            }
        }
    }

    @Test
    void closeAndDrainBeforeActivationMustNotCreateRuntime() throws Exception {
        VertxRpcTransportServer server = new VertxRpcTransportServer(RpcTransportOptions.DEFAULT);
        VertxRpcTransportClient client = new VertxRpcTransportClient(RpcTransportOptions.DEFAULT);
        try {
            client.close();
            client.close();
            assertInstanceOf(RpcUnavailableException.class,
                    failure(client.request(TlsTestSupport.target(1),
                            TlsTestSupport.request(), Duration.ofSeconds(1))));
            server.drain(Duration.ofSeconds(1)).toCompletableFuture().get(1, TimeUnit.SECONDS);
            assertInstanceOf(RpcUnavailableException.class, failure(server.start(new RpcEndpoint("127.0.0.1", 1),
                    (remote, bytes) -> CompletableFuture.completedFuture(TlsTestSupport.response(bytes)))));
            assertInactive(client);
            assertInactive(server);
        } finally {
            client.close();
            server.close();
        }
    }

    @Test
    void fullTargetMustSeparatePoolsBeforeAnyApplicationBytes() throws Exception {
        try (TlsTestCertificates certs = TlsTestCertificates.create()) {
            Instant until = Instant.now().plusSeconds(3600);
            AtomicInteger calls = new AtomicInteger();
            VertxRpcTransportServer server = new VertxRpcTransportServer(TlsTestSupport.options(certs, false, until));
            VertxRpcTransportClient client = new VertxRpcTransportClient(TlsTestSupport.options(certs, true, until));
            try {
                int port = start(server, calls);
                RpcTarget target = TlsTestSupport.target(port);
                assertPayload(client.request(target, TlsTestSupport.request(), Duration.ofSeconds(5)));
                RpcTarget other = new RpcTarget(new ApplicationKey("tls-tests", "other-provider"), target.endpoint());
                assertReason(RpcAuthenticationException.Reason.APPLICATION_MISMATCH,
                        failure(client.request(other, TlsTestSupport.request(), Duration.ofSeconds(5))));
                assertEquals(1, calls.get());
                assertEquals(2, client.connectionGroupCount());
                assertPayload(client.request(target, TlsTestSupport.request(), Duration.ofSeconds(5)));
                assertEquals(2, calls.get());
            } finally {
                client.close();
                server.close();
            }
        }
    }

    @Test
    void wrongProviderNamespaceMustFailBeforeHelloOrBusiness() throws Exception {
        try (TlsTestCertificates certs = TlsTestCertificates.create();
                SSLServerSocket listener = (SSLServerSocket) TlsTestSupport.rawContext(certs, false)
                        .getServerSocketFactory().createServerSocket(0)) {
            listener.setNeedClientAuth(true);
            listener.setSoTimeout(TlsTestSupport.TIMEOUT_MILLIS);
            Instant until = Instant.now().plusSeconds(3600);
            RpcTransportOptions clientOptions = TlsTestSupport.options(certs, true, until);
            clientOptions = clientOptions.withSecurity(clientOptions.security().withIdentity(
                    new RpcTransportIdentityOptions(TlsTestCertificates.CONSUMER, Set.of(
                            TlsTestCertificates.binding(certs.clientCertificate, TlsTestCertificates.CONSUMER, until),
                            TlsTestCertificates.binding(certs.serverCertificate,
                                    new ApplicationKey("other-namespace",
                                            TlsTestCertificates.PROVIDER.applicationName()), until)))));
            CompletableFuture<Integer> ended = new CompletableFuture<>();
            Thread peer = Thread.ofVirtual().unstarted(() -> {
                try (SSLSocket socket = (SSLSocket) listener.accept()) {
                    socket.setSoTimeout(TlsTestSupport.TIMEOUT_MILLIS);
                    socket.startHandshake();
                    ended.complete(socket.getInputStream().read());
                } catch (Throwable error) {
                    ended.completeExceptionally(error);
                }
            });
            VertxRpcTransportClient client = new VertxRpcTransportClient(clientOptions);
            try {
                peer.start();
                assertReason(RpcAuthenticationException.Reason.NAMESPACE_MISMATCH,
                        failure(client.request(TlsTestSupport.target(listener.getLocalPort()),
                                TlsTestSupport.request(), Duration.ofSeconds(5))));
                assertEquals(-1, ended.get(5, TimeUnit.SECONDS));
                client.evictIdleGroups(System.nanoTime() + TimeUnit.MINUTES.toNanos(6));
                assertEquals(0, client.connectionGroupCount());
            } finally {
                client.close();
                listener.close();
                peer.join(TlsTestSupport.TIMEOUT_MILLIS);
                assertFalse(peer.isAlive());
            }
        }
    }

    @Test
    void unboundProviderMustFailBeforeHelloOrBusiness() throws Exception {
        try (TlsTestCertificates certs = TlsTestCertificates.create()) {
            Instant until = Instant.now().plusSeconds(3600);
            AtomicInteger calls = new AtomicInteger();
            RpcTransportOptions clientOptions = TlsTestSupport.options(certs, true, until);
            clientOptions = clientOptions.withSecurity(clientOptions.security().withIdentity(
                    new RpcTransportIdentityOptions(
                    TlsTestCertificates.CONSUMER, Set.of(TlsTestCertificates.binding(certs.clientCertificate,
                            TlsTestCertificates.CONSUMER, until)))));
            VertxRpcTransportServer server = new VertxRpcTransportServer(TlsTestSupport.options(certs, false, until));
            VertxRpcTransportClient client = new VertxRpcTransportClient(clientOptions);
            try {
                int port = start(server, calls);
                assertReason(RpcAuthenticationException.Reason.IDENTITY_UNBOUND,
                        failure(client.request(TlsTestSupport.target(port),
                                TlsTestSupport.request(), Duration.ofSeconds(5))));
                assertEquals(0, calls.get());
            } finally {
                client.close();
                server.close();
            }
        }
    }

    @Test
    void wrongConsumerNamespaceMustFailLocallyWithoutInventingRemoteCertificateFailure() throws Exception {
        try (TlsTestCertificates certs = TlsTestCertificates.create()) {
            Instant until = Instant.now().plusSeconds(3600);
            CompletableFuture<Throwable> rejected = new CompletableFuture<>();
            RpcObserver observer = new RpcObserver() {
                @Override
                public void onTlsHandshakeCompleted(RpcConnectionRole role, RpcEndpoint endpoint, RpcSecurityMode mode,
                        long durationNanos, Throwable error) {
                    if (role == RpcConnectionRole.SERVER && error != null) {
                        rejected.complete(error);
                    }
                }
            };
            RpcTransportOptions serverOptions = TlsTestSupport.options(certs, false, until);
            serverOptions = serverOptions.withSecurity(serverOptions.security().withIdentity(
                    new RpcTransportIdentityOptions(
                    TlsTestCertificates.PROVIDER, Set.of(
                            TlsTestCertificates.binding(certs.serverCertificate, TlsTestCertificates.PROVIDER, until),
                            TlsTestCertificates.binding(certs.clientCertificate,
                                    new ApplicationKey("other-namespace", "consumer"), until)))))
                    .withObserver(observer);
            AtomicInteger calls = new AtomicInteger();
            VertxRpcTransportServer server = new VertxRpcTransportServer(serverOptions);
            VertxRpcTransportClient client = new VertxRpcTransportClient(TlsTestSupport.options(certs, true, until));
            try {
                int port = start(server, calls);
                assertInstanceOf(RpcUnavailableException.class,
                        failure(client.request(TlsTestSupport.target(port),
                                TlsTestSupport.request(), Duration.ofSeconds(5))));
                assertReason(RpcAuthenticationException.Reason.NAMESPACE_MISMATCH, rejected.get(5, TimeUnit.SECONDS));
                assertEquals(0, calls.get());
            } finally {
                client.close();
                server.close();
            }
        }
    }

    @Test
    void exactPeerBindingExpiryAndWallRollbackMustPreventReuse() throws Exception {
        try (TlsTestCertificates certs = TlsTestCertificates.create()) {
            TlsTestSupport.IdentityTime time = new TlsTestSupport.IdentityTime(Instant.now());
            Instant until = time.instant().plusSeconds(60);
            RpcTransportOptions clientOptions = peerExpiry(certs, true, until);
            AtomicInteger calls = new AtomicInteger();
            VertxRpcTransportServer server = new VertxRpcTransportServer(
                    TlsTestSupport.options(certs, false, until.plusSeconds(3600)));
            VertxRpcTransportClient client = new VertxRpcTransportClient(clientOptions, time, time::nanos,
                    TlsTestSupport.DEFERRED_SWEEP_MILLIS);
            try {
                int port = start(server, calls);
                RpcTransportResponse response = client.request(TlsTestSupport.target(port), TlsTestSupport.request(),
                        Duration.ofSeconds(5)).toCompletableFuture().get(5, TimeUnit.SECONDS);
                assertEquals(until, response.peer().validUntil().orElseThrow());
                time.advance(Duration.ofSeconds(60));
                time.rollbackWall(Duration.ofSeconds(30));
                assertFalse(response.peer().validAt(time.instant(), time.nanos()));
                assertReason(RpcAuthenticationException.Reason.IDENTITY_EXPIRED,
                        failure(client.request(TlsTestSupport.target(port),
                                TlsTestSupport.request(), Duration.ofSeconds(5))));
                assertEquals(1, calls.get());
            } finally {
                client.close();
                server.close();
            }
        }
    }

    @Test
    void certificateNotAfterMustCapALongerBinding() throws Exception {
        try (TlsTestCertificates certs = TlsTestCertificates.create()) {
            TlsTestSupport.IdentityTime time = new TlsTestSupport.IdentityTime(Instant.now());
            Instant notAfter = TlsTestCertificates.readCertificate(certs.serverCertificate).getNotAfter().toInstant();
            Instant until = notAfter.plus(Duration.ofDays(1));
            VertxRpcTransportServer server = new VertxRpcTransportServer(TlsTestSupport.options(certs, false, until));
            VertxRpcTransportClient client = new VertxRpcTransportClient(TlsTestSupport.options(certs, true, until),
                    time, time::nanos, TlsTestSupport.DEFERRED_SWEEP_MILLIS);
            try {
                int port = start(server, new AtomicInteger());
                RpcTransportResponse reply = client.request(TlsTestSupport.target(port), TlsTestSupport.request(),
                        Duration.ofSeconds(5)).toCompletableFuture().get(5, TimeUnit.SECONDS);
                assertEquals(notAfter, reply.peer().validUntil().orElseThrow());
                time.advance(Duration.between(time.instant(), notAfter));
                assertReason(RpcAuthenticationException.Reason.IDENTITY_EXPIRED,
                        failure(client.request(TlsTestSupport.target(port),
                                TlsTestSupport.request(), Duration.ofSeconds(5))));
            } finally {
                client.close();
                server.close();
            }
        }
    }

    @Test
    void receivedResponseAfterPeerExpiryMustFailAndReleasePendingOwnership() throws Exception {
        try (TlsTestCertificates certs = TlsTestCertificates.create()) {
            TlsTestSupport.IdentityTime time = new TlsTestSupport.IdentityTime(Instant.now());
            Instant until = time.instant().plusSeconds(60);
            CompletableFuture<byte[]> accepted = new CompletableFuture<>();
            CompletableFuture<byte[]> provider = new CompletableFuture<>();
            CompletableFuture<Throwable> closed = new CompletableFuture<>();
            VertxRpcTransportServer server = new VertxRpcTransportServer(
                    TlsTestSupport.options(certs, false, until.plusSeconds(3600)));
            VertxRpcTransportClient client = new VertxRpcTransportClient(
                    peerExpiry(certs, true, until).withObserver(closeObserver(RpcConnectionRole.CLIENT, closed)),
                    time, time::nanos, TlsTestSupport.DEFERRED_SWEEP_MILLIS);
            try {
                int port = server.start(new RpcEndpoint("127.0.0.1", 0), (remote, bytes) -> {
                    accepted.complete(bytes);
                    return provider;
                }).toCompletableFuture().get(5, TimeUnit.SECONDS).port();
                CompletionStage<RpcTransportResponse> call = client.request(TlsTestSupport.target(port),
                        TlsTestSupport.request(), Duration.ofSeconds(5));
                byte[] request = accepted.get(5, TimeUnit.SECONDS);
                time.advance(Duration.ofSeconds(60));
                provider.complete(TlsTestSupport.response(request));
                assertReason(RpcAuthenticationException.Reason.IDENTITY_EXPIRED, failure(call));
                assertReason(RpcAuthenticationException.Reason.IDENTITY_EXPIRED, closed.get(5, TimeUnit.SECONDS));
                client.evictIdleGroups(System.nanoTime() + TimeUnit.MINUTES.toNanos(6));
                assertEquals(0, client.connectionGroupCount());
            } finally {
                provider.cancel(true);
                client.close();
                server.close();
            }
        }
    }

    @Test
    void lateBusinessResultMustNotWriteSuccessAfterServerPeerExpiry() throws Exception {
        try (TlsTestCertificates certs = TlsTestCertificates.create()) {
            TlsTestSupport.IdentityTime time = new TlsTestSupport.IdentityTime(Instant.now());
            Instant until = time.instant().plusSeconds(60);
            CompletableFuture<byte[]> accepted = new CompletableFuture<>();
            CompletableFuture<byte[]> provider = new CompletableFuture<>();
            CompletableFuture<Throwable> closed = new CompletableFuture<>();
            VertxRpcTransportServer server = new VertxRpcTransportServer(
                    peerExpiry(certs, false, until).withObserver(closeObserver(RpcConnectionRole.SERVER, closed)),
                    time, time::nanos, new BoundedJdkSslEngineOptions(), TlsTestSupport.DEFERRED_SWEEP_MILLIS);
            VertxRpcTransportClient client = new VertxRpcTransportClient(
                    TlsTestSupport.options(certs, true, until.plusSeconds(3600)));
            try {
                int port = server.start(new RpcEndpoint("127.0.0.1", 0), (remote, bytes) -> {
                    accepted.complete(bytes);
                    return provider;
                }).toCompletableFuture().get(5, TimeUnit.SECONDS).port();
                CompletionStage<RpcTransportResponse> call = client.request(TlsTestSupport.target(port),
                        TlsTestSupport.request(), Duration.ofSeconds(5));
                byte[] request = accepted.get(5, TimeUnit.SECONDS);
                time.advance(Duration.ofSeconds(60));
                provider.complete(TlsTestSupport.response(request));
                assertReason(RpcAuthenticationException.Reason.IDENTITY_EXPIRED, closed.get(5, TimeUnit.SECONDS));
                assertInstanceOf(RpcUnavailableException.class, failure(call));
                assertEquals(0, server.admittedConnections());
                assertEquals(0, server.admittedHandshakes());
                assertEquals(0L, server.bufferedResponseBytes());
                assertEquals(0L, server.bufferedPartialBytes());
            } finally {
                provider.cancel(true);
                client.close();
                server.close();
            }
        }
    }

    @Test
    void idleServerMustExpireWithoutNegotiatingHeartbeat() throws Exception {
        try (TlsTestCertificates certs = TlsTestCertificates.create()) {
            TlsTestSupport.IdentityTime time = new TlsTestSupport.IdentityTime(Instant.now());
            Instant until = time.instant().plusSeconds(60);
            CompletableFuture<Throwable> closed = new CompletableFuture<>();
            VertxRpcTransportServer server = new VertxRpcTransportServer(
                    peerExpiry(certs, false, until).withObserver(closeObserver(RpcConnectionRole.SERVER, closed)),
                    time, time::nanos);
            try {
                int port = start(server, new AtomicInteger());
                try (SSLSocket raw = TlsTestSupport.connect(TlsTestSupport.rawContext(certs, true), port, "TLSv1.3")) {
                    TlsTestSupport.hello(raw);
                    time.advance(Duration.ofSeconds(60));
                    assertReason(RpcAuthenticationException.Reason.IDENTITY_EXPIRED, closed.get(5, TimeUnit.SECONDS));
                    assertEquals(-1, raw.getInputStream().read());
                    assertEquals(0, server.admittedConnections());
                    assertEquals(0, server.admittedHandshakes());
                }
            } finally {
                server.close();
            }
        }
    }

    @Test
    void idleClientMustExpireWithoutNegotiatingHeartbeat() throws Exception {
        try (TlsTestCertificates certs = TlsTestCertificates.create();
                SSLServerSocket listener = (SSLServerSocket) TlsTestSupport.rawContext(certs, false)
                        .getServerSocketFactory().createServerSocket(0)) {
            listener.setNeedClientAuth(true);
            listener.setSoTimeout(TlsTestSupport.TIMEOUT_MILLIS);
            CompletableFuture<Integer> ended = new CompletableFuture<>();
            Thread peer = Thread.ofVirtual().start(() -> {
                try (SSLSocket socket = (SSLSocket) listener.accept()) {
                    socket.setSoTimeout(TlsTestSupport.TIMEOUT_MILLIS);
                    socket.startHandshake();
                    assertEquals(RpcMessageType.HELLO,
                            RpcProtocolCodec.decode(TlsTestSupport.readFrame(socket.getInputStream())).messageType());
                    socket.getOutputStream().write(TlsTestSupport.hello(RpcMessageType.HELLO_ACK,
                            TlsTestSupport.withoutHeartbeat()));
                    byte[] request = TlsTestSupport.readFrame(socket.getInputStream());
                    socket.getOutputStream().write(TlsTestSupport.response(request));
                    ended.complete(socket.getInputStream().read());
                } catch (Throwable error) {
                    ended.completeExceptionally(error);
                }
            });
            TlsTestSupport.IdentityTime time = new TlsTestSupport.IdentityTime(Instant.now());
            CompletableFuture<Throwable> closed = new CompletableFuture<>();
            VertxRpcTransportClient client = new VertxRpcTransportClient(peerExpiry(certs, true,
                    time.instant().plusSeconds(60)).withObserver(closeObserver(RpcConnectionRole.CLIENT, closed)),
                    time, time::nanos);
            try {
                assertPayload(client.request(TlsTestSupport.target(listener.getLocalPort()), TlsTestSupport.request(),
                        Duration.ofSeconds(5)));
                time.advance(Duration.ofSeconds(60));
                assertReason(RpcAuthenticationException.Reason.IDENTITY_EXPIRED, closed.get(5, TimeUnit.SECONDS));
                assertEquals(-1, ended.get(5, TimeUnit.SECONDS));
            } finally {
                client.close();
                listener.close();
                peer.join(TlsTestSupport.TIMEOUT_MILLIS);
                assertFalse(peer.isAlive());
            }
        }
    }

    private static RpcTransportOptions peerExpiry(TlsTestCertificates certs, boolean client, Instant peerUntil)
            throws Exception {
        Instant localUntil = peerUntil.plusSeconds(3600);
        RpcTransportOptions options = TlsTestSupport.options(certs, client, localUntil);
        return options.withSecurity(options.security().withIdentity(new RpcTransportIdentityOptions(
                client ? TlsTestCertificates.CONSUMER : TlsTestCertificates.PROVIDER, Set.of(
                        TlsTestCertificates.binding(certs.serverCertificate, TlsTestCertificates.PROVIDER,
                                client ? peerUntil : localUntil),
                        TlsTestCertificates.binding(certs.clientCertificate, TlsTestCertificates.CONSUMER,
                                client ? localUntil : peerUntil)))));
    }

    private static int start(VertxRpcTransportServer server, AtomicInteger calls) throws Exception {
        return server.start(new RpcEndpoint("127.0.0.1", 0), (remote, bytes) -> {
            calls.incrementAndGet();
            return CompletableFuture.completedFuture(TlsTestSupport.response(bytes));
        }).toCompletableFuture().get(5, TimeUnit.SECONDS).port();
    }

    private static void assertPayload(CompletionStage<RpcTransportResponse> result) throws Exception {
        RpcTransportResponse response = result.toCompletableFuture().get(5, TimeUnit.SECONDS);
        assertArrayEquals(new byte[] {7}, RpcProtocolCodec.decode(response.frame()).payload());
        assertTrue(response.peer().authenticated());
        assertEquals(TlsTestCertificates.PROVIDER, response.peer().application().orElseThrow());
    }

    private static Throwable failure(CompletionStage<?> result) {
        return assertThrows(ExecutionException.class,
                () -> result.toCompletableFuture().get(5, TimeUnit.SECONDS)).getCause();
    }

    private static void assertReason(RpcAuthenticationException.Reason reason, Throwable error) {
        assertEquals(reason, assertInstanceOf(RpcAuthenticationException.class, error).reason());
        assertNull(error.getCause());
        assertEquals(0, error.getSuppressed().length);
    }

    private static RpcObserver closeObserver(RpcConnectionRole expected, CompletableFuture<Throwable> closed) {
        return new RpcObserver() {
            @Override
            public void onConnectionClosed(RpcConnectionRole role, RpcEndpoint endpoint,
                    RpcConnectionCloseReason reason, Throwable error) {
                if (role == expected) {
                    closed.complete(error);
                }
            }
        };
    }

    private static void assertInactive(Object transport) throws Exception {
        var runtime = transport.getClass().getDeclaredField("vertx");
        runtime.setAccessible(true);
        assertNull(runtime.get(transport));
    }
}
