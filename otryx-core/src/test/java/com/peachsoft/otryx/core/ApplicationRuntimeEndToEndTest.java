package com.peachsoft.otryx.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.peachsoft.otryx.api.ApplicationKey;
import com.peachsoft.otryx.api.ApplicationRole;
import com.peachsoft.otryx.api.RpcEndpoint;
import com.peachsoft.otryx.api.RpcTarget;
import com.peachsoft.otryx.api.RpcPeerIdentity;
import com.peachsoft.otryx.transport.RpcTransportResponse;
import com.peachsoft.otryx.transport.RpcTransportSecurityDescriptor;
import com.peachsoft.otryx.api.RpcMethodDescriptor;
import com.peachsoft.otryx.api.ServiceKey;
import com.peachsoft.otryx.api.RpcUnavailableException;
import com.peachsoft.otryx.observability.RpcDirectoryFailureReason;
import com.peachsoft.otryx.codec.RpcCodec;
import com.peachsoft.otryx.codec.RpcCodecRegistry;
import com.peachsoft.otryx.codec.RpcMethodCodec;
import com.peachsoft.otryx.registry.ApplicationServiceResolver;
import com.peachsoft.otryx.registry.ManifestSnapshotCache;
import com.peachsoft.otryx.registry.ServiceMappingCache;
import com.peachsoft.otryx.registry.ServiceMappingKey;
import com.peachsoft.otryx.registry.memory.MemoryApplicationManifestRepository;
import com.peachsoft.otryx.registry.memory.MemoryApplicationRegistry;
import com.peachsoft.otryx.registry.memory.MemoryServiceMappingRepository;
import com.peachsoft.otryx.protocol.RpcProtocolCodec;
import com.peachsoft.otryx.transport.RpcRequestHandler;
import com.peachsoft.otryx.transport.RpcTransportClient;
import com.peachsoft.otryx.transport.RpcTransportServer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * 通过真实 Core Client/Server、Wire v1 编解码与本地 Transport 验证应用级发现。
 *
 * <p>仅替换 Socket 为相同 JVM 的帧传递，不模拟业务层解析或返回成功响应。
 * 跨独立进程 Registry/Transport 和 Starter 的启动行为由各 Adapter 与 Quickstart 的专属验收覆盖。
 *
 * @Author OpenAI
 * @Version 1.0.0-SNAPSHOT
 * @CreateTime 2026/10/16 09:51
 */
class ApplicationRuntimeEndToEndTest {

    private static final String NAMESPACE = "production";
    private static final String VERSION = "1.0.0";
    private static final String GROUP = "default";
    private static final int PORT = 19090;
    private static final int CALLER_PORT = 19091;
    private static final byte CODEC_ID = 1;
    private static final int MAX_POLLS = 60;
    private static final long POLL_MILLIS = 100L;
    private static final Duration CONTROL_TIMEOUT = Duration.ofSeconds(3);

    @Test
    void diagnosticsAreLocalAndNeverCreateUnknownSubscriptions() throws Exception {
        try (Fixture fixture = new Fixture()) {
            ServiceKey key = key(Payments.class);
            RpcServiceDiagnosticSnapshot before = fixture.client.diagnose(key);
            assertEquals(RpcServiceDirectoryState.NOT_REFERENCED, before.directoryState());
            assertEquals(RpcDirectoryFailureReason.NONE, before.reason());

            fixture.server.registerService(Payments.class, new PaymentsImpl(), VERSION, GROUP);
            fixture.client.membershipReady().toCompletableFuture().get(3, TimeUnit.SECONDS);
            fixture.server.start().toCompletableFuture().get(3, TimeUnit.SECONDS);
            fixture.client.refer(Payments.class, VERSION, GROUP);
            awaitProviders(fixture, Payments.class);
            RpcServiceDiagnosticSnapshot local = fixture.client.diagnose(key);
            assertTrue(local.directoryState() == RpcServiceDirectoryState.HEALTHY
                    || local.directoryState() == RpcServiceDirectoryState.NOT_READY);
            assertEquals(RpcServiceDirectoryState.NOT_REFERENCED,
                    fixture.client.diagnose(key(Inventory.class)).directoryState());
            assertEquals(1, fixture.registry.lookup(fixture.consumerKey)
                    .toCompletableFuture().join().instances().size());
        }
    }

