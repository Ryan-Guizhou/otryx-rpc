package com.peachsoft.otryx.core;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.peachsoft.otryx.api.OtryxRpcExecution;
import com.peachsoft.otryx.api.RpcEndpoint;
import com.peachsoft.otryx.api.RpcExecutionMode;
import com.peachsoft.otryx.api.ApplicationInstance;
import com.peachsoft.otryx.api.ServiceKey;
import com.peachsoft.otryx.codec.RpcCodec;
import com.peachsoft.otryx.codec.RpcCodecRegistry;
import com.peachsoft.otryx.registry.ApplicationRegistrar;
import com.peachsoft.otryx.registry.ServiceMappingKey;
import com.peachsoft.otryx.transport.RpcRequestHandler;
import com.peachsoft.otryx.transport.RpcTransportServer;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * 验证 Provider 启动与服务绑定的基本生命周期。
 *
 * @Author Ryan
 * @Version 1.0.0
 * @CreateTime 2026/9/23 10:51
 */
public class OtryxRpcServerStartTest {

    private static final Duration CONTROL_TIMEOUT = Duration.ofSeconds(3);
    private final ApplicationTestControlPlane controlPlane = new ApplicationTestControlPlane();

    @AfterEach
    void closeApplicationControlPlane() {
        controlPlane.close();
    }

    @Test
    void shouldRejectDirectExecutionUnlessExplicitlyEnabled() {
        RpcTransportServer transport = new TestTransportServer();
        RpcCodecRegistry codecs =
                RpcCodecRegistry.of(new NoopCodec());

        OtryxRpcServer safeDefault = OtryxRpcServer.builder()
                .securityProfile(com.peachsoft.otryx.api.RpcSecurityProfile.TRUSTED_DEVELOPMENT)
                .applicationMembership(controlPlane.membership())
                .transportServer(transport)
                .codecRegistry(codecs)
                .bindEndpoint(new RpcEndpoint("127.0.0.1", 19090))
                .build();
        try {
            assertThrows(
                    IllegalStateException.class,
                    () -> safeDefault.registerService(
                            DirectService.class,
                            new DirectServiceImpl(),
                            "1.0.0",
                            "default"));
        } finally {
            safeDefault.close();
        }

        OtryxRpcServer explicitlyEnabled = OtryxRpcServer.builder()
                .securityProfile(com.peachsoft.otryx.api.RpcSecurityProfile.TRUSTED_DEVELOPMENT)
                .applicationMembership(controlPlane.membership())
                .transportServer(new TestTransportServer())
                .codecRegistry(codecs)
                .bindEndpoint(new RpcEndpoint("127.0.0.1", 19091))
                .executionOptions(new RpcProviderExecutionOptions(
                        true,
                        1,
                        16))
                .build();
        try {
            assertDoesNotThrow(() ->
                    explicitlyEnabled.registerService(
                            DirectService.class,
                            new DirectServiceImpl(),
                            "1.0.0",
                            "default"));
        } finally {
            explicitlyEnabled.close();
        }
    }

    @Test
    void closeShouldNotBlockForeverWhenApplicationUnregisterHangs() {
        HangingUnregisterRegistrar registry =
                new HangingUnregisterRegistrar();
        OtryxRpcServer server = OtryxRpcServer.builder()
                .securityProfile(com.peachsoft.otryx.api.RpcSecurityProfile.TRUSTED_DEVELOPMENT)
                .applicationMembership(controlPlane.membership(registry, Duration.ofMillis(50)))
                .transportServer(new TestTransportServer())
                .codecRegistry(RpcCodecRegistry.of(new NoopCodec()))
                .bindEndpoint(new RpcEndpoint("127.0.0.1", 19092))
                .controlPlaneTimeout(Duration.ofMillis(50))
                .build()
                .registerService(
                        ServiceOne.class,
                        new ServiceOneImpl(),
                        "1.0.0",
                        "default");

        try {
            server.start().toCompletableFuture().join();
            long startedAt = System.nanoTime();
            server.close();
            long elapsedMillis = Duration.ofNanos(System.nanoTime() - startedAt).toMillis();
            assertTrue(elapsedMillis < 1_000L);
        } finally {
            registry.release.complete(null);
            server.close();
        }
    }

