package com.peachsoft.otryx.core;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 单端点负载均衡与异常实例剔除统计。
 *
 * @Author Ryan
 * @Version 1.0.0
 * @CreateTime 2026/9/23 10:51
 */
final class EndpointStats {

    private final AtomicInteger inflight = new AtomicInteger();
    private final AtomicLong ewmaNanos = new AtomicLong(1_000_000);
    private final AtomicInteger consecutiveFailures = new AtomicInteger();
    private final AtomicLong ejectedUntilNanos = new AtomicLong();

    int inflight() {
        return inflight.get();
    }

    long ewma() {
        return ewmaNanos.get();
    }

    boolean available() {
        for (;;) {
            long ejectedUntil = ejectedUntilNanos.get();
            if (ejectedUntil == 0L) {
                return true;
            }
            if (System.nanoTime() < ejectedUntil) {
                return false;
            }
            if (ejectedUntilNanos.compareAndSet(
                    ejectedUntil,
                    0L)) {
                return true;
            }
        }
    }

    void begin() {
        inflight.incrementAndGet();
    }

    void endSuccess(long nanos) {
        end(nanos);
        resetConsecutiveFailures();
    }

    void endCancelled(long nanos) {
        end(nanos);
    }

    void endSecurityRejected() {
        inflight.decrementAndGet();
    }

    boolean endFailure(
            long nanos,
            RpcClientResilienceOptions options) {
        end(nanos);
        int failures =
                consecutiveFailures.incrementAndGet();
        if (failures
                < options.outlierConsecutiveFailureThreshold()) {
            return false;
        }
        consecutiveFailures.set(0);
        ejectedUntilNanos.set(
                System.nanoTime()
                        + options.outlierEjectionDuration()
                                .toNanos());
        return true;
    }

    private void resetConsecutiveFailures() {
        for (;;) {
            int failures = consecutiveFailures.get();
            if (failures == 0) {
                return;
            }
            if (consecutiveFailures.compareAndSet(
                    failures,
                    0)) {
                return;
            }
        }
    }

    private void end(long nanos) {
        inflight.decrementAndGet();
        ewmaNanos.updateAndGet(previous ->
                previous + (nanos - previous) / 8);
    }
}
