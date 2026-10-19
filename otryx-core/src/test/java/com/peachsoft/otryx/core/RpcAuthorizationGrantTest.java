package com.peachsoft.otryx.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.peachsoft.otryx.api.ApplicationKey;
import com.peachsoft.otryx.api.OtryxRpcExecution;
import com.peachsoft.otryx.api.RpcAuthorizationDecision;
import com.peachsoft.otryx.api.RpcExecutionMode;
import com.peachsoft.otryx.api.RpcIds;
import com.peachsoft.otryx.api.RpcPeerIdentity;
import com.peachsoft.otryx.api.RpcSecurityProfile;
import com.peachsoft.otryx.api.RpcStatus;
import com.peachsoft.otryx.api.ServiceKey;
import com.peachsoft.otryx.codec.RpcCodecRegistry;
import com.peachsoft.otryx.protocol.RpcProtocolCodec;
import java.lang.reflect.Field;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * 用本机身份时钟验证同一有限许可跨策略 Stage、原请求线程及业务队列的执行边界。
 *
 * @Author OpenAI
 * @Version 1.0.0-SNAPSHOT
 * @CreateTime 2026/10/19 11:18
 */
class RpcAuthorizationGrantTest {
    @Test
    void anonymousOrForeignNamespaceCannotReachAuthorizer() throws Exception {
        try (var control = new RpcSecurityTestFixtures.ControlPlane()) {
            var codec = new RpcSecurityTestFixtures.TestCodec();
            var transport = new RpcSecurityTestFixtures.ServerTransport(control.clock);
            var service = new Service();
            AtomicInteger policies = new AtomicInteger();
            try (OtryxRpcServer server = builder(control, transport, codec).authorizer(context -> {
                policies.incrementAndGet();
                return CompletableFuture.completedFuture(
                        RpcAuthorizationDecision.allowUntil(context.maximumValidUntil()));
            }).build()) {
                server.registerService(Api.class, service, "1.0.0", "default");
                server.start().toCompletableFuture().join();
                RpcPeerIdentity foreign = RpcSecurityTestFixtures.peer(new ApplicationKey("foreign", "caller"),
                        control.clock, Duration.ofSeconds(30));
                for (RpcPeerIdentity peer : new RpcPeerIdentity[] {RpcPeerIdentity.anonymous(), foreign}) {
                    assertEquals(RpcStatus.BAD_REQUEST, status(transport.call(peer, request(Api.class), null)
                            .toCompletableFuture().get(2, TimeUnit.SECONDS)));
                }
                assertEquals(0, policies.get());
                assertEquals(0, codec.argumentDecodes.get());
                assertEquals(0, service.invocations.get());
            }
        }
    }

    @Test
    void nullExceptionalAndExpiredDecisionsNeverDecodeOrInvoke() throws Exception {
        try (var control = new RpcSecurityTestFixtures.ControlPlane()) {
            RpcAuthorizer[] policies = {context -> null,
                    context -> CompletableFuture.completedFuture(null),
                    context -> CompletableFuture.failedFuture(new IllegalStateException("secret-policy-payload")),
                    context -> CompletableFuture.completedFuture(
                            RpcAuthorizationDecision.allowUntil(control.clock.instant().minusNanos(1)))};
            for (RpcAuthorizer policy : policies) {
                var codec = new RpcSecurityTestFixtures.TestCodec();
                var transport = new RpcSecurityTestFixtures.ServerTransport(control.clock);
                var service = new Service();
                try (OtryxRpcServer server = builder(control, transport, codec).authorizer(policy).build()) {
                    server.registerService(Api.class, service, "1.0.0", "default");
                    server.start().toCompletableFuture().join();
                    assertEquals(RpcStatus.BAD_REQUEST, status(transport.call(caller(control), request(Api.class), null)
                            .toCompletableFuture().get(2, TimeUnit.SECONDS)));
                    assertEquals(0, codec.argumentDecodes.get());
                    assertEquals(0, service.invocations.get());
                    server.shutdown(RpcShutdownOptions.DEFAULT).toCompletableFuture().get(3, TimeUnit.SECONDS);
                }
            }
        }
    }

    @Test
    void lateAllowCannotRefreshTheOriginalGrantLifetime() throws Exception {
        try (var control = new RpcSecurityTestFixtures.ControlPlane()) {
            var codec = new RpcSecurityTestFixtures.TestCodec();
            var transport = new RpcSecurityTestFixtures.ServerTransport(control.clock);
            var service = new Service();
            CountDownLatch policyEntered = new CountDownLatch(1);
            CompletableFuture<RpcAuthorizationDecision> policy = new CompletableFuture<>();
            try (OtryxRpcServer server = builder(control, transport, codec).authorizer(context -> {
                policyEntered.countDown();
                return policy;
            }).build()) {
                server.registerService(Api.class, service, "1.0.0", "default");
                server.start().toCompletableFuture().join();
                var reply = transport.call(caller(control), request(Api.class), null).toCompletableFuture();
                assertTrue(policyEntered.await(1, TimeUnit.SECONDS));
                control.clock.advance(Duration.ofSeconds(1));
                policy.complete(RpcAuthorizationDecision.allowUntil(control.clock.instant().plusSeconds(30)));
                assertEquals(RpcStatus.BAD_REQUEST, status(reply.get(2, TimeUnit.SECONDS)));
                assertEquals(0, codec.argumentDecodes.get());
                assertEquals(0, service.invocations.get());
            } finally {
                policy.complete(RpcAuthorizationDecision.deny());
            }
        }
    }