    @Test
    void concurrentCloseMustBeIdempotent() throws Exception {
        BlockingUnregisterRegistrar registry = new BlockingUnregisterRegistrar();
        TestTransportServer transport = new TestTransportServer();
        OtryxRpcServer server = OtryxRpcServer.builder()
                .securityProfile(com.peachsoft.otryx.api.RpcSecurityProfile.TRUSTED_DEVELOPMENT)
                .applicationMembership(controlPlane.membership(registry, CONTROL_TIMEOUT))
                .transportServer(transport)
                .codecRegistry(RpcCodecRegistry.of(new NoopCodec()))
                .bindEndpoint(new RpcEndpoint("127.0.0.1", 19103))
                .build()
                .registerService(
                        ServiceOne.class, new ServiceOneImpl(), "1.0.0", "default");
        try {
            server.start().toCompletableFuture().join();
            CompletionStage<RpcShutdownReport> first =
                    server.shutdown(new RpcShutdownOptions(Duration.ofSeconds(2)));
            assertTrue(registry.entered.await(1, TimeUnit.SECONDS));
            CompletionStage<RpcShutdownReport> duplicate =
                    server.shutdown(new RpcShutdownOptions(Duration.ofSeconds(1)));
            assertSame(first, duplicate);
            server.close();
            registry.release.complete(null);
            RpcShutdownReport report = first.toCompletableFuture()
                    .get(2, TimeUnit.SECONDS);
            assertTrue(report.registrationsRemoved());
            assertTrue(report.transportClosed());
            assertTrue(report.executorsTerminated());
            assertEquals(1, registry.unregisters.get());
            assertEquals(1, transport.closeCount.get());
        } finally {
            registry.release.complete(null);
            server.close();
        }
    }

    @Test
    void shutdownMustReturnWithinTotalBudgetWhenUnregisterNeverCompletes()
            throws Exception {
        TestTransportServer transport = new TestTransportServer();
        HangingUnregisterRegistrar registry = new HangingUnregisterRegistrar();
        OtryxRpcServer server = OtryxRpcServer.builder()
                .securityProfile(com.peachsoft.otryx.api.RpcSecurityProfile.TRUSTED_DEVELOPMENT)
                .applicationMembership(controlPlane.membership(registry, CONTROL_TIMEOUT))
                .transportServer(transport)
                .codecRegistry(RpcCodecRegistry.of(new NoopCodec()))
                .bindEndpoint(new RpcEndpoint("127.0.0.1", 19104))
                .build()
                .registerService(
                        ServiceOne.class, new ServiceOneImpl(), "1.0.0", "default");
        try {
            server.start().toCompletableFuture().join();
            long started = System.nanoTime();
            RpcShutdownReport report = server.shutdown(
                            new RpcShutdownOptions(Duration.ofMillis(120)))
                    .toCompletableFuture().get(2, TimeUnit.SECONDS);
            assertTrue(TimeUnit.NANOSECONDS.toMillis(
                    System.nanoTime() - started) < 1000L);
            assertFalse(report.registrationsRemoved());
            assertTrue(report.deadlineExceeded());
        } finally {
            registry.release.complete(null);
            server.close();
        }
    }

    @Test
    void closeFailureMustNotSkipOtherResources() throws Exception {
        FailingCloseTransport transport = new FailingCloseTransport();
        OtryxRpcServer server = OtryxRpcServer.builder()
                .securityProfile(com.peachsoft.otryx.api.RpcSecurityProfile.TRUSTED_DEVELOPMENT)
                .applicationMembership(controlPlane.membership())
                .transportServer(transport)
                .codecRegistry(RpcCodecRegistry.of(new NoopCodec()))
                .bindEndpoint(new RpcEndpoint("127.0.0.1", 19105))
                .build()
                .registerService(
                        ServiceOne.class, new ServiceOneImpl(), "1.0.0", "default");
        server.start().toCompletableFuture().join();
        RpcShutdownReport report = server.shutdown(
                        new RpcShutdownOptions(Duration.ofSeconds(1)))
                .toCompletableFuture().get(2, TimeUnit.SECONDS);
        assertFalse(report.transportClosed());
        assertTrue(report.executorsTerminated());
        assertTrue(report.cleanupFailures() >= 1);
        assertEquals(1, transport.closeCalls.get());
    }