    @Test
    void threeContractsUseOneProviderMemberAndIndependentPureConsumerMember() throws Exception {
        try (Fixture f = new Fixture()) {
            f.server.registerService(Payments.class, new PaymentsImpl(), VERSION, GROUP);
            f.server.registerService(Inventory.class, new InventoryImpl(), VERSION, GROUP);
            f.server.registerService(Delivery.class, new DeliveryImpl(), VERSION, GROUP);
            f.client.membershipReady().toCompletableFuture().get(3, TimeUnit.SECONDS);
            assertEquals(1, f.registry.lookup(f.consumerKey).toCompletableFuture().join().instances().size());
            assertFalse(f.registry.lookup(f.consumerKey).toCompletableFuture()
                    .join().instances().getFirst().routableProvider());

            f.server.start().toCompletableFuture().get(3, TimeUnit.SECONDS);
            assertEquals(1, f.registry.lookup(f.providerKey).toCompletableFuture().join().instances().size());
            assertEquals(3, f.manifestsPublishedServices());

            Payments payments = f.client.refer(Payments.class, VERSION, GROUP);
            Inventory inventory = f.client.refer(Inventory.class, VERSION, GROUP);
            Delivery delivery = f.client.refer(Delivery.class, VERSION, GROUP);
            awaitProviders(f, Payments.class);
            awaitProviders(f, Inventory.class);
            awaitProviders(f, Delivery.class);

            assertEquals("paid:invoice", payments.pay("invoice"));
            assertEquals("stock:item", inventory.get("item"));
            assertEquals("shipped:box", delivery.ship("box"));
            assertEquals(3, f.transport.requests.get());
            assertEquals(1, f.registry.lookup(f.providerKey).toCompletableFuture().join().instances().size());
        }
    }

    @Test
    void colocatedProviderAndConsumerShareOneBothApplicationMember() throws Exception {
        try (Fixture f = new Fixture(true)) {
            f.server.registerService(Payments.class, new PaymentsImpl(), VERSION, GROUP);
            f.server.start().toCompletableFuture().get(3, TimeUnit.SECONDS);
            f.client.membershipReady().toCompletableFuture().get(3, TimeUnit.SECONDS);
            assertEquals(1, f.registry.lookup(f.providerKey)
                    .toCompletableFuture().join().instances().size());
            assertEquals(ApplicationRole.BOTH, f.registry.lookup(f.providerKey)
                    .toCompletableFuture().join().instances().getFirst().role());
            Payments service = f.client.refer(Payments.class, VERSION, GROUP);
            awaitProviders(f, Payments.class);
            assertEquals("paid:co-located", service.pay("co-located"));
            f.client.shutdown(new RpcShutdownOptions(Duration.ofSeconds(2)))
                    .toCompletableFuture().get(3, TimeUnit.SECONDS);
            assertEquals(ApplicationRole.PROVIDER, f.registry.lookup(f.providerKey)
                    .toCompletableFuture().join().instances().getFirst().role());
        }
    }

    @Test
    void providerShutdownRemovesRoutesButLeavesConsumerMember() throws Exception {
        try (Fixture f = new Fixture()) {
            f.server.registerService(Payments.class, new PaymentsImpl(), VERSION, GROUP);
            f.server.start().toCompletableFuture().get(3, TimeUnit.SECONDS);
            Payments api = f.client.refer(Payments.class, VERSION, GROUP);
            awaitProviders(f, Payments.class);
            assertEquals("paid:first", api.pay("first"));
            f.server.shutdown(new RpcShutdownOptions(Duration.ofSeconds(3)))
                    .toCompletableFuture().get(4, TimeUnit.SECONDS);
            assertTrue(f.mapping.lookup(new ServiceMappingKey(NAMESPACE, key(Payments.class)))
                    .toCompletableFuture().join().applications().isEmpty());
            assertTrue(f.registry.lookup(f.providerKey).toCompletableFuture().join().instances().isEmpty());
            assertEquals(1, f.registry.lookup(f.consumerKey).toCompletableFuture().join().instances().size());
        }
    }

