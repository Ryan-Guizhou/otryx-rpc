package com.peachsoft.otryx.benchmarks;

import com.peachsoft.otryx.api.RpcEndpoint;
import com.peachsoft.otryx.codec.RpcCodecIds;
import com.peachsoft.otryx.codec.RpcCodecRegistry;
import com.peachsoft.otryx.core.OtryxRpcClient;
import com.peachsoft.otryx.core.OtryxRpcServer;
import com.peachsoft.otryx.core.RpcProviderExecutionOptions;
import com.peachsoft.otryx.spi.ExtensionLoader;
import com.peachsoft.otryx.transport.RpcTransportFactory;
import com.peachsoft.otryx.transport.RpcTransportOptions;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import org.openjdk.jmh.annotations.AuxCounters;
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
 * V2-D.2 Provider 执行、慢调用与过载性能矩阵。
 *
 * @Author Ryan
 * @Version 1.0.0
 * @CreateTime 2026/9/29 18:54
 */
@BenchmarkMode({Mode.SampleTime, Mode.Throughput})
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
@State(Scope.Benchmark)
public class ExecutionScenarioBenchmark {

    /** 场景名称。 */
    @Param({
        "NOOP",
        "CPU",
        "BLOCKING",
        "SLOW_PROVIDER",
        "OVERLOAD"
    })
    public String scenario;

    /** 单 Endpoint 连接分片数。 */
    @Param({"1", "4"})
    public int connectionsPerEndpoint;

    private BenchmarkApplicationControlPlane controlPlane;
    private OtryxRpcServer server;
    private OtryxRpcClient client;
    private ScenarioBenchmarkService service;

    /** 创建场景 Benchmark。 */
    public ExecutionScenarioBenchmark() {
    }

    /**
     * 启动场景对应的完整 RPC loopback。
     *
     * @throws Exception 端口初始化失败
     */
    @Setup(Level.Trial)
    public void setup() throws Exception {
        int port = freePort();
        controlPlane = new BenchmarkApplicationControlPlane("scenario-benchmark");

        RpcCodecRegistry codecs =
                RpcCodecRegistry.fromSpi();
        RpcTransportFactory transportFactory =
                ExtensionLoader
                        .getLoader(RpcTransportFactory.class)
                        .getExtension("vertx");
        RpcTransportOptions transportOptions =
                new RpcTransportOptions(
                        4096,
                        1024 * 1024,
                        16 * 1024 * 1024,
                        Duration.ofSeconds(3),
                        Duration.ofSeconds(3),
                        Set.of(RpcCodecIds.FORY_NATIVE),
                        connectionsPerEndpoint);

        RpcProviderExecutionOptions executionOptions =
                new RpcProviderExecutionOptions(
                        false,
                        Math.max(
                                1,
                                Runtime.getRuntime()
                                        .availableProcessors()),
                        "OVERLOAD".equals(scenario)
                                ? 8
                                : 1024);

        server = OtryxRpcServer.builder()
                .applicationMembership(controlPlane.provider())
                .transportServer(
                        transportFactory.createServer(
                                transportOptions))
                .codecRegistry(codecs)
                .bindEndpoint(
                        new RpcEndpoint(
                                "127.0.0.1",
                                port))
                .maxConcurrent(
                        "OVERLOAD".equals(scenario)
                                ? 8
                                : 4096)
                .executionOptions(executionOptions)
                .build()
                .registerService(
                        ScenarioBenchmarkService.class,
                        new ScenarioService(),
                        "1.0.0",
                        "benchmark");
        server.start()
                .toCompletableFuture()
                .join();

        client = controlPlane.clientBuilder()
                .transportClient(
                        transportFactory.createClient(
                                transportOptions))
                .codecRegistry(codecs)
                .timeout(Duration.ofSeconds(5))
                .build();
        service = client.refer(
                ScenarioBenchmarkService.class,
                "1.0.0",
                "benchmark");
        controlPlane.warmup(client, () -> service.noOp());
    }

    /**
     * 执行当前场景。
     *
     * @param counters 成功/失败计数器
     * @return 调用结果；失败返回 -1
     */
    @Benchmark
    public int invoke(OutcomeCounters counters) {
        try {
            int value = switch (scenario) {
                case "NOOP" -> service.noOp();
                case "CPU" -> service.cpu();
                case "BLOCKING" -> service.blocking();
                case "SLOW_PROVIDER", "OVERLOAD" ->
                        service.slow();
                default -> throw new IllegalStateException(
                        "Unknown scenario: " + scenario);
            };
            counters.successes++;
            return value;
        } catch (RuntimeException error) {
            counters.errors++;
            return -1;
        }
    }

    /** 关闭场景资源。 */
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

    private static int freePort() throws Exception {
        try (ServerSocket socket =
                     new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static final class ScenarioService
            implements ScenarioBenchmarkService {

        @Override
        public int noOp() {
            return 1;
        }

        @Override
        public int cpu() {
            long value = 0x9e3779b97f4a7c15L;
            for (int index = 0; index < 512; index++) {
                value ^= value << 13;
                value ^= value >>> 7;
                value ^= value << 17;
            }
            return (int) value;
        }

        @Override
        public int blocking() {
            LockSupport.parkNanos(
                    TimeUnit.MILLISECONDS.toNanos(1));
            return 1;
        }

        @Override
        public int slow() {
            LockSupport.parkNanos(
                    TimeUnit.MILLISECONDS.toNanos(10));
            return 1;
        }
    }

    /**
     * JMH 场景结果计数器。
     *
     * <p>用于记录 overload/timeout 等错误率，不让预期失败直接终止基准。
     */
    @AuxCounters(AuxCounters.Type.EVENTS)
    @State(Scope.Thread)
    public static class OutcomeCounters {

        /** 成功 RPC 数。 */
        public long successes;

        /** 失败 RPC 数。 */
        public long errors;

        /** 创建结果计数器。 */
        public OutcomeCounters() {
        }
    }
}
