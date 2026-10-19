package com.peachsoft.otryx.transport.vertx;

import com.peachsoft.otryx.api.RpcEndpoint;
import com.peachsoft.otryx.api.RpcStatus;
import com.peachsoft.otryx.api.RpcTarget;
import com.peachsoft.otryx.codec.RpcCodecIds;
import com.peachsoft.otryx.observability.RpcObserver;
import com.peachsoft.otryx.observability.RpcSecurityMode;
import com.peachsoft.otryx.protocol.RpcConnectionCapabilities;
import com.peachsoft.otryx.protocol.RpcFrame;
import com.peachsoft.otryx.protocol.RpcHandshakeCodec;
import com.peachsoft.otryx.protocol.RpcMessageType;
import com.peachsoft.otryx.protocol.RpcProtocolCodec;
import com.peachsoft.otryx.transport.RpcTransportOptions;
import com.peachsoft.otryx.transport.RpcTransportSecurityOptions;
import io.vertx.core.net.SSLOptions;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;

/**
 * 真实 TLS 回归共用的有限 Socket、Wire 与身份时钟夹具。
 *
 * @Author OpenAI
 * @Version 1.0.0-SNAPSHOT
 * @CreateTime 2026/10/19 11:34
 */
final class TlsTestSupport {
    static final int TIMEOUT_MILLIS = 5000;
    static final long DEFERRED_SWEEP_MILLIS = 60_000L;

    private TlsTestSupport() {
    }

    static RpcTransportOptions options(TlsTestCertificates certs, boolean client, Instant validUntil) throws Exception {
        RpcTransportSecurityOptions security = new RpcTransportSecurityOptions(RpcSecurityMode.MTLS,
                (client ? certs.clientCertificate : certs.serverCertificate).toString(),
                (client ? certs.clientPrivateKey : certs.serverPrivateKey).toString(), certs.caCertificate.toString(),
                true, Duration.ofSeconds(5), Duration.ofMillis(50), Duration.ofDays(7),
                Optional.of(certs.identities(client ? TlsTestCertificates.CONSUMER : TlsTestCertificates.PROVIDER,
                        validUntil)));
        return new RpcTransportOptions(8, 1024 * 1024, 1024 * 1024, Duration.ofSeconds(5))
                .withSecurity(security);
    }

    static RpcTarget target(int port) {
        return new RpcTarget(TlsTestCertificates.PROVIDER, new RpcEndpoint("localhost", port));
    }

    static byte[] request() {
        return RpcProtocolCodec.encode(new RpcFrame(RpcMessageType.REQUEST, RpcCodecIds.FORY_NATIVE, RpcStatus.OK,
                1L, 100, 200, Map.of(), new byte[] {1}));
    }

    static byte[] response(byte[] requestBytes) {
        RpcFrame request = RpcProtocolCodec.decode(requestBytes);
        return RpcProtocolCodec.encodeResponse(request.codec(), RpcStatus.OK, request.requestId(), request.serviceId(),
                request.methodId(), new byte[] {7});
    }

    static SSLContext rawContext(TlsTestCertificates certs, boolean client) throws Exception {
        RpcTransportOptions options = options(certs, client, Instant.now().plusSeconds(3600));
        SSLOptions ssl = VertxTlsSupport.prepare(options.security(), client, RpcObserver.noop(), options.codecIds(),
                Clock.systemUTC(), System::nanoTime).sslOptions();
        SSLContext context = SSLContext.getInstance("TLS", "SunJSSE");
        context.init(ssl.getKeyCertOptions().getKeyManagerFactory(null).getKeyManagers(),
                ssl.getTrustOptions().getTrustManagerFactory(null).getTrustManagers(), null);
        context.getClientSessionContext().setSessionCacheSize(BoundedJdkSslEngineOptions.SESSION_CACHE_ENTRIES);
        context.getClientSessionContext().setSessionTimeout(BoundedJdkSslEngineOptions.SESSION_TIMEOUT_SECONDS);
        context.getServerSessionContext().setSessionCacheSize(BoundedJdkSslEngineOptions.SESSION_CACHE_ENTRIES);
        context.getServerSessionContext().setSessionTimeout(BoundedJdkSslEngineOptions.SESSION_TIMEOUT_SECONDS);
        return context;
    }