    @Test
    void failedProviderStartRollsBackMappingAndKeepsConsumerApplication() throws Exception {
        try (Fixture f = new Fixture()) {
            f.server.registerService(Payments.class, new PaymentsImpl(), VERSION, GROUP);
            f.transport.failNextStart = true;
            assertThrows(java.util.concurrent.ExecutionException.class,
                    () -> f.server.start().toCompletableFuture().get(3, TimeUnit.SECONDS));
            assertTrue(f.mapping.lookup(new ServiceMappingKey(NAMESPACE, key(Payments.class)))
                    .toCompletableFuture().join().applications().isEmpty());
            assertTrue(f.registry.lookup(f.providerKey).toCompletableFuture().join().instances().isEmpty());
            assertEquals(1, f.registry.lookup(f.consumerKey).toCompletableFuture().join().instances().size());
        }
    }

    private static void awaitProviders(Fixture fixture, Class<?> api) throws InterruptedException {
        ServiceKey key = key(api);
        String schema = com.peachsoft.otryx.api.RpcSchemaFingerprint.serviceFingerprint(key, api);
        ServiceMappingKey mappingKey = new ServiceMappingKey(NAMESPACE, key);
        for (int poll = 0; poll < MAX_POLLS; poll++) {
            if (fixture.resolver.current(mappingKey, schema)
                    .map(value -> !value.providers().isEmpty()).orElse(false)) {
                Thread.sleep(POLL_MILLIS);
                return;
            }
            Thread.sleep(POLL_MILLIS);
        }
        throw new AssertionError("Application provider route not ready: " + key);
    }

    private static ServiceKey key(Class<?> api) {
        return new ServiceKey(api.getName(), VERSION, GROUP);
    }

    /** 支付 RPC 契约。 */
    public interface Payments {
        /** 执行模拟支付。 */
        String pay(String reference);
    }

    /** 库存 RPC 契约。 */
    public interface Inventory {
        /** 查询模拟库存。 */
        String get(String reference);
    }

    /** 物流 RPC 契约。 */
    public interface Delivery {
        /** 模拟发货。 */
        String ship(String reference);
    }

    /** 测试环境真正执行的支付业务实现。 */
    public static final class PaymentsImpl implements Payments {
        @Override
        public String pay(String reference) {
            return "paid:" + reference;
        }
    }

    /** 测试环境真正执行的库存业务实现。 */
    public static final class InventoryImpl implements Inventory {
        @Override
        public String get(String reference) {
            return "stock:" + reference;
        }
    }

    /** 测试环境真正执行的物流业务实现。 */
    public static final class DeliveryImpl implements Delivery {
        @Override
        public String ship(String reference) {
            return "shipped:" + reference;
        }
    }

    private static final class Fixture implements AutoCloseable {
        private final ApplicationKey providerKey = new ApplicationKey(NAMESPACE, "backend");
        private final ApplicationKey consumerKey;
        private final MemoryApplicationRegistry registry = new MemoryApplicationRegistry();
        private final MemoryApplicationManifestRepository manifests = new MemoryApplicationManifestRepository();
        private final MemoryServiceMappingRepository mapping = new MemoryServiceMappingRepository();
        private final ServiceMappingCache serviceCache =
                new ServiceMappingCache(mapping, Duration.ofSeconds(2), Duration.ZERO);
        private final ManifestSnapshotCache manifestCache = new ManifestSnapshotCache(manifests);
        private final ApplicationServiceResolver resolver = new ApplicationServiceResolver(
                serviceCache, registry, manifestCache, Duration.ofSeconds(2));
        private final LoopbackTransport transport = new LoopbackTransport();
        private final ApplicationMembershipCoordinator provider = new ApplicationMembershipCoordinator(
                providerKey, "backend-1", registry.registrar().orElseThrow(), manifests, mapping, CONTROL_TIMEOUT);
        private final ApplicationMembershipCoordinator consumer;
        private final RpcCodecRegistry codecs = RpcCodecRegistry.of(new TestCodec());
        private final OtryxRpcServer server = OtryxRpcServer.builder()
                .securityProfile(com.peachsoft.otryx.api.RpcSecurityProfile.TRUSTED_DEVELOPMENT)
                .applicationMembership(provider)
                .codecRegistry(codecs)
                .transportServer(transport)
                .bindEndpoint(new RpcEndpoint("127.0.0.1", PORT))
                .build();
        private final OtryxRpcClient client;

        private Fixture() {
            this(false);
        }

