package com.peachsoft.otryx.compare.otryx;

import com.peachsoft.otryx.api.ApplicationInstance;
import com.peachsoft.otryx.api.ApplicationKey;
import com.peachsoft.otryx.api.RpcEndpoint;
import com.peachsoft.otryx.api.RpcSchemaFingerprint;
import com.peachsoft.otryx.api.RpcUnavailableException;
import com.peachsoft.otryx.api.ServiceKey;
import com.peachsoft.otryx.codec.RpcCodecIds;
import com.peachsoft.otryx.codec.RpcCodecRegistry;
import com.peachsoft.otryx.compare.ComparisonHarness;
import com.peachsoft.otryx.core.ApplicationMembershipCoordinator;
import com.peachsoft.otryx.core.OtryxRpcClient;
import com.peachsoft.otryx.core.OtryxRpcServer;
import com.peachsoft.otryx.registry.ApplicationMetadataBackend;
import com.peachsoft.otryx.registry.ApplicationRegistry;
import com.peachsoft.otryx.registry.ApplicationRegistryFactory;
import com.peachsoft.otryx.registry.ApplicationServiceResolver;
import com.peachsoft.otryx.registry.ManifestSnapshotCache;
import com.peachsoft.otryx.registry.RegistryOptions;
import com.peachsoft.otryx.registry.ServiceMappingCache;
import com.peachsoft.otryx.registry.ServiceMappingKey;
import com.peachsoft.otryx.registry.VerifiedProviderSnapshot;
import com.peachsoft.otryx.registry.etcd.EtcdApplicationRegistryFactory;
import com.peachsoft.otryx.spi.ExtensionLoader;
import com.peachsoft.otryx.transport.RpcTransportFactory;
import com.peachsoft.otryx.transport.RpcTransportOptions;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 独立 JVM 中使用共享 Etcd 应用控制面的 OTRYX TCP+Fory Echo Provider/Consumer。
 *
 * <p>两端显式配置同一 Etcd Endpoint 和隔离 Namespace，分别注册真实 Provider 与纯 Consumer 成员。
 * Manifest、Mapping、成员和首次 RPC 探测均在测量前就绪；单次 RPC 只读取本地路由缓存。
 * 该入口不能与 Dubbo 依赖打包在同一个 JVM 中。
 *
 * @Author Ryan
 * @Version 1.0.0
 * @CreateTime 2026/10/10 14:27
 */
public final class OtryxComparisonMain {

    private static final String VERSION = "1.0.0";
    private static final String GROUP = "comparison";
    private static final String PROVIDER_APPLICATION = "comparison-provider";
    private static final String CONSUMER_APPLICATION = "comparison-consumer";
    private static final String ENV_REGISTRY_ENDPOINT = "RPC_COMPARISON_REGISTRY_ENDPOINT";
    private static final String ENV_REGISTRY_NAMESPACE = "RPC_COMPARISON_REGISTRY_NAMESPACE";
    private static final String ENV_ADVERTISED_HOST = "RPC_COMPARISON_ADVERTISED_HOST";
    private static final String LOOPBACK_HOST = "127.0.0.1";
    private static final String BIND_ALL_HOST = "0.0.0.0";
    private static final String TRANSPORT_EXTENSION = "vertx";
    private static final int MAX_PORT = 65535;
    private static final long READY_TIMEOUT_SECONDS = 15L;
    private static final long READY_POLL_MILLIS = 20L;
    private static final long CLOSE_TIMEOUT_SECONDS = 4L;
    private static final Duration CONTROL_TIMEOUT = Duration.ofSeconds(3);
    private static final ServiceKey SERVICE_KEY = new ServiceKey(CompareEchoService.class.getName(), VERSION, GROUP);
    private static final String SCHEMA = RpcSchemaFingerprint.serviceFingerprint(SERVICE_KEY, CompareEchoService.class);
    private static final byte[] PROBE_PAYLOAD = new byte[0];

    private OtryxComparisonMain() {
    }

