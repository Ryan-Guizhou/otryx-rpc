package com.peachsoft.otryx.benchmarks;

import com.peachsoft.otryx.api.RpcEndpoint;
import com.peachsoft.otryx.api.ServiceInstance;
import com.peachsoft.otryx.api.ApplicationKey;
import com.peachsoft.otryx.api.ServiceKey;
import com.peachsoft.otryx.loadbalance.LoadBalanceContext;
import com.peachsoft.otryx.loadbalance.LoadBalanceMetrics;
import com.peachsoft.otryx.loadbalance.defaults.P2cEwmaLoadBalancer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;

/**
 * P2C/EWMA 旧分配路径与数组快路径基准。
 *
 * @Author Ryan
 * @Version 1.0.0
 * @CreateTime 2026/9/23 15:46
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Thread)
public class LoadBalancePathBenchmark {

    private final P2cEwmaLoadBalancer loadBalancer =
            new P2cEwmaLoadBalancer();
    private ServiceInstance[] instances;
    private LoadBalanceMetrics metrics;

    /** 创建负载均衡路径基准。 */
    public LoadBalancePathBenchmark() {
    }

    /** 准备八个固定服务实例。 */
    @Setup
    public void setup() {
        ServiceKey key = new ServiceKey(
                "benchmark.Service",
                "1.0.0",
                "default");
        instances = new ServiceInstance[8];
        for (int index = 0; index < instances.length; index++) {
            instances[index] = new ServiceInstance(
                    "node-" + index,
                    new ApplicationKey("benchmark", "load-balancer"),
                    key,
                    new RpcEndpoint(
                            "127.0.0.1",
                            19090 + index),
                    100,
                    Map.of());
        }
        metrics = new LoadBalanceMetrics() {
            @Override
            public long ewmaLatencyNanos(
                    ServiceInstance instance) {
                return 1_000_000L
                        + instance.endpoint().port();
            }

            @Override
            public int inflight(
                    ServiceInstance instance) {
                return instance.endpoint().port() & 3;
            }
        };
    }

    /**
     * 模拟旧 Consumer 每请求创建候选上下文的路径。
     *
     * @return 选中实例
     */
    @Benchmark
    public ServiceInstance compatibilityListPath() {
        List<LoadBalanceContext> candidates =
                new ArrayList<>(instances.length);
        for (ServiceInstance instance : instances) {
            candidates.add(new LoadBalanceContext(
                    instance,
                    metrics.ewmaLatencyNanos(instance),
                    metrics.inflight(instance)));
        }
        return loadBalancer.select(candidates);
    }

    /**
     * 测量数组快照 + 实时指标路径。
     *
     * @return 选中实例
     */
    @Benchmark
    public ServiceInstance arrayMetricsPath() {
        return loadBalancer.select(
                instances,
                metrics);
    }
}
