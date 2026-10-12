package com.peachsoft.otryx.transport.vertx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.peachsoft.otryx.api.RpcEndpoint;
import com.peachsoft.otryx.api.RpcStatus;
import com.peachsoft.otryx.codec.RpcCodecIds;
import com.peachsoft.otryx.protocol.RpcFrame;
import com.peachsoft.otryx.protocol.RpcHandshakeCodec;
import com.peachsoft.otryx.protocol.RpcMessageType;
import com.peachsoft.otryx.protocol.RpcProtocolCodec;
import com.peachsoft.otryx.transport.RpcTransportAdmissionOptions;
import com.peachsoft.otryx.transport.RpcTransportOptions;
import java.net.Socket;
import java.time.Duration;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.concurrent.locks.LockSupport;
import org.junit.jupiter.api.Test;

/**
 * 从真实 TCP Socket 验证 Transport 连接和 RPC HELLO 准入及额度回收。
 *
 * @Author OpenAI
 * @Version 1.0.0-SNAPSHOT
 * @CreateTime 2026/10/12 16:54
 */
class VertxRpcTransportAdmissionTest {

    @Test
    void connectionLimitMustRejectAndRecover() throws Exception {
        RpcTransportOptions options = options(2, 2);
        VertxRpcTransportServer server = new VertxRpcTransportServer(options);
        RpcEndpoint endpoint = start(server);
        try (Socket first = connect(endpoint);
             Socket second = connect(endpoint)) {
            handshake(first, options);
            handshake(second, options);
            await(() -> server.admittedConnections() == 2);

            try (Socket rejected = connect(endpoint)) {
                assertEquals(-1, rejected.getInputStream().read());
            }
            assertEquals(2, server.admittedConnections());

            first.close();
            await(() -> server.admittedConnections() == 1);

            try (Socket recovered = connect(endpoint)) {
                handshake(recovered, options);
                assertEquals(2, server.admittedConnections());
            }
        } finally {
            server.close();
        }
    }

    @Test
    void handshakeLimitMustReleaseOnFailure() throws Exception {
        RpcTransportOptions options = options(3, 1);
        VertxRpcTransportServer server = new VertxRpcTransportServer(options);
        RpcEndpoint endpoint = start(server);
        try (Socket stalled = connect(endpoint)) {
            await(() -> server.admittedHandshakes() == 1);
            try (Socket rejected = connect(endpoint)) {
                assertEquals(-1, rejected.getInputStream().read());
            }
            assertEquals(1, server.admittedHandshakes());

            // 关闭尚未发送 HELLO 的连接，释放全部握手所有权。
            stalled.close();
            await(() -> server.admittedHandshakes() == 0);
            try (Socket recovered = connect(endpoint)) {
                handshake(recovered, options);
                await(() -> server.admittedHandshakes() == 0);
            }
        } finally {
            server.close();
        }
    }

    @Test
    void partialFrameBudgetMustReleaseOnDisconnect() throws Exception {
        RpcTransportOptions options = RpcTransportOptions.DEFAULT.withAdmission(
                new RpcTransportAdmissionOptions(
                        3, 3, 1024L, Duration.ofSeconds(2)));
        VertxRpcTransportServer server = new VertxRpcTransportServer(options);
        RpcEndpoint endpoint = start(server);
        byte[] firstChunk = Arrays.copyOf(requestFrame(2048), 700);
        try (Socket first = connect(endpoint);
             Socket second = connect(endpoint)) {
            handshake(first, options);
            handshake(second, options);

            first.getOutputStream().write(firstChunk);
            first.getOutputStream().flush();
            await(() -> server.bufferedPartialBytes() == 700L);

            second.getOutputStream().write(firstChunk);
            second.getOutputStream().flush();
            assertEquals(RpcMessageType.GO_AWAY,
                    readFrame(second).messageType());
            await(() -> server.bufferedPartialBytes() == 700L);

            first.close();
            await(() -> server.bufferedPartialBytes() == 0L);
            try (Socket recovered = connect(endpoint)) {
                handshake(recovered, options);
                recovered.getOutputStream().write(firstChunk);
                recovered.getOutputStream().flush();
                await(() -> server.bufferedPartialBytes() == 700L);
            }
            await(() -> server.bufferedPartialBytes() == 0L);
        } finally {
            server.close();
        }
    }