    @Test
    void shutdownWhileStartingMustPreventLateReadyState()
            throws Exception {
        DeferredStartTransport transport = new DeferredStartTransport();
        OtryxRpcServer server = OtryxRpcServer.builder()
                .securityProfile(com.peachsoft.otryx.api.RpcSecurityProfile.TRUSTED_DEVELOPMENT)
                .applicationMembership(controlPlane.membership())
                .transportServer(transport)
                .codecRegistry(RpcCodecRegistry.of(new NoopCodec()))
                .bindEndpoint(new RpcEndpoint("127.0.0.1", 19106))
                .build()
                .registerService(ServiceOne.class, new ServiceOneImpl(), "1.0.0", "default");
        CompletionStage<RpcEndpoint> starting = server.start();
        assertTrue(transport.entered.await(1, TimeUnit.SECONDS));
        CompletionStage<RpcShutdownReport> closing =
                server.shutdown(new RpcShutdownOptions(Duration.ofSeconds(1)));
        transport.started.complete(new RpcEndpoint("127.0.0.1", 19106));
        assertThrows(CompletionException.class,
                () -> starting.toCompletableFuture().join());
        RpcShutdownReport report = closing.toCompletableFuture()
                .get(2, TimeUnit.SECONDS);
        assertTrue(report.executorsTerminated());
        assertThrows(CompletionException.class,
                () -> server.start().toCompletableFuture().join());
    }

    @Test
    void wildcardBindShouldRequireAdvertisedHost() {
        OtryxRpcServer server = OtryxRpcServer.builder()
                .securityProfile(com.peachsoft.otryx.api.RpcSecurityProfile.TRUSTED_DEVELOPMENT)
                .applicationMembership(controlPlane.membership())
                .transportServer(new TestTransportServer())
                .codecRegistry(RpcCodecRegistry.of(new NoopCodec()))
                .bindEndpoint(new RpcEndpoint("0.0.0.0", 19090))
                .build()
                .registerService(ServiceOne.class, new ServiceOneImpl(), "1.0.0", "default");

        try {
            CompletionException error = assertThrows(
                    CompletionException.class,
                    () -> server.start()
                            .toCompletableFuture()
                            .join());
            assertEquals(
                    "RPC advertised host is required when bind host is 0.0.0.0",
                    error.getCause().getMessage());
        } finally {
            server.close();
        }
    }

    @Test
    void dynamicPortShouldPublishActualTransportPort() {
        CapturingRegistrar registry = new CapturingRegistrar();
        OtryxRpcServer server = OtryxRpcServer.builder()
                .securityProfile(com.peachsoft.otryx.api.RpcSecurityProfile.TRUSTED_DEVELOPMENT)
                .applicationMembership(controlPlane.membership(registry, CONTROL_TIMEOUT))
                .transportServer(new DynamicPortTransportServer(24567))
                .codecRegistry(RpcCodecRegistry.of(new NoopCodec()))
                .bindEndpoint(new RpcEndpoint("127.0.0.1", 0))
                .build()
                .registerService(
                        ServiceOne.class,
                        new ServiceOneImpl(),
                        "1.0.0",
                        "default");

        try {
            server.start().toCompletableFuture().join();

            assertEquals(
                    new RpcEndpoint("127.0.0.1", 24567),
                    registry.registered.get().rpcEndpoint());
        } finally {
            server.close();
        }
    }

