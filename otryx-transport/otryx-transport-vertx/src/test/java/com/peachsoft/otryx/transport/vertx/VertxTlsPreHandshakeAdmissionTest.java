package com.peachsoft.otryx.transport.vertx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.peachsoft.otryx.api.RpcEndpoint;
import com.peachsoft.otryx.api.RpcStatus;
import com.peachsoft.otryx.protocol.RpcProtocolCodec;
import com.peachsoft.otryx.transport.RpcTransportSecurityOptions;
import com.peachsoft.otryx.transport.RpcTransportAdmissionOptions;
import com.peachsoft.otryx.transport.RpcTransportOptions;
import java.lang.management.BufferPoolMXBean;
import java.lang.management.ManagementFactory;
import java.net.Socket;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.concurrent.locks.LockSupport;
import org.junit.jupiter.api.Test;

/**
 * 使用真实 TCP + TLS 证明在创建 SSL Engine 前取得额度，失败和排空均不泄漏租约。
 *
 * @Author OpenAI
 * @Version 1.0.0-SNAPSHOT
 * @CreateTime 2026/10/21 14:58
 */
class VertxTlsPreHandshakeAdmissionTest {
    private static final Duration AWAIT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration RPC_TIMEOUT = Duration.ofSeconds(4);
    private static final int SOCKET_TIMEOUT_MILLIS = 2000;

    @Test
    void stalledTlsHandshakeMustExhaustCapacityBeforeAClientHelloIsSent() throws Exception {
        try (TlsTestCertificates certificates = TlsTestCertificates.create()) {
            RpcTransportOptions serverOptions = limitedServer(certificates);
            VertxRpcTransportServer server = new VertxRpcTransportServer(serverOptions);
            RpcEndpoint endpoint = start(server);
            try (Socket stalled = rawSocket(endpoint)) {
                await(() -> server.pendingTlsHandshakes() == 1);
                assertEquals(1, server.admittedHandshakes());
                assertEquals(1, server.admittedConnections());

                try (Socket rejected = rawSocket(endpoint)) {
                    assertEquals(-1, rejected.getInputStream().read(),
                            "A second TCP client must be rejected before its TLS Engine is created");
                }
                assertEquals(1, server.admittedHandshakes());
                assertEquals(1, server.pendingTlsHandshakes());
                stalled.close();
                await(() -> server.admittedConnections() == 0
                        && server.admittedHandshakes() == 0
                        && server.pendingTlsHandshakes() == 0);

                RpcTransportOptions clientOptions =
                        TlsTestSupport.options(certificates, true, Instant.now().plusSeconds(3600));
                try (VertxRpcTransportClient client = new VertxRpcTransportClient(clientOptions)) {
                    byte[] frame = client.request(TlsTestSupport.target(endpoint.port()),
                                    TlsTestSupport.request(), RPC_TIMEOUT)
                            .toCompletableFuture().get(5, TimeUnit.SECONDS).frame();
                    assertEquals(RpcStatus.OK, RpcProtocolCodec.view(frame).status());
                }
            } finally {
                server.close();
            }
        }
    }

    @Test
    void drainingStalledPreTlsSocketMustReclaimResourcesWithoutBusinessInvocation() throws Exception {
        try (TlsTestCertificates certificates = TlsTestCertificates.create()) {
            RpcTransportOptions options = limitedServer(certificates);
            VertxRpcTransportServer server = new VertxRpcTransportServer(options);
            RpcEndpoint endpoint = start(server);
            try (Socket stalled = rawSocket(endpoint)) {
                await(() -> server.pendingTlsHandshakes() == 1);
                assertFalse(stalled.isClosed());
                server.drain(Duration.ofSeconds(3)).toCompletableFuture().get(5, TimeUnit.SECONDS);
                await(() -> server.pendingTlsHandshakes() == 0
                        && server.admittedHandshakes() == 0
                        && server.admittedConnections() == 0);
                // JSSE 可先发送 TLS Alert record (content type 21)，随后正常 EOF。
                int firstRecord = stalled.getInputStream().read();
                if (firstRecord >= 0) {
                    assertEquals(21, firstRecord, "Only a TLS alert is allowed before close");
                    byte[] trailingAlert = stalled.getInputStream().readNBytes(1024);
                    assertTrue(trailingAlert.length < 1024, "Alert must be followed by a bounded close");
                }
            } finally {
                server.close();
            }
        }
    }

