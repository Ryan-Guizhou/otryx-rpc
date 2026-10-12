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
        byte[] header = socket.getInputStream().readNBytes(
                RpcProtocolCodec.HEADER_LENGTH);
        assertEquals(RpcProtocolCodec.HEADER_LENGTH, header.length);
        int fullLength = RpcProtocolCodec.expectedFrameLength(header);
        byte[] content = socket.getInputStream().readNBytes(
                fullLength - header.length);
        byte[] frame = new byte[fullLength];
        System.arraycopy(header, 0, frame, 0, header.length);
        System.arraycopy(content, 0, frame, header.length, content.length);
        RpcFrame ack = RpcProtocolCodec.decode(frame);
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