    static SSLSocket connect(SSLContext context, int port, String protocol) throws Exception {
        Socket tcp = new Socket();
        tcp.connect(new java.net.InetSocketAddress("localhost", port), TIMEOUT_MILLIS);
        try {
            return connect(context, tcp, port, protocol);
        } catch (Exception error) {
            tcp.close();
            throw error;
        }
    }

    static SSLSocket connect(SSLContext context, Socket tcp, int port, String protocol) throws Exception {
        SSLSocket socket = (SSLSocket) context.getSocketFactory().createSocket(tcp, "localhost", port, true);
        socket.setSoTimeout(TIMEOUT_MILLIS);
        socket.setEnabledProtocols(new String[] {protocol});
        var parameters = socket.getSSLParameters();
        parameters.setEndpointIdentificationAlgorithm("HTTPS");
        socket.setSSLParameters(parameters);
        socket.startHandshake();
        return socket;
    }

    static RpcConnectionCapabilities withoutHeartbeat() {
        RpcConnectionCapabilities base = RpcTransportOptions.DEFAULT.capabilities();
        return new RpcConnectionCapabilities(base.protocolVersions(), base.codecIds(), base.compressionIds(), Set.of(),
                base.maxFrameBytes());
    }

    static byte[] hello(RpcMessageType type, RpcConnectionCapabilities capabilities) {
        return RpcProtocolCodec.encode(new RpcFrame(type, RpcCodecIds.CONTROL, RpcStatus.OK, 0L, 0, 0, Map.of(),
                RpcHandshakeCodec.encode(capabilities)));
    }

    static void hello(SSLSocket socket) throws Exception {
        socket.getOutputStream().write(hello(RpcMessageType.HELLO, withoutHeartbeat()));
        RpcFrame reply = RpcProtocolCodec.decode(readFrame(socket.getInputStream()));
        if (reply.messageType() != RpcMessageType.HELLO_ACK) {
            throw new IOException("TLS fixture did not receive HELLO_ACK");
        }
    }

    static byte[] readFrame(InputStream input) throws IOException {
        byte[] header = input.readNBytes(RpcProtocolCodec.HEADER_LENGTH);
        if (header.length != RpcProtocolCodec.HEADER_LENGTH) {
            throw new EOFException("TLS fixture connection ended before a frame header");
        }
        int length = RpcProtocolCodec.expectedFrameLength(header);
        if (length > 1024 * 1024) {
            throw new IOException("TLS fixture frame exceeded its finite limit");
        }
        byte[] frame = Arrays.copyOf(header, length);
        byte[] body = input.readNBytes(length - header.length);
        if (body.length != length - header.length) {
            throw new EOFException("TLS fixture connection ended before a frame body");
        }
        System.arraycopy(body, 0, frame, header.length, body.length);
        return frame;
    }

    static final class IdentityTime extends Clock {
        private final AtomicReference<TimePoint> value;

        IdentityTime(Instant now) {
            value = new AtomicReference<>(new TimePoint(now, 1_000_000_000L));
        }

        long nanos() {
            return value.get().nanos;
        }

        void advance(Duration duration) {
            value.updateAndGet(before -> new TimePoint(before.instant.plus(duration),
                    before.nanos + duration.toNanos()));
        }

        void rollbackWall(Duration duration) {
            value.updateAndGet(before -> new TimePoint(before.instant.minus(duration), before.nanos));
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            if (!ZoneOffset.UTC.equals(zone)) {
                throw new IllegalArgumentException("TLS identity fixture uses UTC");
            }
            return this;
        }

        @Override
        public Instant instant() {
            return value.get().instant;
        }

        private record TimePoint(Instant instant, long nanos) {
        }
    }
}