    @Test
    void handshakeTimeoutAndConnectionFloodMustPreserveFiniteOwnershipAndReportResourceHighWater()
            throws Exception {
        try (TlsTestCertificates certificates = TlsTestCertificates.create()) {
            RpcTransportOptions base = limitedServer(certificates);
            RpcTransportSecurityOptions tls = base.security();
            RpcTransportSecurityOptions timed = new RpcTransportSecurityOptions(
                    tls.mode(), tls.certificatePath(), tls.privateKeyPath(), tls.trustCertificatePath(),
                    tls.hostnameVerification(), Duration.ofSeconds(2), tls.reloadInterval(),
                    tls.expiryWarningThreshold(), tls.identity());
            VertxRpcTransportServer server = new VertxRpcTransportServer(base.withSecurity(timed));
            RpcEndpoint endpoint = start(server);
            try (Socket stalled = rawSocket(endpoint)) {
                await(() -> server.pendingTlsHandshakes() == 1);
                ResourceSnapshot before = ResourceSnapshot.current();
                for (int attempt = 0; attempt < 16; attempt++) {
                    try (Socket rejected = rawSocket(endpoint)) {
                        assertEquals(-1, rejected.getInputStream().read());
                    }
                    assertTrue(server.admittedConnections() <= 2);
                    assertTrue(server.admittedHandshakes() <= 1);
                    assertTrue(server.pendingTlsHandshakes() <= 1);
                }
                ResourceSnapshot peak = ResourceSnapshot.current();
                await(() -> server.pendingTlsHandshakes() == 0
                        && server.admittedConnections() == 0
                        && server.admittedHandshakes() == 0);
                ResourceSnapshot settled = ResourceSnapshot.current();
                System.out.println("RPC TLS resource smoke: before=" + before
                        + ", underLoad=" + peak + ", settled=" + settled);
                assertTrue(peak.threadCount() > 0);
                assertTrue(settled.heapUsedBytes() >= 0L);
            } finally {
                server.close();
            }
        }
    }

    /** 测试辅助采样；只记录真实值，不声明是固定硬件正式容量验收。 */
    private record ResourceSnapshot(long heapUsedBytes, long directBytes, int threadCount,
                                    long openFileDescriptors) {

        private static ResourceSnapshot current() {
            long direct = 0L;
            for (BufferPoolMXBean bufferPool : ManagementFactory.getPlatformMXBeans(BufferPoolMXBean.class)) {
                if ("direct".equals(bufferPool.getName())) {
                    direct = bufferPool.getMemoryUsed();
                }
            }
            long descriptors = -1L;
            var operatingSystem = ManagementFactory.getOperatingSystemMXBean();
            if (operatingSystem instanceof com.sun.management.UnixOperatingSystemMXBean unix) {
                descriptors = unix.getOpenFileDescriptorCount();
            }
            return new ResourceSnapshot(ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed(),
                    direct, ManagementFactory.getThreadMXBean().getThreadCount(), descriptors);
        }
    }

    private static RpcTransportOptions limitedServer(TlsTestCertificates certificates) throws Exception {
        return TlsTestSupport.options(certificates, false, Instant.now().plusSeconds(3600))
                .withAdmission(new RpcTransportAdmissionOptions(
                        2, 1, 1024 * 1024L, Duration.ofSeconds(2), 1024 * 1024L));
    }

    private static RpcEndpoint start(VertxRpcTransportServer server) throws Exception {
        return server.start(new RpcEndpoint("localhost", 0),
                        (peer, request) -> CompletableFuture.completedFuture(TlsTestSupport.response(request)))
                .toCompletableFuture().get(5, TimeUnit.SECONDS);
    }

    private static Socket rawSocket(RpcEndpoint endpoint) throws Exception {
        Socket socket = new Socket("localhost", endpoint.port());
        socket.setSoTimeout(SOCKET_TIMEOUT_MILLIS);
        return socket;
    }

    private static void await(BooleanSupplier condition) {
        long deadline = System.nanoTime() + AWAIT_TIMEOUT.toNanos();
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(5));
        }
        assertTrue(condition.getAsBoolean(), "TLS ownership did not settle within bounded time");
    }
}
