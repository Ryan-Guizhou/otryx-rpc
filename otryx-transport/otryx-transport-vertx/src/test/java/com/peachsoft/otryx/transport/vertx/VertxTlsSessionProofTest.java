package com.peachsoft.otryx.transport.vertx;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.peachsoft.otryx.api.RpcAuthenticationException;
import com.peachsoft.otryx.api.RpcEndpoint;
import com.peachsoft.otryx.api.RpcPeerIdentity;
import com.peachsoft.otryx.observability.RpcCertificateReloadOutcome;
import com.peachsoft.otryx.observability.RpcConnectionCloseReason;
import com.peachsoft.otryx.observability.RpcConnectionRole;
import com.peachsoft.otryx.observability.RpcObserver;
import com.peachsoft.otryx.observability.RpcSecurityMode;
import com.peachsoft.otryx.protocol.RpcProtocolCodec;
import com.peachsoft.otryx.transport.RpcRequestHandler;
import com.peachsoft.otryx.transport.RpcTransportIdentityOptions;
import com.peachsoft.otryx.transport.RpcTransportOptions;
import io.netty.handler.ssl.ClientAuth;
import io.netty.handler.ssl.DelegatingSslContext;
import io.netty.handler.ssl.SslContext;
import io.vertx.core.net.TrustOptions;
import io.vertx.core.spi.tls.SslContextFactory;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSessionContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509ExtendedTrustManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * 实际 SunJSSE 握手、恢复与热重载期间的私有会话证明回归。
 *
 * <p>测试钩子只观察真实 engine 或在可信本地夹具中移除证明；不向生产
 * 校验器传入当前代次，也不阻塞 EventLoop 或 TrustManager。
 *
 * @Author OpenAI
 * @Version 1.0.0-SNAPSHOT
 * @CreateTime 2026/10/19 11:38
 */
@Timeout(30)
class VertxTlsSessionProofTest {
    private static final String PEER_PROOF = VertxTlsSupport.class.getName() + ".peer";

    @ParameterizedTest
    @ValueSource(strings = {"TLSv1.2", "TLSv1.3"})
    void realResumptionMustRetainOriginalPeerAnchorAndFiniteCache(String protocol) throws Exception {
        if ("TLSv1.2".equals(protocol)) {
            // JDK 21 的 TLS 1.2 空 ticket 扩展会走完整握手；在隔离客户端关闭该扩展，
            // 才能证明真实 session ID 恢复。生产及父 JVM 的属性保持原值。
            isolatedProbe(Tls12ResumptionProbe.class,
                    List.of("-Djdk.tls.client.enableSessionTicketExtension=false"), "TLS12_RESUMPTION_OK", 20);
        } else {
            verifyRealResumption(protocol);
        }
    }

