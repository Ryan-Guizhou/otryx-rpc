package com.peachsoft.otryx.benchmarks;

import com.peachsoft.otryx.api.RpcSecurityProfile;
import com.peachsoft.otryx.api.RpcEndpoint;
import com.peachsoft.otryx.codec.RpcCodecIds;
import com.peachsoft.otryx.codec.RpcCodecRegistry;
import com.peachsoft.otryx.core.OtryxRpcClient;
import com.peachsoft.otryx.core.OtryxRpcServer;
import com.peachsoft.otryx.observability.RpcSecurityMode;
import com.peachsoft.otryx.spi.ExtensionLoader;
import com.peachsoft.otryx.transport.RpcTransportFactory;
import com.peachsoft.otryx.transport.RpcTransportOptions;
import com.peachsoft.otryx.transport.RpcTransportSecurityOptions;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;

/**
 * OTRYX RPC 完整 Unary byte[] 往返性能矩阵。
 *
 * <p>Payload 与每 Endpoint 连接分片通过 Param 控制；并发通过 JMH
 * `-t` 参数控制。该基准保留完整 Generated Stub、Fory、Protocol、
 * Vert.x Transport 与 Provider Dispatcher 路径。
 *
 * @Author Ryan
 * @Version 1.0.0
 * @CreateTime 2026/9/29 17:55
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 8, time = 1)
@Fork(2)
@State(Scope.Benchmark)
public class EndToEndPayloadBenchmark {

    /** 业务 Payload 大小。 */
    @Param({"64", "256", "1024", "16384", "1048576"})
    public int payloadSize;

    /** 单 Endpoint 连接分片数。 */
    @Param({"1", "2", "4", "8"})
    public int connectionsPerEndpoint;

    /** Transport 安全模式；完整矩阵脚本会显式覆盖为 PLAINTEXT/TLS。 */
    @Param({"PLAINTEXT"})
    public String transportSecurity;

    private BenchmarkApplicationControlPlane controlPlane;
    private OtryxRpcServer server;
    private OtryxRpcClient client;
    private PayloadBenchmarkService service;
    private byte[] payload;

    /** 创建端到端 Payload Benchmark。 */
    public EndToEndPayloadBenchmark() {
    }

    /**
     * 启动完整 OTRYX RPC loopback。
     *
     * @throws Exception 端口初始化失败
     */
    @Setup(Level.Trial)
    public void setup() throws Exception {
        payload = new byte[payloadSize];
        int port = freePort();
        controlPlane = new BenchmarkApplicationControlPlane("benchmark");

        RpcCodecRegistry codecs =
                RpcCodecRegistry.fromSpi();
        RpcTransportFactory transportFactory =
                ExtensionLoader
                        .getLoader(RpcTransportFactory.class)
                        .getExtension("vertx");
        RpcTransportOptions baseTransportOptions =
                new RpcTransportOptions(
                        4096,
                        16 * 1024 * 1024,
                        16 * 1024 * 1024,
                        Duration.ofSeconds(3),
                        Duration.ofSeconds(3),
                        Set.of(RpcCodecIds.FORY_NATIVE),
                        connectionsPerEndpoint);
        RpcTransportOptions serverTransportOptions =
                baseTransportOptions.withSecurity(
                        serverSecurity());
        RpcTransportOptions clientTransportOptions =
                baseTransportOptions.withSecurity(
                        clientSecurity());

        server = OtryxRpcServer.builder()
                .securityProfile(RpcSecurityProfile.TRUSTED_DEVELOPMENT)
                .applicationMembership(controlPlane.provider())
                .transportServer(
                        transportFactory.createServer(
                                serverTransportOptions))
                .codecRegistry(codecs)
                .bindEndpoint(
                        new RpcEndpoint(
                                "127.0.0.1",
                                port))
                .maxConcurrent(16384)
                .build()
                .registerService(
                        PayloadBenchmarkService.class,
                        (PayloadBenchmarkService) value -> value,
                        "1.0.0",
                        "benchmark");
        server.start()
                .toCompletableFuture()
                .join();

        client = controlPlane.clientBuilder()
                .transportClient(
                        transportFactory.createClient(
                                clientTransportOptions))
                .codecRegistry(codecs)
                .timeout(Duration.ofSeconds(10))
                .build();
        service = client.refer(
                PayloadBenchmarkService.class,
                "1.0.0",
                "benchmark");

        controlPlane.warmup(client, () -> service.echo(payload));
    }

    /**
     * 测量完整 OTRYX RPC byte[] echo 往返。
     *
     * @return 返回 Payload 长度
     */
    @Benchmark
    public int echo() {
        return service.echo(payload).length;
    }

    /** 关闭 Benchmark 资源。 */
    @TearDown(Level.Trial)
    public void tearDown() {
        if (client != null) {
            client.close();
        }
        if (server != null) {
            server.close();
        }
        if (controlPlane != null) {
            controlPlane.close();
        }
    }

    private RpcTransportSecurityOptions serverSecurity() {
        if ("PLAINTEXT".equalsIgnoreCase(transportSecurity)) {
            return RpcTransportSecurityOptions.PLAINTEXT;
        }
        if (!"TLS".equalsIgnoreCase(transportSecurity)) {
            throw new IllegalArgumentException(
                    "Unsupported transportSecurity: " + transportSecurity);
        }
        return new RpcTransportSecurityOptions(
                RpcSecurityMode.TLS,
                requiredEnvironment("OTRYX_RPC_BENCHMARK_TLS_CERT"),
                requiredEnvironment("OTRYX_RPC_BENCHMARK_TLS_KEY"),
                "",
                true,
                Duration.ofSeconds(3),
                Duration.ofHours(1),
                Duration.ofDays(1), java.util.Optional.empty());
    }

    private RpcTransportSecurityOptions clientSecurity() {
        if ("PLAINTEXT".equalsIgnoreCase(transportSecurity)) {
            return RpcTransportSecurityOptions.PLAINTEXT;
        }
        if (!"TLS".equalsIgnoreCase(transportSecurity)) {
            throw new IllegalArgumentException(
                    "Unsupported transportSecurity: " + transportSecurity);
        }
        return new RpcTransportSecurityOptions(
                RpcSecurityMode.TLS,
                "",
                "",
                requiredEnvironment("OTRYX_RPC_BENCHMARK_TLS_CA"),
                true,
                Duration.ofSeconds(3),
                Duration.ofHours(1),
                Duration.ofDays(1), java.util.Optional.empty());
    }

    private static String requiredEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(
                    name + " is required for TLS benchmark mode");
        }
        return value;
    }

    private static int freePort() throws Exception {
        try (ServerSocket socket =
                     new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