    @Test
    void shouldRollbackMappingsWhenApplicationRegistrationFails() {
        FailingRegistrar registry = new FailingRegistrar();
        TestTransportServer transport = new TestTransportServer();
        RpcCodec codec = new NoopCodec();
        OtryxRpcServer server = OtryxRpcServer.builder()
                .securityProfile(com.peachsoft.otryx.api.RpcSecurityProfile.TRUSTED_DEVELOPMENT)
                .applicationMembership(controlPlane.membership(registry, CONTROL_TIMEOUT))
                .transportServer(transport)
                .codecRegistry(RpcCodecRegistry.of(codec))
                .bindEndpoint(new RpcEndpoint("127.0.0.1", 19090))
                .build()
                .registerService(ServiceOne.class, new ServiceOneImpl(), "1.0.0", "default")
                .registerService(ServiceTwo.class, new ServiceTwoImpl(), "1.0.0", "default");

        try {
            assertThrows(
                    CompletionException.class,
                    () -> server.start().toCompletableFuture().join());
            assertEquals(1, registry.registerCount.get(),
                    "Multiple interfaces must publish one application member");
            assertEquals(0, registry.unregisterCount.get(),
                    "Rejected application registration must not fabricate a live member");
            for (Class<?> api : new Class<?>[] {ServiceOne.class, ServiceTwo.class}) {
                ServiceMappingKey key = new ServiceMappingKey(ApplicationTestControlPlane.NAMESPACE,
                        new ServiceKey(api.getName(), "1.0.0", "default"));
                assertTrue(controlPlane.mappings.lookup(key).toCompletableFuture().join().applications().isEmpty(),
                        "Application startup failure must revoke every exported service mapping");
            }
            assertEquals(1, transport.closeCount.get());
        } finally {
            server.close();
        }
    }

    @Test
    void applicationRegistrationFailureMustCompleteStartupWhenTransportCloseThrows() throws Exception {
        FailingRegistrar registry = new FailingRegistrar();
        FailingCloseTransport transport = new FailingCloseTransport();
        OtryxRpcServer server = OtryxRpcServer.builder()
                .securityProfile(com.peachsoft.otryx.api.RpcSecurityProfile.TRUSTED_DEVELOPMENT)
                .applicationMembership(controlPlane.membership(registry, CONTROL_TIMEOUT))
                .transportServer(transport)
                .codecRegistry(RpcCodecRegistry.of(new NoopCodec()))
                .bindEndpoint(new RpcEndpoint("127.0.0.1", 19107))
                .build()
                .registerService(ServiceOne.class, new ServiceOneImpl(), "1.0.0", "default");
        try {
            CompletableFuture<Throwable> outcome = server.start()
                    .handle((endpoint, error) -> error).toCompletableFuture();
            Throwable failure = assertDoesNotThrow(() -> outcome.get(2, TimeUnit.SECONDS),
                    "Startup failure must complete even when transport cleanup throws");
            assertNotNull(failure);
            Throwable primary = failure;
            while (primary instanceof CompletionException && primary.getCause() != null) {
                primary = primary.getCause();
            }
            assertSame(registry.failure, primary,
                    "Registration failure must remain the startup failure cause");
            assertEquals(1, failure.getSuppressed().length);
            assertSame(transport.failure, failure.getSuppressed()[0]);
            assertEquals(1, transport.closeCalls.get());
            ServiceMappingKey key = new ServiceMappingKey(ApplicationTestControlPlane.NAMESPACE,
                    new ServiceKey(ServiceOne.class.getName(), "1.0.0", "default"));
            assertTrue(controlPlane.mappings.lookup(key).toCompletableFuture().join().applications().isEmpty(),
                    "Transport cleanup failure must not restore revoked application mappings");
        } finally {
            server.shutdown(new RpcShutdownOptions(Duration.ofSeconds(1)))
                    .toCompletableFuture().get(2, TimeUnit.SECONDS);
        }
    }

    public interface DirectService {

        @OtryxRpcExecution(RpcExecutionMode.DIRECT)
        String call();
    }

    public static final class DirectServiceImpl implements DirectService {
        @Override
        public String call() {
            return "direct";
        }
    }

    public interface ServiceOne {
        String call();
    }

    public interface ServiceTwo {
        String call();
    }

    public static final class ServiceOneImpl implements ServiceOne {
        @Override
        public String call() {
            return "one";
        }
    }

    public static final class ServiceTwoImpl implements ServiceTwo {
        @Override
        public String call() {
            return "two";
        }
    }

    private static final class NoopCodec implements RpcCodec {
        @Override
        public byte code() {
            return 1;
        }

