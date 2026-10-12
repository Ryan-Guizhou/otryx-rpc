package com.peachsoft.otryx.benchmarks;

import com.peachsoft.otryx.api.RpcEndpoint;
import com.peachsoft.otryx.codec.RpcCodecIds;
import com.peachsoft.otryx.codec.RpcCodecRegistry;
import com.peachsoft.otryx.core.OtryxRpcClient;
import com.peachsoft.otryx.core.OtryxRpcServer;
import com.peachsoft.otryx.observability.RpcConnectionCloseReason;
import com.peachsoft.otryx.observability.RpcConnectionRole;
import com.peachsoft.otryx.observability.RpcObserver;
import com.peachsoft.otryx.registry.Registry;
import com.peachsoft.otryx.registry.RegistryFactory;
import com.peachsoft.otryx.registry.RegistryOptions;
import com.peachsoft.otryx.spi.ExtensionLoader;
import com.peachsoft.otryx.transport.RpcTransportFactory;
import com.peachsoft.otryx.transport.RpcTransportOptions;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.ThreadMXBean;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAccumulator;
import java.util.concurrent.atomic.LongAdder;

/**
 * V2-D.2 逻辑高并发长时间稳定性 Runner。
 *
 * <p>默认使用 JDK 21 Virtual Threads 驱动同步 Generated Stub，避免使用
 * 10000 个平台线程把 Driver 调度成本误判成 RPC 成本。
 *
 * @Author Ryan
 * @Version 1.0.0
 * @CreateTime 2026/9/29 18:36
 */
public final class PerformanceSoakRunner {

    private static final int SAMPLE_CAPACITY = 1_000_000;
    private static final String SYSTEM_JAVA_VERSION = "java.version";
    private static final String SYSTEM_JAVA_VM_NAME = "java.vm.name";
    private static final String SYSTEM_OS_NAME = "os.name";
    private static final String SYSTEM_OS_ARCH = "os.arch";
    private static final String ENV_BENCHMARK_COMMIT = "OTRYX_RPC_BENCHMARK_COMMIT";
    private static final String ENV_GITHUB_SHA = "GITHUB_SHA";
    private static final String TRANSPORT_EXTENSION = "vertx";
    private static final String REGISTRY_EXTENSION = "memory";

    private PerformanceSoakRunner() {
    }

    /**
     * 运行 Soak 并把机器可读结果写入 JSON。
     *
     * @param args 命令行参数
     * @throws Exception 初始化或输出失败
     */
    public static void main(String[] args) throws Exception {
        Config config = Config.parse(args);
        SoakResult result = execute(config);
        Path output = config.output().toAbsolutePath();
        Path parent = output.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.writeString(output, result.toJson());
    }

    private static SoakResult execute(Config config) throws Exception {
        int port = freePort();
        byte[] payload = new byte[config.payloadSize()];
        ConnectionObserver observer = new ConnectionObserver();
        Registry registry = memoryRegistry();
        RpcTransportFactory transportFactory =
                ExtensionLoader.getLoader(RpcTransportFactory.class)
                        .getExtension(TRANSPORT_EXTENSION);
        RpcTransportOptions transportOptions =
                transportOptions(config, observer);
        RpcCodecRegistry codecs = RpcCodecRegistry.fromSpi();

        try (registry;
             OtryxRpcServer server = server(
                     registry,
                     transportFactory,
                     transportOptions,
                     codecs,
                     config,
                     port);
             OtryxRpcClient client = client(
                     registry,
                     transportFactory,
                     transportOptions,
                     codecs,
                     config)) {
            server.start().toCompletableFuture().join();
            PayloadBenchmarkService service = client.refer(
                    PayloadBenchmarkService.class,
                    "1.0.0",
                    "benchmark");
            verifyResponse(service.echo(payload), payload.length);

            runPhase(
                    service,
                    payload,
                    config.concurrency(),
                    Duration.ofSeconds(config.warmupSeconds()),
                    null);

            RuntimeSnapshot before = RuntimeSnapshot.capture();
            LatencyRecorder recorder =
                    new LatencyRecorder(SAMPLE_CAPACITY);
            PhaseResult phase = runPhase(
                    service,
                    payload,
                    config.concurrency(),
                    Duration.ofSeconds(config.durationSeconds()),
                    recorder);
            RuntimeSnapshot after = RuntimeSnapshot.capture();

            return SoakResult.create(
                    config,
                    phase,
                    recorder,
                    observer,
                    before,
                    after);
        }
    }

