package com.peachsoft.otryx.compare;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.concurrent.atomic.LongAdder;

/**
 * Peach/Dubbo 共用的同步 byte[] Echo 负载生成与证据采集器。
 *
 * <p>通过单独的 provider 与 consumer JVM 测试真实网络调用；两套独立打包
 * 运行时调用同一份本类，避免基准负载漂移。负载为 closed-loop，不适用于
 * 消除 coordinated omission 的正式 open-loop 延迟声明。
 *
 * @Author Ryan
 * @Version 1.0.0
 * @CreateTime 2026/10/10 14:27
 */
public final class ComparisonHarness {

    private static final int SAMPLE_LIMIT = 1_000_000;
    private static final String SYSTEM_JAVA_VERSION = "java.version";
    private static final String SYSTEM_OS_NAME = "os.name";
    private static final String SYSTEM_OS_ARCH = "os.arch";

    private ComparisonHarness() {
    }

    /** 公平比较所需的最小同步 RPC 调用抽象。 */
    @FunctionalInterface
    public interface EchoInvoker {

        /**
         * 返回与请求完全相同的 bytes。
         *
         * @param bytes 请求数据
         * @return 与请求相同的应答
         */
        byte[] echo(byte[] bytes);
    }

    /**
     * 从 client 命令参数执行负载并输出 JSON Evidence。
     *
     * @param framework 框架标识 peach/dubbo
     * @param protocol 协议名称
     * @param serializer 序列化方式
     * @param args client host port concurrency warmup_seconds duration_seconds payload_bytes output.json
     * @param invoker RPC 业务调用入口
     * @throws Exception 调用或文件输出失败
     */
    public static void run(
            String framework,
            String protocol,
            String serializer,
            String[] args,
            EchoInvoker invoker) throws Exception {
        if (args.length != 8 || !"client".equals(args[0])) {
            throw new IllegalArgumentException(
                    "Usage: client <host> <port> <concurrency> "
                            + "<warmup_seconds> <duration_seconds> "
                            + "<payload_bytes> <output.json>");
        }
        String host = args[1];
        int port = Integer.parseInt(args[2]);
        int concurrency = Integer.parseInt(args[3]);
        int warmupSeconds = Integer.parseInt(args[4]);
        int measurementSeconds = Integer.parseInt(args[5]);
        int payloadBytes = Integer.parseInt(args[6]);
        if (host.isBlank() || port < 1 || port > 65535
                || concurrency < 1 || concurrency > 10000
                || warmupSeconds < 1 || measurementSeconds < 1
                || payloadBytes < 0 || payloadBytes > 1_048_576) {
            throw new IllegalArgumentException(
                    "Invalid host/port/concurrency/time/payload configuration");
        }

        byte[] payload = new byte[payloadBytes];
        for (int index = 0; index < payload.length; index++) {
            payload[index] = (byte) (index * 31 + 17);
        }
        verify(invoker.echo(payload), payload);

        CountDownLatch ready = new CountDownLatch(concurrency);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(concurrency);
        LongAdder success = new LongAdder();
        LongAdder errors = new LongAdder();
        ConcurrentHashMap<String, LongAdder> errorTypes = new ConcurrentHashMap<>();
        Latencies latencies = new Latencies();
        AtomicLong warmupEndNanos = new AtomicLong();
        AtomicLong measurementEndNanos = new AtomicLong();

        long startNanos;
        long cpuBefore;
        long gcCountBefore;
        long gcMillisBefore;
        long heapBefore;
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < concurrency; i++) {
                executor.submit(() -> {
                    ready.countDown();
                    try {
                        start.await();
                        while (System.nanoTime() < measurementEndNanos.get()) {
                            long requestedAt = System.nanoTime();
                            boolean measuring = requestedAt >= warmupEndNanos.get();
                            try {
                                verify(invoker.echo(payload), payload);
                                if (measuring) {
                                    success.increment();
                                    latencies.record(System.nanoTime() - requestedAt);
                                }
                            } catch (RuntimeException error) {
                                if (measuring) {
                                    errors.increment();
                                    errorTypes.computeIfAbsent(
                                            rootType(error),
                                            ignored -> new LongAdder()).increment();
                                }
                            }
                        }
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    } finally {
                        finished.countDown();
                    }
                });
            }
            if (!ready.await(120, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Comparison workers failed to start");
            }
            startNanos = System.nanoTime();
            warmupEndNanos.set(
                    startNanos + TimeUnit.SECONDS.toNanos(warmupSeconds));
            measurementEndNanos.set(
                    startNanos + TimeUnit.SECONDS.toNanos(
                            (long) warmupSeconds + measurementSeconds));
            start.countDown();
            Thread.sleep(TimeUnit.SECONDS.toMillis(warmupSeconds));
            cpuBefore = processCpuNanos();
            gcCountBefore = gcCount();
            gcMillisBefore = gcTimeMillis();
            heapBefore = heapUsedBytes();
            long awaitSeconds = (long) warmupSeconds + measurementSeconds + 45L;
            if (!finished.await(awaitSeconds, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Comparison workers did not finish");
            }
        }

        long cpuAfter = processCpuNanos();
        long gcCountAfter = gcCount();
        long gcMillisAfter = gcTimeMillis();
        long heapAfter = heapUsedBytes();
        double throughput = success.sum() / (double) measurementSeconds;
        double cpuCores = (cpuAfter - cpuBefore)
                / (double) TimeUnit.SECONDS.toNanos(measurementSeconds);
        long total = success.sum() + errors.sum();

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("schema", "otryx.rpc.comparison.v1");
        report.put("recorded_at", Instant.now().toString());
        report.put("evidence_class", System.getenv().getOrDefault(
                "RPC_COMPARISON_EVIDENCE_CLASS", "smoke"));
        report.put("run_id", System.getenv().getOrDefault(
                "RPC_COMPARISON_RUN_ID", "local-uncontrolled"));
        report.put("git_sha", System.getenv().getOrDefault(
                "RPC_COMPARISON_GIT_SHA", "unverified"));
        report.put("framework", framework);
        report.put("protocol", protocol);
        report.put("serializer", serializer);
        report.put("workload", "sync-byte-array-echo-closed-loop");
        report.put("provider_host", host);
        report.put("provider_port", port);
        report.put("concurrency", concurrency);
        report.put("payload_bytes", payloadBytes);
        report.put("warmup_seconds", warmupSeconds);
        report.put("measurement_seconds", measurementSeconds);
        report.put("attempts", total);
        report.put("success", success.sum());
        report.put("errors", errors.sum());
        report.put("error_rate", total == 0L ? 0.0d : errors.sum() / (double) total);
        report.put("throughput_qps", throughput);
        report.put("qps_per_cpu_core", null);
        report.put("qps_per_client_cpu_core",
                cpuCores <= 0.0d ? null : throughput / cpuCores);
        report.put("client_process_cpu_cores", cpuCores);
        report.put("client_gc_count_delta", gcCountAfter - gcCountBefore);
        report.put("client_gc_millis_delta", gcMillisAfter - gcMillisBefore);
        report.put("client_heap_start_bytes", heapBefore);
        report.put("client_heap_end_bytes", heapAfter);
        report.put("p50_us", latencies.percentile(0.50d));
        report.put("p99_us", latencies.percentile(0.99d));
        report.put("p999_us", latencies.percentile(0.999d));
        report.put("latency_samples", latencies.sampleCount());
        report.put("allocation_bytes_per_op", null);
        report.put("server_cpu_cores", null);
        report.put("server_gc_millis_delta", null);
        report.put("connection_count", null);
        report.put("java_version", System.getProperty(SYSTEM_JAVA_VERSION));
        report.put("os_name", System.getProperty(SYSTEM_OS_NAME));
        report.put("os_arch", System.getProperty(SYSTEM_OS_ARCH));
        Map<String, Long> failures = new TreeMap<>();
        errorTypes.forEach((name, value) -> failures.put(name, value.sum()));
        report.put("errors_by_type", failures);

        Path output = Path.of(args[7]).toAbsolutePath();
        if (output.getParent() != null) {
            Files.createDirectories(output.getParent());
        }
        Files.writeString(output, toJson(report) + System.lineSeparator());
        System.out.printf(Locale.ROOT,
                "Comparison completed framework=%s success=%d errors=%d qps=%.1f output=%s%n",
                framework, success.sum(), errors.sum(), throughput, output);
    }

