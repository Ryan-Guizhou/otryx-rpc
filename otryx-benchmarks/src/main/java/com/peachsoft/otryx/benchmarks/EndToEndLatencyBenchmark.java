package com.peachsoft.otryx.benchmarks;

import com.peachsoft.otryx.api.RpcSecurityProfile;
import com.peachsoft.otryx.api.RpcEndpoint;
import com.peachsoft.otryx.codec.RpcCodecRegistry;
import com.peachsoft.otryx.core.OtryxRpcClient;
import com.peachsoft.otryx.core.OtryxRpcServer;
import com.peachsoft.otryx.spi.ExtensionLoader;
import com.peachsoft.otryx.transport.RpcTransportFactory;
import com.peachsoft.otryx.transport.RpcTransportOptions;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.net.NetClient;
import io.vertx.core.net.NetServer;
import io.vertx.core.net.NetSocket;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;

/**
 * Raw Vert.x loopback 与完整 OTRYX RPC 往返延迟基准。
 *
 * <p>两个方法都固定单并发和相同字符串负载。两者 AverageTime 的差值可作为
 * 当前环境下的 RPC Added Latency；正式结果仍需记录机器、JVM、payload 与 fork 参数。
 *
 * @Author Ryan
 * @Version 1.0.0
 * @CreateTime 2026/9/24 11:40
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 8, time = 1)
@Fork(2)
@Threads(1)
@State(Scope.Benchmark)
public class EndToEndLatencyBenchmark {

    private static final String PAYLOAD =
            "otryx-end-to-end-latency-payload";

    private Vertx rawVertx;
    private NetServer rawServer;
    private NetClient rawClient;
    private NetSocket rawSocket;
    private final AtomicReference<CompletableFuture<Void>> rawPending =
            new AtomicReference<>();

    private BenchmarkApplicationControlPlane controlPlane;
    private OtryxRpcServer rpcServer;
    private OtryxRpcClient rpcClient;
    private BenchmarkService rpcService;

    /**
     * 启动 Raw Vert.x echo 与完整 OTRYX RPC loopback。
     *
     * @throws Exception 端口或连接初始化失败
     */
    @Setup(Level.Trial)
    public void setup() throws Exception {
        setupRawVertx();
        setupOtryxRpc();
    }

    private void setupRawVertx() throws Exception {
        int port = freePort();
        rawVertx = Vertx.vertx();
        rawServer = rawVertx.createNetServer();
        rawServer.connectHandler(socket ->
                        socket.handler(socket::write))
                .listen(port, "127.0.0.1")
                .toCompletionStage()
                .toCompletableFuture()
                .join();

        rawClient = rawVertx.createNetClient();
        rawSocket = rawClient.connect(port, "127.0.0.1")
                .toCompletionStage()
                .toCompletableFuture()
                .join();
        rawSocket.handler(buffer -> {
            CompletableFuture<Void> pending =
                    rawPending.getAndSet(null);
            if (pending != null) {
                pending.complete(null);
            }
        });
    }

    private void setupOtryxRpc() throws Exception {
        int port = freePort();
        controlPlane = new BenchmarkApplicationControlPlane("benchmark");
        RpcCodecRegistry codecs = RpcCodecRegistry.fromSpi();
        RpcTransportFactory transportFactory =
                ExtensionLoader.getLoader(RpcTransportFactory.class)
                        .getExtension("vertx");
        RpcTransportOptions transportOptions =
                new RpcTransportOptions(
                        1024,
                        1024 * 1024,
                        1024 * 1024,
                        Duration.ofSeconds(3));

        rpcServer = OtryxRpcServer.builder()
                .securityProfile(RpcSecurityProfile.TRUSTED_DEVELOPMENT)
                .applicationMembership(controlPlane.provider())
                .transportServer(
                        transportFactory.createServer(transportOptions))
                .codecRegistry(codecs)
                .bindEndpoint(new RpcEndpoint("127.0.0.1", port))
                .maxConcurrent(4096)
                .build()
                .registerService(
                        BenchmarkService.class,
                        (BenchmarkService) value -> value,
                        "1.0.0",
                        "benchmark");
        rpcServer.start().toCompletableFuture().join();

        rpcClient = controlPlane.clientBuilder()
                .transportClient(
                        transportFactory.createClient(transportOptions))
                .codecRegistry(codecs)
                .timeout(Duration.ofSeconds(3))
                .build();
        rpcService = rpcClient.refer(
                BenchmarkService.class,
                "1.0.0",
                "benchmark");
        controlPlane.warmup(rpcClient, () -> rpcService.echo(PAYLOAD));
    }

    /**
     * 测量不包含 OTRYX RPC 协议/Codec/分派的 Raw Vert.x echo。
     *
     * @return 固定负载长度
     */
    @Benchmark
    public int rawVertxEcho() {
        CompletableFuture<Void> response = new CompletableFuture<>();
        if (!rawPending.compareAndSet(null, response)) {
            throw new IllegalStateException(
                    "Raw Vert.x benchmark only supports one inflight request");
        }
        rawSocket.write(Buffer.buffer(PAYLOAD))
                .toCompletionStage()
                .toCompletableFuture()
                .join();
        response.join();
        return PAYLOAD.length();
    }

    /**
     * 测量完整 OTRYX RPC Consumer 到 Provider 的 Unary echo。
     *
     * @return echo 结果长度
     */
    @Benchmark
    public int peachRpcEcho() {
        return rpcService.echo(PAYLOAD).length();
    }

    /** 关闭 benchmark 资源。 */
    @TearDown(Level.Trial)
    public void tearDown() {
        if (rpcClient != null) {
            rpcClient.close();
        }
        if (rpcServer != null) {
            rpcServer.close();
        }
        if (controlPlane != null) {
            controlPlane.close();
        }
        if (rawSocket != null) {
            rawSocket.close();
        }
        if (rawClient != null) {
            rawClient.close();
        }
        if (rawServer != null) {
            rawServer.close();
        }
        if (rawVertx != null) {
            rawVertx.close();
        }
    }

    private static int freePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
