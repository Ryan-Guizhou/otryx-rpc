package com.peachsoft.otryx.core;

import com.peachsoft.otryx.api.ApplicationKey;
import com.peachsoft.otryx.api.ExportedService;
import com.peachsoft.otryx.api.RpcCertificateBinding;
import com.peachsoft.otryx.api.RpcEndpoint;
import com.peachsoft.otryx.api.RpcMethodDescriptor;
import com.peachsoft.otryx.api.RpcPeerIdentity;
import com.peachsoft.otryx.api.RpcStatus;
import com.peachsoft.otryx.api.RpcTarget;
import com.peachsoft.otryx.api.ServiceKey;
import com.peachsoft.otryx.codec.RpcBoundedResultMethodCodec;
import com.peachsoft.otryx.codec.RpcCodec;
import com.peachsoft.otryx.codec.RpcMethodCodec;
import com.peachsoft.otryx.codec.RpcCodecRegistry;
import com.peachsoft.otryx.codec.RpcCodecSecurityDescriptor;
import com.peachsoft.otryx.observability.RpcSecurityMode;
import com.peachsoft.otryx.protocol.RpcProtocolCodec;
import com.peachsoft.otryx.registry.ApplicationServiceResolver;
import com.peachsoft.otryx.registry.ManifestSnapshotCache;
import com.peachsoft.otryx.registry.ServiceMappingCache;
import com.peachsoft.otryx.registry.ServiceMappingRepository;
import com.peachsoft.otryx.registry.memory.MemoryApplicationRegistry;
import com.peachsoft.otryx.transport.RpcRequestHandler;
import com.peachsoft.otryx.transport.RpcTransportClient;
import com.peachsoft.otryx.transport.RpcTransportResponse;
import com.peachsoft.otryx.transport.RpcTransportSecurityDescriptor;
import com.peachsoft.otryx.transport.RpcTransportServer;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.UnaryOperator;

/**
 * Core 安全边界的确定性夹具；连接身份由 fake Transport 提供，不冒充真实 TLS 验收。
 *
 * @Author OpenAI
 * @Version 1.0.0-SNAPSHOT
 * @CreateTime 2026/10/19 11:18
 */
final class RpcSecurityTestFixtures {
    static final String NAMESPACE = "security-tests";
    static final ApplicationKey CONSUMER = new ApplicationKey(NAMESPACE, "consumer");
    static final ApplicationKey PROVIDER = new ApplicationKey(NAMESPACE, "provider");
    static final RpcEndpoint ENDPOINT = new RpcEndpoint("127.0.0.1", 19091);
    static final RpcEndpoint REMOTE = new RpcEndpoint("127.0.0.1", 29100);

    private RpcSecurityTestFixtures() {
    }

    static RpcCertificateBinding binding(ApplicationKey application, Instant expiry) {
        return new RpcCertificateBinding("a".repeat(64), application, expiry);
    }

    static RpcPeerIdentity peer(ApplicationKey application, TestClock clock, Duration lifetime) {
        Instant now = clock.instant();
        return RpcPeerIdentity.verifiedMutualTls(binding(application, now.plus(lifetime)),
                now.minusSeconds(60), now.plusSeconds(3600), now, clock.nanos.get());
    }

    static RpcTransportSecurityDescriptor descriptor(ApplicationKey application, TestClock clock) {
        return new RpcTransportSecurityDescriptor(RpcTransportSecurityDescriptor.State.EFFECTIVE,
                RpcSecurityMode.MTLS, Optional.of(binding(application, clock.instant().plusSeconds(3600))),
                EnumSet.allOf(RpcTransportSecurityDescriptor.Capability.class), Set.of((byte) 1));
    }

    static byte[] success(byte[] frame) {
        var request = RpcProtocolCodec.view(frame);
        return RpcProtocolCodec.encodeResponse((byte) 1, RpcStatus.OK, request.requestId(),
                request.serviceId(), request.methodId(), "ok".getBytes(StandardCharsets.UTF_8));
    }

    static final class TestClock extends Clock {
        final AtomicReference<Instant> wall = new AtomicReference<>(Instant.parse("2030-01-01T00:00:00Z"));
        final AtomicLong nanos = new AtomicLong(1000L);

        void advance(Duration elapsed) {
            wall.updateAndGet(value -> value.plus(elapsed));
            nanos.addAndGet(elapsed.toNanos());
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            if (!ZoneOffset.UTC.equals(zone)) {
                throw new IllegalArgumentException("Test identity clock uses UTC");
            }
            return this;
        }

        @Override
        public Instant instant() {
            return wall.get();
        }
    }

    static class TestCodec implements RpcCodec {
        final AtomicInteger argumentDecodes = new AtomicInteger();
        final AtomicInteger resultDecodes = new AtomicInteger();
        Runnable decodeArguments = () -> { };
        Runnable decodeResult = () -> { };
        RpcCodecSecurityDescriptor descriptor = new RpcCodecSecurityDescriptor(
                RpcCodecSecurityDescriptor.TypePolicy.EXPLICIT_ALLOWLIST, true, true, 1024, 16, 4096);

        @Override
        public byte code() {
            return 1;
        }

        @Override
        public RpcCodecSecurityDescriptor securityDescriptor() {
            return descriptor;
        }

        @Override
        public byte[] encode(Object value) {
            return new byte[] {1};
        }