        private Fixture(boolean colocated) {
            consumerKey = colocated ? providerKey : new ApplicationKey(NAMESPACE, "frontend");
            consumer = colocated ? provider : new ApplicationMembershipCoordinator(
                    consumerKey, "frontend-1", registry.registrar().orElseThrow(),
                    manifests, mapping, CONTROL_TIMEOUT);
            client = OtryxRpcClient.builder()
                .securityProfile(com.peachsoft.otryx.api.RpcSecurityProfile.TRUSTED_DEVELOPMENT)
                    .applicationControlPlane(resolver, consumer, NAMESPACE)
                    .codecRegistry(codecs)
                    .transportClient(transport)
                    .timeout(Duration.ofSeconds(2))
                    .build();
        }

        private int manifestsPublishedServices() {
            return manifests.lookup(providerKey, registry.lookup(providerKey)
                            .toCompletableFuture().join().instances().getFirst().manifestRevision())
                    .toCompletableFuture().join().orElseThrow().services().size();
        }

        @Override
        public void close() {
            try {
                client.shutdown(new RpcShutdownOptions(Duration.ofSeconds(3)))
                        .toCompletableFuture().get(4, TimeUnit.SECONDS);
                server.shutdown(new RpcShutdownOptions(Duration.ofSeconds(3)))
                        .toCompletableFuture().get(4, TimeUnit.SECONDS);
                provider.shutdown().toCompletableFuture().get(4, TimeUnit.SECONDS);
                consumer.shutdown().toCompletableFuture().get(4, TimeUnit.SECONDS);
            } catch (Exception error) {
                throw new IllegalStateException("Test runtime cleanup failed", error);
            } finally {
                resolver.close();
                manifestCache.close();
                serviceCache.close();
                registry.close();
                manifests.close();
            }
        }
    }

    private static final class LoopbackTransport implements RpcTransportServer, RpcTransportClient {
        private final AtomicReference<RpcRequestHandler> handler = new AtomicReference<>();
        private final AtomicLong sequence = new AtomicLong();
        private final AtomicLong requests = new AtomicLong();
        private volatile boolean failNextStart;

        @Override
        public CompletionStage<RpcEndpoint> start(RpcEndpoint bind, RpcRequestHandler requestHandler) {
            if (failNextStart) {
                failNextStart = false;
                return CompletableFuture.failedFuture(new IllegalStateException("Synthetic listener failure"));
            }
            handler.set(requestHandler);
            return CompletableFuture.completedFuture(bind);
        }

        @Override
        public CompletionStage<RpcTransportResponse> request(RpcTarget target, byte[] frame, Duration timeout) {
            RpcRequestHandler current = handler.get();
            if (current == null) {
                return CompletableFuture.failedFuture(new RpcUnavailableException("Listener not available"));
            }
            requests.incrementAndGet();
            RpcProtocolCodec.writeRequestId(frame, sequence.incrementAndGet());
            return current.handle(new RpcEndpoint("127.0.0.1", CALLER_PORT), frame)
                    .thenApply(response -> new RpcTransportResponse(response, RpcPeerIdentity.anonymous()));
        }

        @Override
        public RpcTransportSecurityDescriptor securityDescriptor() {
            return RpcTransportSecurityDescriptor.unknown();
        }

        @Override
        public CompletionStage<Void> drain(Duration timeout) {
            handler.set(null);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void close() {
            handler.set(null);
        }
    }

    private static final class TestCodec implements RpcCodec {
        @Override
        public byte code() {
            return CODEC_ID;
        }

        @Override
        public byte[] encode(Object value) {
            return value.toString().getBytes(StandardCharsets.UTF_8);
        }

        @Override
        public <T> T decode(byte[] bytes, Class<T> type) {
            throw new UnsupportedOperationException("Bind-specific codec only");
        }

        @Override
        public RpcMethodCodec bind(RpcMethodDescriptor descriptor) {
            return new RpcMethodCodec() {
                @Override
                public byte codecId() {
                    return CODEC_ID;
                }

                @Override
                public byte[] encodeArguments(Object[] arguments) {
                    return arguments[0].toString().getBytes(StandardCharsets.UTF_8);
                }

                @Override
                public Object[] decodeArguments(byte[] payload) {
                    return new Object[] {new String(payload, StandardCharsets.UTF_8)};
                }

                @Override
                public byte[] encodeResult(Object value) {
                    return encode(value);
                }

                @Override
                public Object decodeResult(byte[] payload) {
                    return new String(payload, StandardCharsets.UTF_8);
                }
            };
        }
    }
}