    private static void verify(byte[] actual, byte[] expected) {
        if (!Arrays.equals(actual, expected)) {
            throw new IllegalStateException("Unexpected RPC response payload");
        }
    }

    private static String rootType(Throwable error) {
        Throwable cause = error;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        return cause.getClass().getName();
    }

    private static long processCpuNanos() {
        return ProcessHandle.current().info().totalCpuDuration()
                .orElse(Duration.ZERO).toNanos();
    }

    private static long heapUsedBytes() {
        return ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
    }

    private static long gcCount() {
        long count = 0L;
        for (GarbageCollectorMXBean bean :
                ManagementFactory.getGarbageCollectorMXBeans()) {
            count += Math.max(0L, bean.getCollectionCount());
        }
        return count;
    }

    private static long gcTimeMillis() {
        long millis = 0L;
        for (GarbageCollectorMXBean bean :
                ManagementFactory.getGarbageCollectorMXBeans()) {
            millis += Math.max(0L, bean.getCollectionTime());
        }
        return millis;
    }

    private static String toJson(Map<String, ?> values) {
        StringBuilder output = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, ?> entry : values.entrySet()) {
            if (!first) {
                output.append(',');
            }
            first = false;
            output.append('"').append(escape(entry.getKey())).append("\":");
            Object value = entry.getValue();
            if (value == null) {
                output.append("null");
            } else if (value instanceof Number || value instanceof Boolean) {
                output.append(value);
            } else if (value instanceof Map<?, ?> nested) {
                Map<String, Object> strings = new LinkedHashMap<>();
                nested.forEach((key, item) -> strings.put(String.valueOf(key), item));
                output.append(toJson(strings));
            } else {
                output.append('"').append(escape(value.toString())).append('"');
            }
        }
        return output.append('}').toString();
    }

    private static String escape(String text) {
        return text.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r");
    }

    private static final class Latencies {
        private final AtomicLong cursor = new AtomicLong();
        private final AtomicLongArray nanos = new AtomicLongArray(SAMPLE_LIMIT);

        private void record(long value) {
            long position = cursor.getAndIncrement();
            nanos.set((int) (position % SAMPLE_LIMIT), Math.max(0, value));
        }

        private int sampleCount() {
            return (int) Math.min(cursor.get(), SAMPLE_LIMIT);
        }

        private Double percentile(double fraction) {
            int count = sampleCount();
            if (count == 0) {
                return null;
            }
            long[] sorted = new long[count];
            for (int i = 0; i < count; i++) {
                sorted[i] = nanos.get(i);
            }
            Arrays.sort(sorted);
            int at = Math.max(0,
                    Math.min(count - 1, (int) Math.ceil(count * fraction) - 1));
            return sorted[at] / 1_000.0d;
        }
    }
}