        @Override
        public byte[] encode(Object value) {
            return new byte[0];
        }

        @Override
        public <T> T decode(byte[] bytes, Class<T> type) {
            throw new UnsupportedOperationException("Not used by this test");
        }
    }

    private static final class HangingUnregisterRegistrar
            implements ApplicationRegistrar {
        private final CompletableFuture<Void> release = new CompletableFuture<>();

        @Override
        public CompletionStage<Void> register(ApplicationInstance instance) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletionStage<Void> unregister(ApplicationInstance instance) {
            return release;
        }
    }

    private static final class CapturingRegistrar
            implements ApplicationRegistrar {
        private final AtomicReference<ApplicationInstance> registered =
                new AtomicReference<>();

        @Override
        public CompletionStage<Void> register(ApplicationInstance instance) {
            registered.set(instance);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletionStage<Void> unregister(ApplicationInstance instance) {
            return CompletableFuture.completedFuture(null);
        }
    }

    private static final class DynamicPortTransportServer
            implements RpcTransportServer {
        private final int port;

        private DynamicPortTransportServer(int port) {
            this.port = port;
        }

        @Override
        public CompletionStage<RpcEndpoint> start(
                RpcEndpoint bind,
                RpcRequestHandler handler) {
            return CompletableFuture.completedFuture(
                    new RpcEndpoint(bind.host(), port));
        }

        @Override
        public void close() {
        }
    }

    private static final class FailingRegistrar implements ApplicationRegistrar {
        private final AtomicInteger registerCount = new AtomicInteger();
        private final AtomicInteger unregisterCount = new AtomicInteger();
        private final IllegalStateException failure =
                new IllegalStateException("Synthetic application registration failure");

        @Override
        public CompletionStage<Void> register(ApplicationInstance instance) {
            registerCount.incrementAndGet();
            return CompletableFuture.failedFuture(failure);
        }

        @Override
        public CompletionStage<Void> unregister(ApplicationInstance instance) {
            unregisterCount.incrementAndGet();
            return CompletableFuture.completedFuture(null);
        }

    }

    private static final class BlockingUnregisterRegistrar
            implements ApplicationRegistrar {
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CompletableFuture<Void> release = new CompletableFuture<>();
        private final AtomicInteger unregisters = new AtomicInteger();

        @Override
        public CompletionStage<Void> register(ApplicationInstance instance) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletionStage<Void> unregister(ApplicationInstance instance) {
            unregisters.incrementAndGet();
            entered.countDown();
            return release;
        }
    }

    private static final class FailingCloseTransport
            implements RpcTransportServer {
        private final AtomicInteger closeCalls = new AtomicInteger();
        private final IllegalStateException failure = new IllegalStateException("synthetic close failure");

        @Override
        public CompletionStage<RpcEndpoint> start(
                RpcEndpoint bind,
                RpcRequestHandler handler) {
            return CompletableFuture.completedFuture(bind);
        }

        @Override
        public CompletionStage<Void> drain(Duration timeout) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void close() {
            closeCalls.incrementAndGet();
            throw failure;
        }
    }

    private static final class DeferredStartTransport
            implements RpcTransportServer {
        private final CompletableFuture<RpcEndpoint> started =
                new CompletableFuture<>();
        private final CountDownLatch entered = new CountDownLatch(1);

        @Override
        public CompletionStage<RpcEndpoint> start(
                RpcEndpoint bind,
                RpcRequestHandler handler) {
            entered.countDown();
            return started;
        }

        @Override
        public void close() {
        }
    }

    private static final class TestTransportServer implements RpcTransportServer {
        private final AtomicInteger closeCount = new AtomicInteger();

        @Override
        public CompletionStage<RpcEndpoint> start(
                RpcEndpoint bind, RpcRequestHandler handler) {
            return CompletableFuture.completedFuture(bind);
        }

        @Override
        public CompletionStage<Void> drain(Duration timeout) {
            // 独立测试排空和实际关闭；默认 SPI 的 drain() 本身会调用 close()。
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void close() {
            closeCount.incrementAndGet();
        }
    }
}