    private void verifyRealResumption(String protocol) throws Exception {
        try (TlsTestCertificates certs = TlsTestCertificates.create()) {
            TlsTestSupport.IdentityTime time = new TlsTestSupport.IdentityTime(Instant.now());
            Instant peerUntil = time.instant().plusSeconds(60);
            RpcTransportOptions options = TlsTestSupport.options(certs, false, peerUntil.plusSeconds(3600));
            options = options.withSecurity(options.security().withIdentity(new RpcTransportIdentityOptions(
                    TlsTestCertificates.PROVIDER, Set.of(
                            TlsTestCertificates.binding(certs.serverCertificate, TlsTestCertificates.PROVIDER,
                                    peerUntil.plusSeconds(3600)),
                            TlsTestCertificates.binding(certs.clientCertificate, TlsTestCertificates.CONSUMER,
                                    peerUntil)))));
            BlockingQueue<RpcPeerIdentity> peers = new ArrayBlockingQueue<>(4);
            BlockingQueue<Throwable> rejected = new ArrayBlockingQueue<>(4);
            BlockingQueue<RpcConnectionCloseReason> closed = new ArrayBlockingQueue<>(4);
            TrackingState tracking = new TrackingState(protocol, false);
            VertxRpcTransportServer server = new VertxRpcTransportServer(options.withObserver(observer(rejected, closed,
                    new CompletableFuture<>())), time, time::nanos, new TrackingEngineOptions(tracking),
                    TlsTestSupport.DEFERRED_SWEEP_MILLIS);
            try {
                int port = server.start(new RpcEndpoint("127.0.0.1", 0), peerHandler(peers))
                        .toCompletableFuture().get(5, TimeUnit.SECONDS).port();
                SSLContext clientContext = TlsTestSupport.rawContext(certs, true);
                Object originalProof;
                long creationTime;
                try (SSLSocket first = TlsTestSupport.connect(clientContext, port, protocol)) {
                    exchange(first);
                    SSLEngine engine = requireEngine(tracking);
                    SSLSession session = engine.getSession();
                    assertEquals(protocol, session.getProtocol());
                    originalProof = session.getValue(PEER_PROOF);
                    assertNotNull(originalProof);
                    creationTime = session.getCreationTime();
                    RpcPeerIdentity peer = peers.poll(5, TimeUnit.SECONDS);
                    assertNotNull(peer);
                    assertEquals(TimeUnit.SECONDS.toNanos(60),
                            peer.remainingValidityNanos(time.instant(), time.nanos()));
                    assertEquals(1, tracking.trustChecks.get());
                    assertTrue(clientContext.getClientSessionContext().getIds().hasMoreElements());
                }
                assertNotNull(closed.poll(5, TimeUnit.SECONDS));
                time.advance(Duration.ofSeconds(30));
                time.rollbackWall(Duration.ofSeconds(30));
                try (SSLSocket resumed = TlsTestSupport.connect(clientContext, port, protocol)) {
                    exchange(resumed);
                    SSLSession session = requireEngine(tracking).getSession();
                    assertEquals(protocol, session.getProtocol());
                    assertSame(originalProof, session.getValue(PEER_PROOF));
                    assertEquals(creationTime, session.getCreationTime());
                    assertEquals(1, tracking.trustChecks.get(), "Resumption must skip a second trust validation");
                    RpcPeerIdentity peer = peers.poll(5, TimeUnit.SECONDS);
                    assertNotNull(peer);
                    assertEquals(TimeUnit.SECONDS.toNanos(30),
                            peer.remainingValidityNanos(time.instant(), time.nanos()));
                }
                assertNotNull(closed.poll(5, TimeUnit.SECONDS));
                time.advance(Duration.ofSeconds(30));
                try (SSLSocket expired = TlsTestSupport.connect(clientContext, port, protocol)) {
                    assertThrows(IOException.class, () -> TlsTestSupport.hello(expired));
                }
                assertReason(RpcAuthenticationException.Reason.IDENTITY_EXPIRED, rejected.poll(5, TimeUnit.SECONDS));
                assertEquals(1, tracking.trustChecks.get(), "Expired resumed identity must not acquire a new anchor");
                assertTrue(peers.isEmpty());
                assertTrue(tracking.copies.get() > 0, "The actual Vert.x options copy must retain the bounded factory");
                assertFinite(tracking);
            } finally {
                server.close();
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"TLSv1.2", "TLSv1.3"})
    void verifiedTlsWithMissingSessionProofMustFailClosed(String protocol) throws Exception {
        try (TlsTestCertificates certs = TlsTestCertificates.create()) {
            BlockingQueue<Throwable> rejected = new ArrayBlockingQueue<>(4);
            TrackingState tracking = new TrackingState(protocol, true);
            AtomicInteger business = new AtomicInteger();
            VertxRpcTransportServer server = new VertxRpcTransportServer(TlsTestSupport.options(certs, false,
                    Instant.now().plusSeconds(3600)).withObserver(observer(rejected, new ArrayBlockingQueue<>(4),
                            new CompletableFuture<>())), Clock.systemUTC(), System::nanoTime,
                    new TrackingEngineOptions(tracking));
            try {
                int port = server.start(new RpcEndpoint("127.0.0.1", 0), (remote, frame) -> {
                    business.incrementAndGet();
                    return CompletableFuture.completedFuture(TlsTestSupport.response(frame));
                }).toCompletableFuture().get(5, TimeUnit.SECONDS).port();
                try (SSLSocket socket = TlsTestSupport.connect(
                        TlsTestSupport.rawContext(certs, true), port, protocol)) {
                    assertThrows(IOException.class, () -> TlsTestSupport.hello(socket));
                }
                assertReason(RpcAuthenticationException.Reason.CERTIFICATE_REJECTED,
                        rejected.poll(5, TimeUnit.SECONDS));
                assertEquals(1, tracking.trustChecks.get(),
                        "CA validation really completed before proof removal");
                assertNull(requireEngine(tracking).getSession().getValue(PEER_PROOF));
                assertEquals(0, business.get());
            } finally {
                server.close();
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"TLSv1.2", "TLSv1.3"})
    void oldAllocatedEngineMustNotBorrowTrustOnlyReloadGeneration(String protocol) throws Exception {
        try (TlsTestCertificates certs = TlsTestCertificates.create()) {
            BlockingQueue<Throwable> rejected = new ArrayBlockingQueue<>(4);
            BlockingQueue<RpcPeerIdentity> peers = new ArrayBlockingQueue<>(4);
            CompletableFuture<Void> reloaded = new CompletableFuture<>();
            TrackingState tracking = new TrackingState(protocol, false);
            VertxRpcTransportServer server = new VertxRpcTransportServer(TlsTestSupport.options(certs, false,
                    Instant.now().plusSeconds(3600)).withObserver(
                            observer(rejected, new ArrayBlockingQueue<>(4), reloaded)),
                    Clock.systemUTC(), System::nanoTime, new TrackingEngineOptions(tracking));
            try {
                int port = server.start(new RpcEndpoint("127.0.0.1", 0), peerHandler(peers))
                        .toCompletableFuture().get(5, TimeUnit.SECONDS).port();
                SSLContext clientContext = TlsTestSupport.rawContext(certs, true);
                try (Socket tcp = new Socket()) {
                    tcp.connect(new InetSocketAddress("localhost", port), TlsTestSupport.TIMEOUT_MILLIS);
                    SSLEngine oldEngine = requireEngine(tracking);
                    assertEquals(0, tracking.trustChecks.get());
                    byte[] oldCertificate = Files.readAllBytes(certs.serverCertificate);
                    byte[] oldKey = Files.readAllBytes(certs.serverPrivateKey);
                    Files.writeString(certs.caCertificate, Files.readString(certs.caCertificate)
                            + Files.readString(certs.wrongCaCertificate));
                    reloaded.get(5, TimeUnit.SECONDS);
                    assertArrayEquals(oldCertificate, Files.readAllBytes(certs.serverCertificate));
                    assertArrayEquals(oldKey, Files.readAllBytes(certs.serverPrivateKey));
                    try (SSLSocket late = TlsTestSupport.connect(clientContext, tcp, port, protocol)) {
                        assertThrows(IOException.class, () -> TlsTestSupport.hello(late));
                    }
                    assertNotNull(oldEngine.getSession().getValue(PEER_PROOF));
                    assertReason(RpcAuthenticationException.Reason.SECURITY_CONTEXT_CHANGED,
                            rejected.poll(5, TimeUnit.SECONDS));
                    assertEquals(1, tracking.trustChecks.get());
                    assertTrue(peers.isEmpty(), "The old engine cannot enter the application handler");
                }
                try (SSLSocket fresh = TlsTestSupport.connect(clientContext, port, protocol)) {
                    exchange(fresh);
                    assertEquals(TlsTestCertificates.CONSUMER,
                            peers.poll(5, TimeUnit.SECONDS).application().orElseThrow());
                }
                // updateSSLOptions 安装 provider；SDK 在首个新连接创建实际 SSLContext。
                assertTrue(tracking.contexts.size() >= 2, "A different real TLS context must have served the new peer");
                assertTrue(tracking.trustChecks.get() >= 2, "The newly installed context must verify the peer itself");
                assertFinite(tracking);
            } finally {
                server.close();
            }
        }
    }

    @Test
    void finiteSessionCacheMustOverrideZeroHostDefaultsInAnIsolatedJvm() throws Exception {
        isolatedProbe(FiniteCacheProbe.class, List.of("-Djavax.net.ssl.sessionCacheSize=0",
                "-Djdk.tls.server.sessionTicketTimeout=0"), "FINITE_CACHE_OK", 10);
    }

    private static void isolatedProbe(Class<?> probe, List<String> vmOptions, String marker, int timeoutSeconds)
            throws Exception {
        Path reports = Path.of("target", "surefire-reports");
        Files.createDirectories(reports);
        Path output = Files.createTempFile(reports, "tls-" + probe.getSimpleName() + "-", ".log");
        Path result = Files.createTempFile(reports, "tls-" + probe.getSimpleName() + "-", ".result");
        Process process = null;
        try {
            String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
            List<String> command = new ArrayList<>();
            command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
            command.addAll(vmOptions);
            command.addAll(List.of("-cp", classpath, probe.getName(), result.toAbsolutePath().toString()));
            process = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(output.toFile()).start();
            assertTrue(process.waitFor(timeoutSeconds, TimeUnit.SECONDS),
                    "Isolated TLS probe exceeded its finite deadline; pid=" + process.pid()
                            + "; output=" + output + System.lineSeparator() + Files.readString(output));
            assertEquals(0, process.exitValue(), Files.readString(output));
            assertEquals(marker, Files.readString(result));
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
                assertTrue(process.waitFor(5, TimeUnit.SECONDS));
            }
        }
    }

    private static void publishProbeResult(String[] arguments, String marker) throws IOException {
        assertEquals(1, arguments.length, "The parent must supply one fresh private result file");
        Path result = Path.of(arguments[0]);
        assertEquals(0L, Files.size(result), "An isolated probe cannot reuse a completed result");
        Files.writeString(result, marker, StandardOpenOption.WRITE);
    }

    /** 隔离 TLS 1.2 客户端 ticket 属性，真实走有界 session ID 恢复。 */
    public static final class Tls12ResumptionProbe {
        private Tls12ResumptionProbe() {
        }

        /** 由外部有限进程预算约束，保留完整握手、恢复和过期断言。 */
        public static void main(String[] args) throws Exception {
            new VertxTlsSessionProofTest().verifyRealResumption("TLSv1.2");
            publishProbeResult(args, "TLS12_RESUMPTION_OK");
        }
    }

    private static void exchange(SSLSocket socket) throws Exception {
        TlsTestSupport.hello(socket);
        socket.getOutputStream().write(TlsTestSupport.request());
        assertArrayEquals(new byte[] {7}, RpcProtocolCodec.decode(TlsTestSupport.readFrame(socket.getInputStream()))
                .payload());
    }

    private static RpcRequestHandler peerHandler(BlockingQueue<RpcPeerIdentity> peers) {
        return new RpcRequestHandler() {
            @Override
            public CompletionStage<byte[]> handle(RpcEndpoint remote, byte[] frame) {
                throw new AssertionError("Peer-aware transport callback was not used");
            }

            @Override
            public CompletionStage<byte[]> handle(RpcEndpoint remote, RpcPeerIdentity peer, byte[] frame) {
                assertTrue(peers.offer(peer));
                return CompletableFuture.completedFuture(TlsTestSupport.response(frame));
            }
        };
    }

    private static RpcObserver observer(BlockingQueue<Throwable> rejected,
            BlockingQueue<RpcConnectionCloseReason> closed, CompletableFuture<Void> reloaded) {
        return new RpcObserver() {
            @Override
            public void onTlsHandshakeCompleted(RpcConnectionRole role, RpcEndpoint endpoint, RpcSecurityMode mode,
                    long durationNanos, Throwable error) {
                if (role == RpcConnectionRole.SERVER && error != null) {
                    assertTrue(rejected.offer(error));
                }
            }

            @Override
            public void onConnectionClosed(RpcConnectionRole role, RpcEndpoint endpoint,
                    RpcConnectionCloseReason reason, Throwable error) {
                if (role == RpcConnectionRole.SERVER) {
                    assertTrue(closed.offer(reason));
                }
            }

            @Override
            public void onCertificateReloadCompleted(RpcSecurityMode mode, RpcCertificateReloadOutcome outcome,
                    long durationNanos, Throwable error) {
                if (outcome == RpcCertificateReloadOutcome.SUCCESS) {
                    reloaded.complete(null);
                } else {
                    reloaded.completeExceptionally(error);
                }
            }
        };
    }

    private static SSLEngine requireEngine(TrackingState tracking) throws InterruptedException {
        SSLEngine engine = tracking.engines.poll(5, TimeUnit.SECONDS);
        assertNotNull(engine, "A real accepted TLS engine must be observed before proceeding");
        return engine;
    }

    private static void assertReason(RpcAuthenticationException.Reason reason, Throwable error) {
        assertEquals(reason, assertInstanceOf(RpcAuthenticationException.class, error).reason());
        assertNull(error.getCause());
        assertEquals(0, error.getSuppressed().length);
    }

    private static void assertFinite(TrackingState tracking) {
        assertFalse(tracking.contexts.isEmpty());
        tracking.contexts.forEach(VertxTlsSessionProofTest::assertFinite);
    }

    private static void assertFinite(SSLSessionContext context) {
        assertEquals(BoundedJdkSslEngineOptions.SESSION_CACHE_ENTRIES, context.getSessionCacheSize());
        assertEquals(BoundedJdkSslEngineOptions.SESSION_TIMEOUT_SECONDS, context.getSessionTimeout());
    }

    private static final class TrackingState {
        private final String protocol;
        private final boolean erasePeerProof;
        private final BlockingQueue<SSLEngine> engines = new ArrayBlockingQueue<>(16);
        private final List<SSLSessionContext> contexts = new CopyOnWriteArrayList<>();
        private final AtomicInteger trustChecks = new AtomicInteger();
        private final AtomicInteger copies = new AtomicInteger();

        private TrackingState(String protocol, boolean erasePeerProof) {
            this.protocol = protocol;
            this.erasePeerProof = erasePeerProof;
        }
    }

    private static final class TrackingEngineOptions extends BoundedJdkSslEngineOptions {
        private final TrackingState tracking;

        private TrackingEngineOptions(TrackingState tracking) {
            this.tracking = tracking;
        }

        private TrackingEngineOptions(TrackingEngineOptions other) {
            super(other);
            tracking = other.tracking;
            tracking.copies.incrementAndGet();
        }

        @Override
        public TrackingEngineOptions copy() {
            return new TrackingEngineOptions(this);
        }

        @Override
        public SslContextFactory sslContextFactory() {
            return new TrackingContextFactory(super.sslContextFactory(), tracking);
        }
    }

    private static final class TrackingContextFactory implements SslContextFactory {
        private SslContextFactory delegate;
        private final TrackingState tracking;

        private TrackingContextFactory(SslContextFactory delegate, TrackingState tracking) {
            this.delegate = delegate;
            this.tracking = tracking;
        }

        @Override
        public SslContextFactory useAlpn(boolean value) {
            delegate = delegate.useAlpn(value);
            return this;
        }

        @Override
        public SslContextFactory clientAuth(ClientAuth value) {
            delegate = delegate.clientAuth(value);
            return this;
        }

        @Override
        public SslContextFactory forClient(boolean value) {
            delegate = delegate.forClient(value);
            return this;
        }

        @Override
        public SslContextFactory keyMananagerFactory(KeyManagerFactory value) {
            delegate = delegate.keyMananagerFactory(value);
            return this;
        }

        @Override
        public SslContextFactory trustManagerFactory(TrustManagerFactory value) {
            try {
                X509ExtendedTrustManager manager = (X509ExtendedTrustManager) value.getTrustManagers()[0];
                TrustManagerFactory counted = TrustOptions.wrap(new TrackingTrustManager(manager, tracking))
                        .getTrustManagerFactory(null);
                delegate = delegate.trustManagerFactory(counted);
                return this;
            } catch (Exception error) {
                throw new IllegalStateException("TLS test trust factory setup failed", error);
            }
        }

        @Override
        public SslContextFactory enabledCipherSuites(Set<String> value) {
            delegate = delegate.enabledCipherSuites(value);
            return this;
        }

        @Override
        public SslContextFactory applicationProtocols(List<String> value) {
            delegate = delegate.applicationProtocols(value);
            return this;
        }

        @Override
        public SslContextFactory serverName(String value) {
            delegate = delegate.serverName(value);
            return this;
        }

        @Override
        public SslContext create() throws SSLException {
            SslContext actual = delegate.create();
            if (tracking.contexts.size() >= 16) {
                throw new SSLException("TLS test context observation capacity exhausted");
            }
            tracking.contexts.add(actual.sessionContext());
            return new DelegatingSslContext(actual) {
                @Override
                protected void initEngine(SSLEngine engine) {
                    engine.setEnabledProtocols(new String[] {tracking.protocol});
                    assertTrue(tracking.engines.offer(engine));
                }
            };
        }
    }

    private static final class TrackingTrustManager extends X509ExtendedTrustManager {
        private final X509ExtendedTrustManager delegate;
        private final TrackingState tracking;

        private TrackingTrustManager(X509ExtendedTrustManager delegate, TrackingState tracking) {
            this.delegate = delegate;
            this.tracking = tracking;
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType, SSLEngine engine)
                throws CertificateException {
            delegate.checkClientTrusted(chain, authType, engine);
            tracking.trustChecks.incrementAndGet();
            if (tracking.erasePeerProof) {
                engine.getHandshakeSession().removeValue(PEER_PROOF);
            }
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType, SSLEngine engine)
                throws CertificateException {
            delegate.checkServerTrusted(chain, authType, engine);
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType, Socket socket)
                throws CertificateException {
            delegate.checkClientTrusted(chain, authType, socket);
            tracking.trustChecks.incrementAndGet();
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType, Socket socket)
                throws CertificateException {
            delegate.checkServerTrusted(chain, authType, socket);
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            delegate.checkClientTrusted(chain, authType);
            tracking.trustChecks.incrementAndGet();
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            delegate.checkServerTrusted(chain, authType);
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return delegate.getAcceptedIssuers().clone();
        }
    }

    /** 隔离 JVM 入口，只创建 JDK SSLContext，不监听端口或创建 Vert.x。 */
    public static final class FiniteCacheProbe {
        private FiniteCacheProbe() {
        }

        /** 验证初始、复制和后续工厂创建不继承宿主的零容量、零 TTL。 */
        public static void main(String[] args) throws Exception {
            BoundedJdkSslEngineOptions initial = new BoundedJdkSslEngineOptions();
            for (BoundedJdkSslEngineOptions options : List.of(initial, initial.copy(), initial.copy())) {
                SSLSessionContext context = options.sslContextFactory().forClient(true).create().sessionContext();
                if (context.getSessionCacheSize() != BoundedJdkSslEngineOptions.SESSION_CACHE_ENTRIES
                        || context.getSessionTimeout() != BoundedJdkSslEngineOptions.SESSION_TIMEOUT_SECONDS) {
                    throw new AssertionError("Finite session retention was not installed");
                }
            }
            publishProbeResult(args, "FINITE_CACHE_OK");
        }
    }
}
