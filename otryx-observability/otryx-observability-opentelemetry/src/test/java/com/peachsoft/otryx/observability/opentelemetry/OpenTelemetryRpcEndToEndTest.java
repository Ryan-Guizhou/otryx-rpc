package com.peachsoft.otryx.observability.opentelemetry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import com.peachsoft.otryx.api.ApplicationKey;
import com.peachsoft.otryx.api.RpcEndpoint;
import com.peachsoft.otryx.api.ServiceInstance;
import com.peachsoft.otryx.api.ServiceKey;
import com.peachsoft.otryx.codec.RpcCodecRegistry;
import com.peachsoft.otryx.core.ApplicationMembershipCoordinator;
import com.peachsoft.otryx.core.OtryxRpcClient;
import com.peachsoft.otryx.core.OtryxRpcServer;
import com.peachsoft.otryx.registry.ApplicationServiceResolver;
import com.peachsoft.otryx.registry.ManifestSnapshotCache;
import com.peachsoft.otryx.registry.ServiceMappingCache;
import com.peachsoft.otryx.registry.memory.MemoryApplicationRegistry;
import com.peachsoft.otryx.spi.ExtensionLoader;
import com.peachsoft.otryx.transport.RpcTransportFactory;
import com.peachsoft.otryx.transport.RpcTransportOptions;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;

/**
 * OpenTelemetry 真实 RPC Trace 穿透测试。
 *
 * @Author Ryan
 * @Version 1.0.0
 * @CreateTime 2026/9/29 16:40
 */
class OpenTelemetryRpcEndToEndTest {

    private static final String NAMESPACE = "tracing-tests";
    private static final Duration CONTROL_TIMEOUT = Duration.ofSeconds(3);