    private static Registry memoryRegistry() {
        return ExtensionLoader.getLoader(RegistryFactory.class)
                .getExtension(REGISTRY_EXTENSION)
                .create(new RegistryOptions(
                        List.of(),
                        "v2d2-soak",
                        Map.of()));
    }

    private static OtryxRpcServer server(
            Registry registry,
            RpcTransportFactory transportFactory,
            RpcTransportOptions transportOptions,
            RpcCodecRegistry codecs,
            Config config,
            int port) {
        int maxConcurrent = Math.max(
                16_384,
                config.concurrency() * 2);
        return OtryxRpcServer.builder()
                .serviceRegistrar(
                        registry.registrar().orElseThrow())
                .transportServer(
                        transportFactory.createServer(
                                transportOptions))
                .codecRegistry(codecs)
                .bindEndpoint(
                        new RpcEndpoint(
                                "127.0.0.1",
                                port))
                .maxConcurrent(maxConcurrent)
                .build()
                .registerService(
                        PayloadBenchmarkService.class,
                        (PayloadBenchmarkService) value -> value,
                        "1.0.0",
                        "benchmark");
    }

    private static OtryxRpcClient client(
            Registry registry,
            RpcTransportFactory transportFactory,
            RpcTransportOptions transportOptions,
            RpcCodecRegistry codecs,
            Config config) {
        return OtryxRpcClient.builder()
                .serviceDiscovery(registry)
                .transportClient(
                        transportFactory.createClient(
                                transportOptions))
                .codecRegistry(codecs)
                .timeout(Duration.ofMillis(
                        config.clientTimeoutMillis()))
                .build();
    }

    private static RpcTransportOptions transportOptions(
            Config config,
            RpcObserver observer) {
        int perConnection = Math.max(
                4096,
                ceilDiv(
                        config.concurrency(),
                        config.connectionsPerEndpoint())
                        * 2);
        int maxFrameBytes = Math.max(
                16 * 1024 * 1024,
                config.payloadSize() * 2 + 4096);
        return new RpcTransportOptions(
                perConnection,
                maxFrameBytes,
                64 * 1024 * 1024,
                Duration.ofSeconds(3),
                Duration.ofSeconds(3),
                Set.of(RpcCodecIds.FORY_NATIVE),
                config.connectionsPerEndpoint())
                .withObserver(observer);
    }