    @Test
    void grantExpiringOnOriginalRequestQueueCannotDecode() throws Exception {
        assertRequestQueueBoundary(false);
    }

    @Test
    void cancellationOnOriginalRequestQueueCannotDecode() throws Exception {
        assertRequestQueueBoundary(true);
    }

    private static void assertRequestQueueBoundary(boolean cancel) throws Exception {
        try (var control = new RpcSecurityTestFixtures.ControlPlane()) {
            var codec = new RpcSecurityTestFixtures.TestCodec();
            var transport = new RpcSecurityTestFixtures.ServerTransport(control.clock);
            var service = new DirectService();
            ManualDispatcher dispatcher = new ManualDispatcher();
            CountDownLatch policyEntered = new CountDownLatch(1);
            CompletableFuture<RpcAuthorizationDecision> policy = new CompletableFuture<>();
            try (OtryxRpcServer server = builder(control, transport, codec)
                    .executionOptions(new RpcProviderExecutionOptions(true, 1, 1)).authorizer(context -> {
                        policyEntered.countDown();
                        return policy;
                    }).build()) {
                server.registerService(DirectApi.class, service, "1.0.0", "default");
                server.start().toCompletableFuture().join();
                var reply = transport.call(caller(control), request(DirectApi.class), dispatcher).toCompletableFuture();
                assertTrue(policyEntered.await(1, TimeUnit.SECONDS));
                policy.complete(RpcAuthorizationDecision.allowUntil(control.clock.instant().plusSeconds(30)));
                Runnable dispatched = dispatcher.tasks.poll(1, TimeUnit.SECONDS);
                assertTrue(dispatched != null, "Authorized request did not return to its original dispatcher");
                if (cancel) {
                    assertTrue(reply.cancel(true));
                } else {
                    control.clock.advance(Duration.ofSeconds(1));
                }
                dispatched.run();
                if (!cancel) {
                    assertEquals(RpcStatus.BAD_REQUEST, status(reply.get(1, TimeUnit.SECONDS)));
                }
                assertEquals(0, codec.argumentDecodes.get());
                assertEquals(0, service.invocations.get());
            } finally {
                policy.complete(RpcAuthorizationDecision.deny());
            }
        }
    }

    @Test
    void grantExpiringInCpuQueueCannotDecodeTheQueuedCall() throws Exception {
        try (var control = new RpcSecurityTestFixtures.ControlPlane()) {
            var codec = new RpcSecurityTestFixtures.TestCodec();
            var transport = new RpcSecurityTestFixtures.ServerTransport(control.clock);
            CountDownLatch firstEntered = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            var service = new CpuService(firstEntered, release);
            try (OtryxRpcServer server = builder(control, transport, codec)
                    .executionOptions(new RpcProviderExecutionOptions(false, 1, 1)).authorizer(context ->
                            CompletableFuture.completedFuture(
                                    RpcAuthorizationDecision.allowUntil(context.maximumValidUntil())))
                    .build()) {
                server.registerService(CpuApi.class, service, "1.0.0", "default");
                server.start().toCompletableFuture().join();
                var first = transport.call(caller(control), request(CpuApi.class), null).toCompletableFuture();
                assertTrue(firstEntered.await(1, TimeUnit.SECONDS));
                var queued = transport.call(caller(control), request(CpuApi.class), null).toCompletableFuture();
                ApplicationTestControlPlane.await(() -> cpuQueueSize(server) == 1, "Second call was not queued");
                control.clock.advance(Duration.ofSeconds(1));
                release.countDown();
                assertEquals(RpcStatus.OK, status(first.get(2, TimeUnit.SECONDS)),
                        "An expired start grant does not revoke business work that already began");
                assertEquals(RpcStatus.BAD_REQUEST, status(queued.get(2, TimeUnit.SECONDS)));
                assertEquals(1, codec.argumentDecodes.get());
                assertEquals(1, service.invocations.get());
            } finally {
                release.countDown();
            }
        }
    }