    @Test
    void stalledPartialFrameMustExpire() throws Exception {
        RpcTransportOptions options = RpcTransportOptions.DEFAULT.withAdmission(
                new RpcTransportAdmissionOptions(
                        2, 2, 1024L, Duration.ofMillis(120)));
        VertxRpcTransportServer server = new VertxRpcTransportServer(options);
        RpcEndpoint endpoint = start(server);
        try (Socket stalled = connect(endpoint)) {
            handshake(stalled, options);
            stalled.getOutputStream().write(
                    Arrays.copyOf(requestFrame(2048), 64));
            stalled.getOutputStream().flush();
            await(() -> server.bufferedPartialBytes() == 64L);
            assertEquals(RpcMessageType.GO_AWAY,
                    readFrame(stalled).messageType());
            await(() -> server.bufferedPartialBytes() == 0L);
        } finally {
            server.close();
        }
    }

    private static byte[] requestFrame(int payloadSize) {
        return RpcProtocolCodec.encode(new RpcFrame(
                RpcMessageType.REQUEST,
                RpcCodecIds.FORY_NATIVE,
                RpcStatus.OK,
                91L,
                1,
                1,
                Map.of(),
                new byte[payloadSize]));
    }

    private static RpcFrame readFrame(Socket socket) throws Exception {
        byte[] header = socket.getInputStream().readNBytes(
                RpcProtocolCodec.HEADER_LENGTH);
        assertEquals(RpcProtocolCodec.HEADER_LENGTH, header.length);
        int length = RpcProtocolCodec.expectedFrameLength(header);
        byte[] payload = socket.getInputStream().readNBytes(
                length - header.length);
        assertEquals(length - header.length, payload.length);
        byte[] framed = new byte[length];
        System.arraycopy(header, 0, framed, 0, header.length);
        System.arraycopy(payload, 0, framed, header.length, payload.length);
        return RpcProtocolCodec.decode(framed);
    }

    private static RpcTransportOptions options(
            int maxConnections,
            int maxHandshakes) {
        return RpcTransportOptions.DEFAULT.withAdmission(
                new RpcTransportAdmissionOptions(
                        maxConnections,
                        maxHandshakes,
                        65536L,
                        Duration.ofSeconds(2)));
    }

    private static RpcEndpoint start(
            VertxRpcTransportServer server) throws Exception {
        return server.start(
                        new RpcEndpoint("127.0.0.1", 0),
                        (remote, frame) -> CompletableFuture.completedFuture(frame))
                .toCompletableFuture()
                .get(3, TimeUnit.SECONDS);
    }

    private static Socket connect(RpcEndpoint endpoint) throws Exception {
        Socket socket = new Socket(endpoint.host(), endpoint.port());
        socket.setSoTimeout(2000);
        return socket;
    }

    private static void handshake(
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
                RpcHandshakeCodec.encode(options.capabilities()));
        socket.getOutputStream().write(RpcProtocolCodec.encode(hello));
        socket.getOutputStream().flush();
        RpcFrame ack = readFrame(socket);
        assertEquals(RpcMessageType.HELLO_ACK, ack.messageType());
        assertEquals(RpcStatus.OK, ack.status());
    }

    private static void await(BooleanSupplier condition) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (!condition.getAsBoolean()
                && System.nanoTime() < deadline) {
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(2));
        }
        assertTrue(condition.getAsBoolean(), "Transport admission did not settle");
    }
}
