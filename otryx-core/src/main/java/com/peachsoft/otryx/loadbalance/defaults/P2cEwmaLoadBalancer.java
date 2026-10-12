package com.peachsoft.otryx.loadbalance.defaults;

import com.peachsoft.otryx.api.ServiceInstance;
import com.peachsoft.otryx.loadbalance.LoadBalanceContext;
import com.peachsoft.otryx.loadbalance.LoadBalanceMetrics;
import com.peachsoft.otryx.loadbalance.LoadBalancer;
import com.peachsoft.otryx.spi.Extension;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Power-of-Two-Choices + EWMA 负载均衡实现。
 *
 * @Author Ryan
 * @Version 1.0.0
 * @CreateTime 2026/9/23 10:51
 */
@Extension(P2cEwmaLoadBalancer.EXTENSION_NAME)
public final class P2cEwmaLoadBalancer implements LoadBalancer {

    static final String EXTENSION_NAME = "p2c-ewma";
    private static final long MIN_EWMA_LATENCY_NANOS = 1_000_000L;

    /** 创建 P2C + EWMA 负载均衡器。 */
    public P2cEwmaLoadBalancer() {
    }

    @Override
    public ServiceInstance select(
            ServiceInstance[] candidates,
            LoadBalanceMetrics metrics) {
        int size = candidates.length;
        if (size == 0) {
            return null;
        }
        if (size == 1) {
            return metrics.available(candidates[0])
                    ? candidates[0]
                    : null;
        }

        ThreadLocalRandom random = ThreadLocalRandom.current();
        int firstIndex = findAvailable(
                candidates,
                metrics,
                random.nextInt(size),
                -1);
        if (firstIndex < 0) {
            return null;
        }
        int secondIndex = findAvailable(
                candidates,
                metrics,
                random.nextInt(size),
                firstIndex);
        if (secondIndex < 0) {
            return candidates[firstIndex];
        }

        ServiceInstance first = candidates[firstIndex];
        ServiceInstance second = candidates[secondIndex];
        return score(first, metrics) <= score(second, metrics)
                ? first
                : second;
    }

    @Override
    public ServiceInstance select(
            List<LoadBalanceContext> candidates) {
        if (candidates.isEmpty()) {
            return null;
        }
        if (candidates.size() == 1) {
            return candidates.getFirst().instance();
        }

        ThreadLocalRandom random = ThreadLocalRandom.current();
        int firstIndex = random.nextInt(candidates.size());
        int secondIndex = random.nextInt(candidates.size() - 1);
        if (secondIndex >= firstIndex) {
            secondIndex++;
        }

        LoadBalanceContext first = candidates.get(firstIndex);
        LoadBalanceContext second = candidates.get(secondIndex);
        return score(first) <= score(second)
                ? first.instance()
                : second.instance();
    }

    private static int findAvailable(
            ServiceInstance[] candidates,
            LoadBalanceMetrics metrics,
            int start,
            int excluded) {
        for (int offset = 0; offset < candidates.length; offset++) {
            int index = (start + offset) % candidates.length;
            if (index != excluded && metrics.available(candidates[index])) {
                return index;
            }
        }
        return -1;
    }

    private static double score(
            ServiceInstance instance,
            LoadBalanceMetrics metrics) {
        long latencyNanos = Math.max(
                metrics.ewmaLatencyNanos(instance),
                MIN_EWMA_LATENCY_NANOS);
        return latencyNanos
                * (metrics.inflight(instance) + 1.0)
                / instance.weight();
    }

    private static double score(
            LoadBalanceContext context) {
        long latencyNanos = Math.max(
                context.ewmaLatencyNanos(),
                MIN_EWMA_LATENCY_NANOS);
        return latencyNanos
                * (context.inflight() + 1.0)
                / context.instance().weight();
    }
}