    @Test
    void realRpcShouldPreserveClientServerTraceParent() throws Exception {
        RecordingExporter exporter = new RecordingExporter();
        SdkTracerProvider tracerProvider = SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(exporter)).build();
        OpenTelemetrySdk openTelemetry = OpenTelemetrySdk.builder()
                .setTracerProvider(tracerProvider)
                .setPropagators(ContextPropagators.create(W3CTraceContextPropagator.getInstance()))
                .build();
        try (MemoryApplicationRegistry registry = new MemoryApplicationRegistry();
             ServiceMappingCache mappingCache = new ServiceMappingCache(
                     registry.serviceMappingRepository(), CONTROL_TIMEOUT, Duration.ZERO);
             ManifestSnapshotCache manifestCache = new ManifestSnapshotCache(registry.manifestRepository());
             ApplicationServiceResolver resolver = new ApplicationServiceResolver(
                     mappingCache, registry, manifestCache, CONTROL_TIMEOUT);
             ApplicationMembershipCoordinator provider = new ApplicationMembershipCoordinator(
                     new ApplicationKey(NAMESPACE, "tracing-provider"), "provider-1", registry,
                     registry.manifestRepository(), registry.serviceMappingRepository(), CONTROL_TIMEOUT);
             ApplicationMembershipCoordinator consumer = new ApplicationMembershipCoordinator(
                     new ApplicationKey(NAMESPACE, "tracing-consumer"), "consumer-1", registry,
                     registry.manifestRepository(), registry.serviceMappingRepository(), CONTROL_TIMEOUT)) {
            RpcCodecRegistry codecs = RpcCodecRegistry.fromSpi();
            RpcTransportFactory transportFactory = ExtensionLoader.getLoader(RpcTransportFactory.class)
                    .getDefaultExtension();
            OpenTelemetryRpcTracingBridge bridge = new OpenTelemetryRpcTracingBridge(openTelemetry);
            try (OtryxRpcServer server = OtryxRpcServer.builder()
                    .applicationMembership(provider)
                    .transportServer(transportFactory.createServer(RpcTransportOptions.DEFAULT))
                    .codecRegistry(codecs)
                    .bindEndpoint(new RpcEndpoint("127.0.0.1", 0))
                    .advertisedHost("127.0.0.1")
                    .tracingBridge(bridge)
                    .build()
                    .registerService(GreetingService.class, new GreetingServiceImpl(), "1.0.0", "default")) {
                server.start().toCompletableFuture().get(3, TimeUnit.SECONDS);
                try (OtryxRpcClient client = OtryxRpcClient.builder()
                        .applicationControlPlane(resolver, consumer, NAMESPACE)
                        .transportClient(transportFactory.createClient(RpcTransportOptions.DEFAULT))
                        .codecRegistry(codecs)
                        .tracingBridge(bridge)
                        .build()) {
                    client.membershipReady().toCompletableFuture().get(3, TimeUnit.SECONDS);
                    GreetingService service = client.refer(GreetingService.class, "1.0.0", "default");
                    awaitApplicationRoute(client);
                    assertEquals("hello peach", service.hello("peach"));
                    List<SpanData> spans = exporter.awaitSpans(2, Duration.ofSeconds(2));
                    SpanData clientSpan = spans.stream().filter(span -> span.getKind() == SpanKind.CLIENT)
                            .findFirst().orElseThrow();
                    SpanData serverSpan = spans.stream().filter(span -> span.getKind() == SpanKind.SERVER)
                            .findFirst().orElseThrow();
                    assertEquals(clientSpan.getTraceId(), serverSpan.getTraceId());
                    assertEquals(clientSpan.getSpanId(), serverSpan.getParentSpanId());
                    assertEquals(2, spans.size(), "Application readiness must not create synthetic RPC spans");
                }
            }
        } finally {
            tracerProvider.close();
        }
    }

    private static void awaitApplicationRoute(OtryxRpcClient client) throws Exception {
        // 只观察首刷完成，避免为等待应用就绪发起探测 RPC 而污染精确的 Trace 父子断言。
        Field directoriesField = OtryxRpcClient.class.getDeclaredField("directories");
        directoriesField.setAccessible(true);
        Map<?, ?> directories = (Map<?, ?>) directoriesField.get(client);
        Object directory = directories.get(new ServiceKey(GreetingService.class.getName(), "1.0.0", "default"));
        Method snapshot = directory.getClass().getDeclaredMethod("snapshot");
        snapshot.setAccessible(true);
        long started = System.nanoTime();
        while (((ServiceInstance[]) snapshot.invoke(directory)).length == 0
                && System.nanoTime() - started < CONTROL_TIMEOUT.toNanos()) {
            Thread.sleep(1L);
        }
        assertTrue(((ServiceInstance[]) snapshot.invoke(directory)).length > 0,
                "Application provider route did not become ready");
    }

    /** 测试服务。 */
    public interface GreetingService {
        /**
         * 返回问候语。
         *
         * @param name 名称
         * @return 问候语
         */
        String hello(String name);
    }

    /** 测试服务实现。 */
    public static final class GreetingServiceImpl
            implements GreetingService {
        @Override
        public String hello(String name) {
            return "hello " + name;
        }
    }

    /** 记录导出 Span 的测试 Exporter。 */
    private static final class RecordingExporter
            implements SpanExporter {

        private final List<SpanData> spans =
                new CopyOnWriteArrayList<>();

        @Override
        public CompletableResultCode export(
                Collection<SpanData> values) {
            spans.addAll(values);
            return CompletableResultCode.ofSuccess();
        }

        @Override
        public CompletableResultCode flush() {
            return CompletableResultCode.ofSuccess();
        }

        @Override
        public CompletableResultCode shutdown() {
            return CompletableResultCode.ofSuccess();
        }

        private List<SpanData> awaitSpans(
                int expected,
                Duration timeout) {
            long deadline =
                    System.nanoTime() + timeout.toNanos();
            while (spans.size() < expected
                    && System.nanoTime() < deadline) {
                try {
                    Thread.sleep(10L);
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(
                            "Interrupted while waiting for spans",
                            error);
                }
            }
            return List.copyOf(spans);
        }
    }
}
