package com.peachsoft.otryx.benchmarks;

import com.peachsoft.otryx.api.RpcEndpoint;
import com.peachsoft.otryx.api.RpcStatus;
import com.peachsoft.otryx.api.ServiceKey;
import com.peachsoft.otryx.observability.RpcObserver;
import com.peachsoft.otryx.observability.jfr.JfrRpcObserver;
import com.peachsoft.otryx.observability.micrometer.MicrometerRpcObserver;
import com.peachsoft.otryx.observability.opentelemetry.OpenTelemetryRpcTracingBridge;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.opentelemetry.api.OpenTelemetry;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;

/**
 * 对比 NOOP、Micrometer、禁用录制的 JFR 与 NOOP OpenTelemetry 的调用开销。
 *
 * <p>只测量 Observer/Tracing Bridge 本地事件开销，不含真实 Exporter、
 * 采样后端或 RPC 网络。必须通过相同硬件 JMH/JFR 实测后方可给出性能结论。
 *
 * @Author OpenAI
 * @Version 1.0.0-SNAPSHOT
 * @CreateTime 2026/10/14 16:00
 */
@BenchmarkMode({Mode.SampleTime, Mode.Throughput})
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Thread)
public class ObservabilityPathBenchmark {

    private static final int METHOD_ID = 7;
    private static final RpcEndpoint ENDPOINT =
            new RpcEndpoint("127.0.0.1", 19090);
    private static final ServiceKey SERVICE_KEY =
            new ServiceKey("benchmark.Observability", "1.0.0", "default");

    private RpcObserver noop;
    private RpcObserver micrometer;
    private RpcObserver jfr;
    private SimpleMeterRegistry registry;
    private OpenTelemetryRpcTracingBridge tracing;

    /** 初始化待比较的观测出口，并避免在测量中重复创建 Registry。 */
    @Setup(Level.Trial)
    public void setup() {
        noop = RpcObserver.noop();
        registry = new SimpleMeterRegistry();
        micrometer = new MicrometerRpcObserver(registry);
        jfr = new JfrRpcObserver(Duration.ofMillis(1));
        tracing = new OpenTelemetryRpcTracingBridge(OpenTelemetry.noop());
    }

    /** 测量无观测出口的基线事件路径。 */
    @Benchmark
    public void noopLogicalCall() {
        noop.onClientCallCompleted(SERVICE_KEY, METHOD_ID, 100L, RpcStatus.OK, null);
    }

    /** 测量固定低基数标签的 Micrometer 事件路径。 */
    @Benchmark
    public void micrometerLogicalCall() {
        micrometer.onClientCallCompleted(SERVICE_KEY, METHOD_ID, 100L, RpcStatus.OK, null);
    }

    /** 测量没有启用 JFR Recording 时的调用事件路径。 */
    @Benchmark
    public void jfrDisabledLogicalCall() {
        jfr.onClientAttemptCompleted(
                SERVICE_KEY,
                METHOD_ID,
                ENDPOINT,
                1,
                Duration.ofMillis(2).toNanos(),
                RpcStatus.OK,
                null);
    }

    /** 测量 OpenTelemetry NOOP 实现，不将其解释为完整 Exporter 的开销。 */
    @Benchmark
    public void otelNoopClientSpan() {
        tracing.startClient(SERVICE_KEY, METHOD_ID).end(RpcStatus.OK, null);
    }

    /** 释放本次 JMH State 拥有的 MeterRegistry。 */
    @TearDown(Level.Trial)
    public void tearDown() {
        registry.close();
    }
}