    /**
     * 启动 Provider 或执行 Consumer；必须显式提供共享 Etcd Endpoint 和 Namespace 环境变量。
     *
     * @param args provider port 或 client host port concurrency warmup_seconds
     *             duration_seconds payload_bytes output.json；client host/port 必须匹配真实发现的唯一 Provider
     * @throws Exception 应用控制面、RPC 或负载执行失败
     */
    public static void main(String[] args) throws Exception {
        if (args.length == 2 && "provider".equals(args[0])) {
            provider(Integer.parseInt(args[1]));
            return;
        }
        if (args.length == 8 && "client".equals(args[0])) {
            client(args);
            return;
        }
        throw new IllegalArgumentException(
                "Usage: provider <port> OR client <host> <port> "
                        + "<concurrency> <warmup_seconds> <duration_seconds> "
                        + "<payload_bytes> <output.json>");
    }

    private static void provider(int port) throws Exception {
        if (port < 1 || port > MAX_PORT) {
            throw new IllegalArgumentException("Invalid provider port");
        }
        RpcTransportFactory transport = transportFactory();
        RpcCodecRegistry codecs = RpcCodecRegistry.fromSpi();
        try (ControlPlane controlPlane = new ControlPlane(PROVIDER_APPLICATION);
             OtryxRpcServer server = OtryxRpcServer.builder()
                     .applicationMembership(controlPlane.membership)
                     .transportServer(transport.createServer(options()))
                     .codecRegistry(codecs)
                     .bindEndpoint(new RpcEndpoint(BIND_ALL_HOST, port))
                     .advertisedHost(System.getenv().getOrDefault(ENV_ADVERTISED_HOST, LOOPBACK_HOST))
                     .maxConcurrent(20000)
                     .build()
                     .registerService(
                             CompareEchoService.class,
                             (CompareEchoService) input -> input,
                             VERSION, GROUP)) {
            Thread shutdownHook = Thread.ofPlatform().name("comparison-provider-shutdown").unstarted(() -> {
                server.close();
                controlPlane.close();
            });
            Runtime.getRuntime().addShutdownHook(shutdownHook);
            try {
                server.start().toCompletableFuture().get(READY_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                System.out.printf(
                        "READY framework=otryx protocol=tcp serializer=fory-native port=%d%n", port);
                new CountDownLatch(1).await();
            } finally {
                try {
                    Runtime.getRuntime().removeShutdownHook(shutdownHook);
                } catch (IllegalStateException shuttingDown) {
                    // JVM 已启动关闭流程时，由已注册的 Hook 执行同一幂等清理。
                }
            }
        }
    }

    private static void client(String[] args) throws Exception {
        RpcEndpoint expectedEndpoint = new RpcEndpoint(args[1], Integer.parseInt(args[2]));
        int concurrency = Integer.parseInt(args[3]);
        RpcTransportFactory transport = transportFactory();
        try (ControlPlane controlPlane = new ControlPlane(CONSUMER_APPLICATION);
             OtryxRpcClient client = OtryxRpcClient.builder()
                     .applicationControlPlane(controlPlane.resolver, controlPlane.membership, controlPlane.namespace)
                     .transportClient(transport.createClient(options()))
                     .codecRegistry(RpcCodecRegistry.fromSpi())
                     .responseCompletionQueueCapacity(Math.max(4096, concurrency * 2))
                     .timeout(Duration.ofSeconds(15))
                     .build()) {
            CompareEchoService service = client.refer(CompareEchoService.class, VERSION, GROUP);
            controlPlane.awaitReady(client, service, expectedEndpoint);
            ComparisonHarness.run("otryx", "tcp-v1", "fory-native", args, service::echo);
        }
    }

    private static RpcTransportFactory transportFactory() {
        return ExtensionLoader.getLoader(RpcTransportFactory.class).getExtension(TRANSPORT_EXTENSION);
    }

    private static RpcTransportOptions options() {
        return new RpcTransportOptions(
                4096,
                16 * 1024 * 1024,
                16 * 1024 * 1024,
                Duration.ofSeconds(3),
                Duration.ofSeconds(3),
                Set.of(RpcCodecIds.FORY_NATIVE),
                4);
    }

    private static String requiredEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required for the shared application control plane");
        }
        return value.trim();
    }

    private static final class ControlPlane implements AutoCloseable {
        private final String namespace;
        private final ApplicationRegistry registry;
        private final ServiceMappingCache mappingCache;
        private final ManifestSnapshotCache manifestCache;
        private final ApplicationServiceResolver resolver;
        private final ApplicationMembershipCoordinator membership;
        private final AtomicBoolean closed = new AtomicBoolean();

        private ControlPlane(String applicationName) {
            namespace = requiredEnvironment(ENV_REGISTRY_NAMESPACE);
            ApplicationKey application = new ApplicationKey(namespace, applicationName);
            String endpoint = requiredEnvironment(ENV_REGISTRY_ENDPOINT);
            registry = ExtensionLoader.getLoader(ApplicationRegistryFactory.class)
                    .getExtension(EtcdApplicationRegistryFactory.EXTENSION_NAME)
                    .create(new RegistryOptions(List.of(endpoint), namespace, Map.of()));
            if (!(registry instanceof ApplicationMetadataBackend metadata)) {
                registry.close();
                throw new IllegalStateException("Comparison application registry has no metadata backend");
            }
            mappingCache = new ServiceMappingCache(
                    metadata.serviceMappingRepository(), CONTROL_TIMEOUT, Duration.ZERO);
            manifestCache = new ManifestSnapshotCache(metadata.manifestRepository());
            resolver = new ApplicationServiceResolver(mappingCache, registry, manifestCache, CONTROL_TIMEOUT);
            membership = new ApplicationMembershipCoordinator(application, UUID.randomUUID().toString(),
                    registry.registrar().orElseThrow(), metadata.manifestRepository(),
                    metadata.serviceMappingRepository(), CONTROL_TIMEOUT);
        }

        private void awaitReady(OtryxRpcClient client, CompareEchoService service, RpcEndpoint expected)
                throws Exception {
            client.membershipReady().toCompletableFuture().get(READY_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            ServiceMappingKey key = new ServiceMappingKey(namespace, SERVICE_KEY);
            long started = System.nanoTime();
            CompletionException lastFailure = null;
            while (System.nanoTime() - started < TimeUnit.SECONDS.toNanos(READY_TIMEOUT_SECONDS)) {
                Optional<VerifiedProviderSnapshot> snapshot = resolver.current(key, SCHEMA);
                if (snapshot.isPresent() && !snapshot.orElseThrow().providers().isEmpty()) {
                    List<ApplicationInstance> providers = snapshot.orElseThrow().providers();
                    if (providers.size() != 1 || !expected.equals(providers.getFirst().rpcEndpoint())) {
                        throw new IllegalStateException(
                                "Discovered comparison provider does not match requested endpoint");
                    }
                    try {
                        if (service.echo(PROBE_PAYLOAD).length != PROBE_PAYLOAD.length) {
                            throw new IllegalStateException("Comparison readiness probe returned an invalid echo");
                        }
                        return;
                    } catch (CompletionException error) {
                        if (!(error.getCause() instanceof RpcUnavailableException)) {
                            throw error;
                        }
                        lastFailure = error;
                    }
                }
                Thread.sleep(READY_POLL_MILLIS);
            }
            throw new IllegalStateException("Shared application provider route did not become ready", lastFailure);
        }

        @Override
        public void close() {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            try {
                membership.shutdown().toCompletableFuture().get(CLOSE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while closing comparison application membership", error);
            } catch (Exception error) {
                throw new IllegalStateException("Comparison application membership cleanup failed", error);
            } finally {
                resolver.close();
                manifestCache.close();
                mappingCache.close();
                registry.close();
            }
        }
    }
}