    @Test
    void expiryDuringArgumentDecodePreventsBusinessInvocation() throws Exception {
        try (var control = new RpcSecurityTestFixtures.ControlPlane()) {
            var codec = new RpcSecurityTestFixtures.TestCodec();
            var transport = new RpcSecurityTestFixtures.ServerTransport(control.clock);
            var service = new Service();
            CountDownLatch entered = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            codec.decodeArguments = () -> {
                entered.countDown();
                await(release);
            };
            try (OtryxRpcServer server = builder(control, transport, codec).authorizer(context ->
                    CompletableFuture.completedFuture(
                                    RpcAuthorizationDecision.allowUntil(context.maximumValidUntil())))
                    .build()) {
                server.registerService(Api.class, service, "1.0.0", "default");
                server.start().toCompletableFuture().join();
                var response = transport.call(caller(control), request(Api.class), null).toCompletableFuture();
                assertTrue(entered.await(1, TimeUnit.SECONDS));
                control.clock.advance(Duration.ofSeconds(1));
                release.countDown();
                assertEquals(RpcStatus.BAD_REQUEST, status(response.get(2, TimeUnit.SECONDS)));
                assertEquals(1, codec.argumentDecodes.get());
                assertEquals(0, service.invocations.get());
            } finally {
                release.countDown();
            }
        }
    }

    @Test
    void expiredConnectionCannotReceiveSuccessAfterBusinessAlreadyRan() throws Exception {
        try (var control = new RpcSecurityTestFixtures.ControlPlane()) {
            var codec = new RpcSecurityTestFixtures.TestCodec();
            var transport = new RpcSecurityTestFixtures.ServerTransport(control.clock);
            var service = new Service();
            service.onCall = () -> control.clock.advance(Duration.ofSeconds(1));
            try (OtryxRpcServer server = builder(control, transport, codec).authorizer(context ->
                    CompletableFuture.completedFuture(
                                    RpcAuthorizationDecision.allowUntil(context.maximumValidUntil())))
                    .build()) {
                server.registerService(Api.class, service, "1.0.0", "default");
                server.start().toCompletableFuture().join();
                RpcPeerIdentity peer = RpcSecurityTestFixtures.peer(RpcSecurityTestFixtures.CONSUMER,
                        control.clock, Duration.ofSeconds(1));
                var response = transport.call(peer, request(Api.class), null).toCompletableFuture();
                assertEquals(RpcStatus.BAD_REQUEST, status(response.get(2, TimeUnit.SECONDS)));
                assertEquals(1, service.invocations.get());
            }
        }
    }

    private static OtryxRpcServer.Builder builder(RpcSecurityTestFixtures.ControlPlane control,
            RpcSecurityTestFixtures.ServerTransport transport, RpcSecurityTestFixtures.TestCodec codec) {
        return control.serverBuilder(transport, RpcCodecRegistry.of(codec))
                .securityProfile(RpcSecurityProfile.NETWORK_MTLS)
                .maxAuthorizationGrantLifetime(Duration.ofSeconds(1));
    }

    private static RpcPeerIdentity caller(RpcSecurityTestFixtures.ControlPlane control) {
        return RpcSecurityTestFixtures.peer(RpcSecurityTestFixtures.CONSUMER, control.clock, Duration.ofSeconds(60));
    }

    private static RpcStatus status(byte[] frame) {
        return RpcProtocolCodec.view(frame).status();
    }

    private static byte[] request(Class<?> api) throws Exception {
        ServiceKey key = new ServiceKey(api.getName(), "1.0.0", "default");
        return RpcProtocolCodec.encodeRequest((byte) 1, RpcIds.serviceId(key), RpcIds.methodId(api.getMethod("call")),
                0L, 0L, Map.of(), new byte[0]);
    }

    private static int cpuQueueSize(OtryxRpcServer server) {
        try {
            Field field = OtryxRpcServer.class.getDeclaredField("cpuExecutor");
            field.setAccessible(true);
            return ((ThreadPoolExecutor) field.get(server)).getQueue().size();
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("Controlled business task was not released");
            }
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new AssertionError(failure);
        }
    }

    public interface Api {
        String call();
    }

    public static final class Service implements Api {
        final AtomicInteger invocations = new AtomicInteger();
        Runnable onCall = () -> { };

        @Override
        public String call() {
            invocations.incrementAndGet();
            onCall.run();
            return "ok";
        }
    }

    public interface DirectApi {
        @OtryxRpcExecution(RpcExecutionMode.DIRECT)
        String call();
    }

    public static final class DirectService implements DirectApi {
        final AtomicInteger invocations = new AtomicInteger();

        @Override
        public String call() {
            invocations.incrementAndGet();
            return "ok";
        }
    }

    public interface CpuApi {
        @OtryxRpcExecution(RpcExecutionMode.CPU)
        String call();
    }

    public static final class CpuService implements CpuApi {
        final AtomicInteger invocations = new AtomicInteger();
        private final CountDownLatch entered;
        private final CountDownLatch release;

        CpuService(CountDownLatch entered, CountDownLatch release) {
            this.entered = entered;
            this.release = release;
        }

        @Override
        public String call() {
            invocations.incrementAndGet();
            entered.countDown();
            await(release);
            return "ok";
        }
    }

    private static final class ManualDispatcher implements Executor {
        private final ArrayBlockingQueue<Runnable> tasks = new ArrayBlockingQueue<>(1);

        @Override
        public void execute(Runnable task) {
            if (!tasks.offer(task)) {
                throw new IllegalStateException("Only one dispatch is expected");
            }
        }
    }
}