    private static PhaseResult runPhase(
            PayloadBenchmarkService service,
            byte[] payload,
            int concurrency,
            Duration duration,
            LatencyRecorder recorder)
            throws Exception {
        CountDownLatch ready =
                new CountDownLatch(concurrency);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done =
                new CountDownLatch(concurrency);
        AtomicLong deadlineNanos =
                new AtomicLong();
        AtomicInteger inflight =
                new AtomicInteger();
        LongAccumulator maxInflight =
                new LongAccumulator(Long::max, 0L);
        LongAdder successes = new LongAdder();
        LongAdder errors = new LongAdder();
        Map<String, LongAdder> errorsByType =
                new ConcurrentHashMap<>();

        long startedAtNanos;
        try (ExecutorService executor =
                     Executors.newVirtualThreadPerTaskExecutor()) {
            for (int index = 0; index < concurrency; index++) {
                executor.submit(() -> {
                    ready.countDown();
                    try {
                        start.await();
                        long deadline =
                                deadlineNanos.get();
                        while (System.nanoTime() < deadline) {
                            int current =
                                    inflight.incrementAndGet();
                            maxInflight.accumulate(current);
                            long requestStarted =
                                    System.nanoTime();
                            try {
                                byte[] response =
                                        service.echo(payload);
                                verifyResponse(
                                        response,
                                        payload.length);
                                successes.increment();
                                if (recorder != null) {
                                    recorder.record(
                                            System.nanoTime()
                                                    - requestStarted);
                                }
                            } catch (RuntimeException error) {
                                errors.increment();
                                String type =
                                        rootType(error);
                                errorsByType
                                        .computeIfAbsent(
                                                type,
                                                ignored ->
                                                        new LongAdder())
                                        .increment();
                            } finally {
                                inflight.decrementAndGet();
                            }
                        }
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }

            if (!ready.await(60, TimeUnit.SECONDS)) {
                throw new IllegalStateException(
                        "Virtual thread workers did not become ready");
            }
            startedAtNanos = System.nanoTime();
            deadlineNanos.set(
                    startedAtNanos + duration.toNanos());
            start.countDown();

            long awaitSeconds =
                    Math.max(
                            30L,
                            duration.toSeconds() + 30L);
            if (!done.await(
                    awaitSeconds,
                    TimeUnit.SECONDS)) {
                throw new IllegalStateException(
                        "Soak workers did not stop in time");
            }
        }

        long elapsedNanos =
                System.nanoTime() - startedAtNanos;
        return new PhaseResult(
                elapsedNanos,
                successes.sum(),
                errors.sum(),
                maxInflight.get(),
                snapshotErrors(errorsByType));
    }

    private static Map<String, Long> snapshotErrors(
            Map<String, LongAdder> source) {
        Map<String, Long> values =
                new TreeMap<>();
        source.forEach((key, value) ->
                values.put(key, value.sum()));
        return Map.copyOf(values);
    }

    private static void verifyResponse(
            byte[] response,
            int expectedLength) {
        if (response == null
                || response.length != expectedLength) {
            throw new IllegalStateException(
                    "Unexpected RPC payload length");
        }
    }

    private static String rootType(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null
                && current.getCause() != current) {
            current = current.getCause();
        }
        return current.getClass().getName();
    }

    private static int ceilDiv(int value, int divisor) {
        return (value + divisor - 1) / divisor;
    }

    private static int freePort() throws Exception {
        try (ServerSocket socket =
                     new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private record PhaseResult(
            long elapsedNanos,
            long successes,
            long errors,
            long maxInflight,
            Map<String, Long> errorsByType) {
    }

    private static final class LatencyRecorder {
        private final long[] samples;
        private final AtomicLong cursor =
                new AtomicLong();
        private final LongAccumulator maxNanos =
                new LongAccumulator(Long::max, 0L);

        private LatencyRecorder(int capacity) {
            samples = new long[capacity];
        }

        private void record(long nanos) {
            long index = cursor.getAndIncrement();
            samples[(int) (index % samples.length)] =
                    Math.max(0L, nanos);
            maxNanos.accumulate(nanos);
        }

        private Percentiles percentiles() {
            int size = (int) Math.min(
                    cursor.get(),
                    samples.length);
            if (size == 0) {
                return new Percentiles(
                        0.0d,
                        0.0d,
                        0.0d,
                        0.0d);
            }
            long[] copy = Arrays.copyOf(
                    samples,
                    size);
            Arrays.sort(copy);
            return new Percentiles(
                    micros(copy, 0.50d),
                    micros(copy, 0.99d),
                    micros(copy, 0.999d),
                    maxNanos.get() / 1000.0d);
        }

        private static double micros(
                long[] sorted,
                double percentile) {
            int index = (int) Math.ceil(
                    percentile * sorted.length) - 1;
            index = Math.max(
                    0,
                    Math.min(
                            index,
                            sorted.length - 1));
            return sorted[index] / 1000.0d;
        }
    }

    private record Percentiles(
            double p50Micros,
            double p99Micros,
            double p999Micros,
            double maxMicros) {
    }

    private static final class ConnectionObserver
            implements RpcObserver {
        private final AtomicInteger active =
                new AtomicInteger();
        private final LongAccumulator maxActive =
                new LongAccumulator(Long::max, 0L);
        private final LongAdder reconnects =
                new LongAdder();
        private final LongAdder heartbeatTimeouts =
                new LongAdder();

        @Override
        public void onConnectionEstablished(
                RpcConnectionRole role,
                RpcEndpoint endpoint,
                long durationNanos) {
            int current = active.incrementAndGet();
            maxActive.accumulate(current);
        }

        @Override
        public void onConnectionReconnectScheduled(
                RpcEndpoint endpoint,
                int attempt,
                long delayMillis) {
            reconnects.increment();
        }

        @Override
        public void onConnectionHeartbeatTimeout(
                RpcConnectionRole role,
                RpcEndpoint endpoint,
                long idleNanos) {
            heartbeatTimeouts.increment();
        }

        @Override
        public void onConnectionClosed(
                RpcConnectionRole role,
                RpcEndpoint endpoint,
                RpcConnectionCloseReason reason,
                Throwable error) {
            active.updateAndGet(
                    current -> Math.max(
                            0,
                            current - 1));
        }
    }

    private record RuntimeSnapshot(
            long heapUsedBytes,
            long gcCount,
            long gcTimeMillis,
            long processCpuTimeNanos,
            int peakPlatformThreads) {

        private static RuntimeSnapshot capture() {
            MemoryMXBean memory =
                    ManagementFactory.getMemoryMXBean();
            ThreadMXBean threads =
                    ManagementFactory.getThreadMXBean();
            long gcCount = 0L;
            long gcTime = 0L;
            for (GarbageCollectorMXBean collector :
                    ManagementFactory
                            .getGarbageCollectorMXBeans()) {
                if (collector.getCollectionCount() >= 0L) {
                    gcCount +=
                            collector.getCollectionCount();
                }
                if (collector.getCollectionTime() >= 0L) {
                    gcTime +=
                            collector.getCollectionTime();
                }
            }
            long processCpuTime = -1L;
            if (ManagementFactory.getOperatingSystemMXBean()
                    instanceof com.sun.management.OperatingSystemMXBean os) {
                processCpuTime =
                        os.getProcessCpuTime();
            }
            return new RuntimeSnapshot(
                    memory.getHeapMemoryUsage().getUsed(),
                    gcCount,
                    gcTime,
                    processCpuTime,
                    threads.getPeakThreadCount());
        }
    }

    private record SoakResult(
            Config config,
            PhaseResult phase,
            Percentiles latency,
            ConnectionObserver connections,
            RuntimeSnapshot before,
            RuntimeSnapshot after,
            Instant completedAt) {

        private static SoakResult create(
                Config config,
                PhaseResult phase,
                LatencyRecorder recorder,
                ConnectionObserver connections,
                RuntimeSnapshot before,
                RuntimeSnapshot after) {
            return new SoakResult(
                    config,
                    phase,
                    recorder.percentiles(),
                    connections,
                    before,
                    after,
                    Instant.now());
        }

        private String toJson() {
            double seconds =
                    phase.elapsedNanos()
                            / 1_000_000_000.0d;
            long total =
                    phase.successes()
                            + phase.errors();
            double throughput =
                    seconds <= 0.0d
                            ? 0.0d
                            : phase.successes()
                                    / seconds;
            double errorRate =
                    total == 0L
                            ? 0.0d
                            : phase.errors()
                                    / (double) total;
            double cpuCores =
                    cpuCores(
                            before.processCpuTimeNanos(),
                            after.processCpuTimeNanos(),
                            phase.elapsedNanos());

            StringBuilder json =
                    new StringBuilder(2048);
            json.append("{\n");
            append(json, "schemaVersion", "1", true);
            append(
                    json,
                    "completedAt",
                    completedAt.toString(),
                    true);
            append(
                    json,
                    "commit",
                    benchmarkCommit(),
                    true);
            append(
                    json,
                    "javaVersion",
                    System.getProperty(SYSTEM_JAVA_VERSION),
                    true);
            append(
                    json,
                    "vmName",
                    System.getProperty(SYSTEM_JAVA_VM_NAME),
                    true);
            append(
                    json,
                    "os",
                    System.getProperty(SYSTEM_OS_NAME)
                            + " "
                            + System.getProperty(SYSTEM_OS_ARCH),
                    true);
            append(
                    json,
                    "availableProcessors",
                    Runtime.getRuntime()
                            .availableProcessors(),
                    true);
            append(
                    json,
                    "jvmArguments",
                    String.join(
                            " ",
                            ManagementFactory
                                    .getRuntimeMXBean()
                                    .getInputArguments()),
                    true);
            append(
                    json,
                    "concurrency",
                    config.concurrency(),
                    true);
            append(
                    json,
                    "payloadBytes",
                    config.payloadSize(),
                    true);
            append(
                    json,
                    "connectionsPerEndpoint",
                    config.connectionsPerEndpoint(),
                    true);
            append(
                    json,
                    "warmupSeconds",
                    config.warmupSeconds(),
                    true);
            append(
                    json,
                    "durationSeconds",
                    seconds,
                    true);
            append(
                    json,
                    "successes",
                    phase.successes(),
                    true);
            append(
                    json,
                    "errors",
                    phase.errors(),
                    true);
            append(
                    json,
                    "errorRate",
                    errorRate,
                    true);
            append(
                    json,
                    "throughputOpsPerSecond",
                    throughput,
                    true);
            append(
                    json,
                    "p50Micros",
                    latency.p50Micros(),
                    true);
            append(
                    json,
                    "p99Micros",
                    latency.p99Micros(),
                    true);
            append(
                    json,
                    "p999Micros",
                    latency.p999Micros(),
                    true);
            append(
                    json,
                    "maxMicros",
                    latency.maxMicros(),
                    true);
            append(
                    json,
                    "maxLogicalInflight",
                    phase.maxInflight(),
                    true);
            append(
                    json,
                    "maxObservedConnections",
                    connections.maxActive.get(),
                    true);
            append(
                    json,
                    "reconnects",
                    connections.reconnects.sum(),
                    true);
            append(
                    json,
                    "heartbeatTimeouts",
                    connections.heartbeatTimeouts.sum(),
                    true);
            append(
                    json,
                    "heapUsedStartBytes",
                    before.heapUsedBytes(),
                    true);
            append(
                    json,
                    "heapUsedEndBytes",
                    after.heapUsedBytes(),
                    true);
            append(
                    json,
                    "gcCountDelta",
                    after.gcCount() - before.gcCount(),
                    true);
            append(
                    json,
                    "gcTimeMillisDelta",
                    after.gcTimeMillis()
                            - before.gcTimeMillis(),
                    true);
            append(
                    json,
                    "processCpuCoresAverage",
                    cpuCores,
                    true);
            append(
                    json,
                    "peakPlatformThreads",
                    after.peakPlatformThreads(),
                    true);
            json.append("  \"errorsByType\": ");
            appendMap(
                    json,
                    phase.errorsByType());
            json.append("\n}\n");
            return json.toString();
        }

        private static double cpuCores(
                long before,
                long after,
                long elapsedNanos) {
            if (before < 0L
                    || after < before
                    || elapsedNanos <= 0L) {
                return -1.0d;
            }
            return (after - before)
                    / (double) elapsedNanos;
        }

        private static String benchmarkCommit() {
            String value = System.getenv(
                    ENV_BENCHMARK_COMMIT);
            if (value == null || value.isBlank()) {
                value = System.getenv(ENV_GITHUB_SHA);
            }
            return value == null || value.isBlank()
                    ? "unknown"
                    : value;
        }

        private static void append(
                StringBuilder target,
                String key,
                String value,
                boolean comma) {
            target.append("  \"")
                    .append(escape(key))
                    .append("\": \"")
                    .append(escape(value))
                    .append('\"');
            target.append(comma ? ",\n" : "\n");
        }

        private static void append(
                StringBuilder target,
                String key,
                long value,
                boolean comma) {
            target.append("  \"")
                    .append(escape(key))
                    .append("\": ")
                    .append(value);
            target.append(comma ? ",\n" : "\n");
        }

        private static void append(
                StringBuilder target,
                String key,
                int value,
                boolean comma) {
            append(
                    target,
                    key,
                    (long) value,
                    comma);
        }

        private static void append(
                StringBuilder target,
                String key,
                double value,
                boolean comma) {
            target.append("  \"")
                    .append(escape(key))
                    .append("\": ")
                    .append(String.format(
                            Locale.ROOT,
                            "%.6f",
                            value));
            target.append(comma ? ",\n" : "\n");
        }

        private static void appendMap(
                StringBuilder target,
                Map<String, Long> values) {
            target.append('{');
            boolean first = true;
            for (Map.Entry<String, Long> entry :
                    values.entrySet()) {
                if (!first) {
                    target.append(", ");
                }
                first = false;
                target.append('\"')
                        .append(escape(entry.getKey()))
                        .append("\": ")
                        .append(entry.getValue());
            }
            target.append('}');
        }

        private static String escape(String value) {
            return value
                    .replace("\\", "\\\\")
                    .replace("\"", "\\\"")
                    .replace("\n", "\\n")
                    .replace("\r", "\\r");
        }
    }

    private record Config(
            int concurrency,
            int payloadSize,
            int connectionsPerEndpoint,
            int warmupSeconds,
            int durationSeconds,
            long clientTimeoutMillis,
            Path output) {

        private static Config parse(String[] args) {
            Map<String, String> values =
                    new LinkedHashMap<>();
            for (String argument : args) {
                if (!argument.startsWith("--")
                        || !argument.contains("=")) {
                    throw new IllegalArgumentException(
                            "Expected --key=value arguments");
                }
                int separator =
                        argument.indexOf('=');
                values.put(
                        argument.substring(
                                2,
                                separator),
                        argument.substring(
                                separator + 1));
            }
            Config config = new Config(
                    integer(
                            values,
                            "concurrency",
                            10_000),
                    integer(
                            values,
                            "payload-size",
                            256),
                    integer(
                            values,
                            "connections-per-endpoint",
                            4),
                    integer(
                            values,
                            "warmup-seconds",
                            15),
                    integer(
                            values,
                            "duration-seconds",
                            600),
                    longValue(
                            values,
                            "client-timeout-ms",
                            5000L),
                    Path.of(values.getOrDefault(
                            "output",
                            "target/v2d2-soak.json")));
            config.validate();
            return config;
        }

        private void validate() {
            if (concurrency <= 0
                    || payloadSize <= 0
                    || connectionsPerEndpoint <= 0
                    || warmupSeconds < 0
                    || durationSeconds <= 0
                    || clientTimeoutMillis <= 0L) {
                throw new IllegalArgumentException(
                        "Soak configuration values are invalid");
            }
        }

        private static int integer(
                Map<String, String> values,
                String key,
                int fallback) {
            return Integer.parseInt(
                    values.getOrDefault(
                            key,
                            Integer.toString(fallback)));
        }

        private static long longValue(
                Map<String, String> values,
                String key,
                long fallback) {
            return Long.parseLong(
                    values.getOrDefault(
                            key,
                            Long.toString(fallback)));
        }
    }
}