        /**
         * 测试受信方法级 Codec：真实固定一字节结果直接写入有界输出流，
         * 不使用默认先整块数组序列化的兼容适配路径。
         */
        @Override
        public RpcMethodCodec bind(RpcMethodDescriptor descriptor) {
            RpcMethodCodec baseline = RpcCodec.super.bind(descriptor);
            return new RpcBoundedResultMethodCodec() {
                @Override
                public byte codecId() {
                    return baseline.codecId();
                }

                @Override
                public RpcCodecSecurityDescriptor securityDescriptor() {
                    return baseline.securityDescriptor();
                }

                @Override
                public byte[] encodeArguments(Object[] arguments) {
                    return baseline.encodeArguments(arguments);
                }

                @Override
                public Object[] decodeArguments(byte[] payload) {
                    return baseline.decodeArguments(payload);
                }

                @Override
                public byte[] encodeResult(Object value) {
                    return baseline.encodeResult(value);
                }

                @Override
                public void encodeResultTo(Object value, OutputStream output) throws IOException {
                    output.write(1);
                }

                @Override
                public Object decodeResult(byte[] payload) {
                    return baseline.decodeResult(payload);
                }
            };
        }

        @Override
        public <T> T decode(byte[] payload, Class<T> type) {
            if (type == Object[].class) {
                argumentDecodes.incrementAndGet();
                decodeArguments.run();
                return type.cast(new Object[0]);
            }
            resultDecodes.incrementAndGet();
            decodeResult.run();
            return type.cast("ok");
        }
    }

    @FunctionalInterface
    interface Request {
        CompletionStage<RpcTransportResponse> request(RpcTarget target, byte[] frame, Duration timeout);
    }

    static final class ClientTransport implements RpcTransportClient {
        final AtomicInteger requests = new AtomicInteger();
        final AtomicInteger closes = new AtomicInteger();
        RpcTransportSecurityDescriptor descriptor;
        Request responder;

        ClientTransport(TestClock clock) {
            descriptor = descriptor(CONSUMER, clock);
            responder = (target, frame, timeout) -> CompletableFuture.completedFuture(
                    new RpcTransportResponse(success(frame), peer(target.application(), clock, Duration.ofMinutes(1))));
        }

        @Override
        public RpcTransportSecurityDescriptor securityDescriptor() {
            return descriptor;
        }

        @Override
        public CompletionStage<RpcTransportResponse> request(RpcTarget target, byte[] frame, Duration timeout) {
            requests.incrementAndGet();
            return responder.request(target, frame, timeout);
        }

        @Override
        public void close() {
            closes.incrementAndGet();
        }
    }

    static final class ServerTransport implements RpcTransportServer {
        final AtomicInteger starts = new AtomicInteger();
        final AtomicInteger closes = new AtomicInteger();
        RpcTransportSecurityDescriptor descriptor;
        RpcRequestHandler handler;

        ServerTransport(TestClock clock) {
            descriptor = descriptor(PROVIDER, clock);
        }

        @Override
        public RpcTransportSecurityDescriptor securityDescriptor() {
            return descriptor;
        }

        @Override
        public CompletionStage<RpcEndpoint> start(RpcEndpoint bind, RpcRequestHandler requestHandler) {
            starts.incrementAndGet();
            handler = requestHandler;
            return CompletableFuture.completedFuture(bind);
        }

        CompletionStage<byte[]> call(RpcPeerIdentity peer, byte[] request, Executor dispatcher) {
            return handler.handle(REMOTE, peer, request, dispatcher);
        }

        @Override
        public void close() {
            closes.incrementAndGet();
        }
    }

    static final class ControlPlane implements AutoCloseable {
        final TestClock clock = new TestClock();
        final MemoryApplicationRegistry registry = new MemoryApplicationRegistry();
        final ServiceMappingCache mappings;
        final ManifestSnapshotCache manifests;
        final ApplicationServiceResolver resolver;
        final ApplicationMembershipCoordinator consumer;
        final ApplicationMembershipCoordinator provider;

        ControlPlane() {
            this(UnaryOperator.identity());
        }

        ControlPlane(UnaryOperator<ServiceMappingRepository> mappingSource) {
            mappings = new ServiceMappingCache(mappingSource.apply(registry.serviceMappingRepository()),
                    Duration.ofSeconds(3), Duration.ZERO);
            manifests = new ManifestSnapshotCache(registry.manifestRepository());
            resolver = new ApplicationServiceResolver(mappings, registry, manifests, Duration.ofSeconds(3));
            consumer = membership(CONSUMER);
            provider = membership(PROVIDER);
        }

        ApplicationMembershipCoordinator membership(ApplicationKey application) {
            return new ApplicationMembershipCoordinator(application, application.applicationName(), registry,
                    registry.manifestRepository(), registry.serviceMappingRepository(), Duration.ofSeconds(3));
        }

        OtryxRpcClient.Builder clientBuilder(ClientTransport transport, RpcCodecRegistry codecs) {
            return OtryxRpcClient.builder().applicationControlPlane(resolver, consumer, NAMESPACE)
                    .transportClient(transport).codecRegistry(codecs).identityClock(clock, clock.nanos::get);
        }

        OtryxRpcServer.Builder serverBuilder(ServerTransport transport, RpcCodecRegistry codecs) {
            return OtryxRpcServer.builder().applicationMembership(provider).transportServer(transport)
                    .codecRegistry(codecs).bindEndpoint(ENDPOINT).identityClock(clock, clock.nanos::get);
        }

        void publish(Class<?> api) {
            ServiceKey service = new ServiceKey(api.getName(), "1.0.0", "default");
            provider.prepareProvider(List.of(ExportedService.fromContract(service, api))).toCompletableFuture().join();
            provider.providerReady(ENDPOINT).toCompletableFuture().join();
        }

        @Override
        public void close() throws Exception {
            consumer.shutdown().toCompletableFuture().get(5, TimeUnit.SECONDS);
            provider.shutdown().toCompletableFuture().get(5, TimeUnit.SECONDS);
            resolver.close();
            manifests.close();
            mappings.close();
            registry.close();
        }
    }
}
