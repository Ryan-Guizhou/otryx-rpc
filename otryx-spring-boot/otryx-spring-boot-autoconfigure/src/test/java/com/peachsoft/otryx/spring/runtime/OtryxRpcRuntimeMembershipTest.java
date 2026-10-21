package com.peachsoft.otryx.spring.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.peachsoft.otryx.api.ApplicationInstance;
import com.peachsoft.otryx.api.ApplicationKey;
import com.peachsoft.otryx.api.RpcTarget;
import com.peachsoft.otryx.transport.RpcTransportResponse;
import com.peachsoft.otryx.core.ApplicationMembershipCoordinator;
import com.peachsoft.otryx.core.OtryxRpcClient;
import com.peachsoft.otryx.core.RpcShutdownOptions;
import com.peachsoft.otryx.registry.ApplicationRegistrar;
import com.peachsoft.otryx.registry.ApplicationRegistry;
import com.peachsoft.otryx.registry.memory.MemoryApplicationRegistry;
import com.peachsoft.otryx.spring.annotation.OtryxRpcReference;
import com.peachsoft.otryx.spring.autoconfigure.OtryxRpcAutoConfiguration;
import com.peachsoft.otryx.transport.RpcTransportClient;
import com.peachsoft.otryx.transport.RpcTransportFactory;
import com.peachsoft.otryx.transport.RpcTransportOptions;
import com.peachsoft.otryx.transport.RpcTransportServer;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * 验证 Spring Consumer 成员就绪、启动回滚与共享运行时关闭的真实生命周期。
 *
 * @Author OpenAI
 * @Version 1.0.0-SNAPSHOT
 * @CreateTime 2026/10/19 09:46
 */
class OtryxRpcRuntimeMembershipTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(OtryxRpcAutoConfiguration.class))
            .withPropertyValues("spring.application.name=lifecycle-test",
                    "otryx.rpc.security-profile=TRUSTED_DEVELOPMENT");

    @Test
    void rejectedConsumerMembershipMustFailSpringStartupAndCloseAllocatedTransport() {
        AtomicBoolean transportClosed = new AtomicBoolean();
        withRegistration(CompletableFuture.failedFuture(new IllegalStateException("registration rejected")))
                .withBean(RpcTransportFactory.class, () -> transportFactory(
                        transport(transportClosed, null, null), null, null))
                .withBean(ConsumerBean.class)
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasStackTraceContaining("membership");
                    assertThat(transportClosed).isTrue();
                });
    }

    @Test
    void unacknowledgedConsumerMembershipMustFailWithinControlPlaneBudget() {
        withRegistration(new CompletableFuture<>())
                .withBean(ConsumerBean.class)
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasStackTraceContaining("membership");
                });
    }

    @Test
    void closedCoordinatorMustRejectPreviouslyUnusedLazyCapabilities() {
        runner.run(context -> {
            OtryxRpcRuntimeCoordinator coordinator = context.getBean(OtryxRpcRuntimeCoordinator.class);
            coordinator.close();
            assertThatThrownBy(coordinator::client).isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("closed");
            assertThatThrownBy(coordinator::server).isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("closed");
            assertThat(coordinator.clientIfCreated()).isEmpty();
            assertThat(coordinator.serverIfCreated()).isEmpty();
        });
    }

    @Test
    void closingSpringRuntimeMustAwaitClientTransportBeforeSharedMembershipTeardown() {
        CountDownLatch closeEntered = new CountDownLatch(1);
        CountDownLatch releaseClose = new CountDownLatch(1);
        AtomicBoolean transportClosed = new AtomicBoolean();
        runner.withBean(RpcTransportFactory.class, () -> transportFactory(
                        transport(transportClosed, closeEntered, releaseClose), null, null))
                .withBean(ConsumerBean.class)
                .run(context -> {
                    OtryxRpcRuntimeCoordinator coordinator = context.getBean(OtryxRpcRuntimeCoordinator.class);
                    OtryxRpcClient client = coordinator.clientIfCreated().orElseThrow();
                    CompletableFuture<Void> closing = CompletableFuture.runAsync(coordinator::close);
                    try {
                        assertTrue(closeEntered.await(5, TimeUnit.SECONDS));
                        assertThat(closing).isNotDone();
                    } finally {
                        releaseClose.countDown();
                    }
                    closing.get(5, TimeUnit.SECONDS);
                    var report = client.shutdown(RpcShutdownOptions.DEFAULT).toCompletableFuture()
                            .get(5, TimeUnit.SECONDS);
                    assertThat(transportClosed).isTrue();
                    assertThat(report.transportClosed()).isTrue();
                    assertThat(report.cleanupFailures()).isZero();
                    assertThatThrownBy(coordinator::client).hasMessageContaining("closed");
                });
    }

    @Test
    void concurrentCloseMustAwaitTheSameTransportCleanup() {
        CountDownLatch closeEntered = new CountDownLatch(1);
        CountDownLatch releaseClose = new CountDownLatch(1);
        CountDownLatch secondStarted = new CountDownLatch(1);
        AtomicBoolean transportClosed = new AtomicBoolean();
        runner.withBean(RpcTransportFactory.class, () -> transportFactory(
                        transport(transportClosed, closeEntered, releaseClose), null, null))
                .withBean(ConsumerBean.class)
                .run(context -> {
                    OtryxRpcRuntimeCoordinator coordinator = context.getBean(OtryxRpcRuntimeCoordinator.class);
                    CompletableFuture<Void> first = CompletableFuture.runAsync(coordinator::close);
                    CompletableFuture<Void> second = CompletableFuture.runAsync(() -> {
                        awaitLatch(closeEntered);
                        secondStarted.countDown();
                        coordinator.close();
                    });
                    try {
                        assertTrue(closeEntered.await(5, TimeUnit.SECONDS));
                        assertTrue(secondStarted.await(5, TimeUnit.SECONDS));
                        assertThatThrownBy(() -> second.get(200, TimeUnit.MILLISECONDS))
                                .isInstanceOf(TimeoutException.class);
                        assertThat(first).isNotDone();
                        assertThat(transportClosed).isFalse();
                    } finally {
                        releaseClose.countDown();
                    }
                    first.get(5, TimeUnit.SECONDS);
                    second.get(5, TimeUnit.SECONDS);
                    assertThat(transportClosed).isTrue();
                });
    }

    @Test
    void reentrantCloseOnTheOwnerThreadMustNotWaitForItself() {
        ApplicationMembershipCoordinator membership = mock(ApplicationMembershipCoordinator.class);
        AtomicReference<OtryxRpcRuntimeCoordinator> coordinatorReference = new AtomicReference<>();
        when(membership.shutdown()).thenAnswer(invocation -> {
            coordinatorReference.get().close();
            return CompletableFuture.completedFuture(null);
        });
        runner.withBean(ApplicationMembershipCoordinator.class, () -> membership)
                .run(context -> {
                    OtryxRpcRuntimeCoordinator coordinator = context.getBean(OtryxRpcRuntimeCoordinator.class);
                    coordinatorReference.set(coordinator);
                    CompletableFuture.runAsync(coordinator::close).get(1, TimeUnit.SECONDS);
                    verify(membership).shutdown();
                    assertThatThrownBy(coordinator::client).hasMessageContaining("closed");
                });
    }

    @Test
    void synchronousMembershipShutdownFailureMustReleaseEveryCloseCaller() {
        ApplicationMembershipCoordinator membership = mock(ApplicationMembershipCoordinator.class);
        when(membership.shutdown()).thenThrow(new IllegalStateException("synthetic shutdown rejection"));
        runner.withBean(ApplicationMembershipCoordinator.class, () -> membership)
                .run(context -> {
                    OtryxRpcRuntimeCoordinator coordinator = context.getBean(OtryxRpcRuntimeCoordinator.class);
                    coordinator.close();
                    CompletableFuture.runAsync(coordinator::close).get(1, TimeUnit.SECONDS);
                    verify(membership).shutdown();
                    assertThatThrownBy(coordinator::client).hasMessageContaining("closed");
                });
    }

    @Test
    void concurrentLazyCreationAndCloseMustNotLeaveLiveClientBehind() {
        CountDownLatch createEntered = new CountDownLatch(1);
        CountDownLatch releaseCreate = new CountDownLatch(1);
        AtomicBoolean transportClosed = new AtomicBoolean();
        runner.withBean(RpcTransportFactory.class, () -> transportFactory(
                        transport(transportClosed, null, null), createEntered, releaseCreate))
                .run(context -> {
                    OtryxRpcRuntimeCoordinator coordinator = context.getBean(OtryxRpcRuntimeCoordinator.class);
                    CompletableFuture<OtryxRpcClient> creating = CompletableFuture.supplyAsync(coordinator::client);
                    assertTrue(createEntered.await(5, TimeUnit.SECONDS));
                    CountDownLatch closingStarted = new CountDownLatch(1);
                    CompletableFuture<Void> closing = CompletableFuture.runAsync(() -> {
                        closingStarted.countDown();
                        coordinator.close();
                    });
                    try {
                        assertTrue(closingStarted.await(5, TimeUnit.SECONDS));
                    } finally {
                        releaseCreate.countDown();
                    }
                    OtryxRpcClient client = creating.get(5, TimeUnit.SECONDS);
                    closing.get(5, TimeUnit.SECONDS);
                    assertThat(transportClosed).isTrue();
                    assertThat(client.shutdown(RpcShutdownOptions.DEFAULT).toCompletableFuture()
                            .get(5, TimeUnit.SECONDS).cleanupFailures()).isZero();
                    assertThatThrownBy(coordinator::client).hasMessageContaining("closed");
                });
    }

    private ApplicationContextRunner withRegistration(CompletionStage<Void> acknowledgement) {
        MemoryApplicationRegistry registry = new MemoryApplicationRegistry();
        ApplicationKey application = new ApplicationKey("default", "lifecycle-test");
        ApplicationRegistrar registrar = new ApplicationRegistrar() {
            @Override
            public CompletionStage<Void> register(ApplicationInstance instance) {
                return acknowledgement;
            }

            @Override
            public CompletionStage<Void> unregister(ApplicationInstance instance) {
                return CompletableFuture.completedFuture(null);
            }
        };
        return runner.withBean(ApplicationRegistry.class, () -> registry)
                .withBean(OtryxRpcApplicationIdentity.class,
                        () -> new OtryxRpcApplicationIdentity(application, "test-consumer"))
                .withBean(ApplicationMembershipCoordinator.class, () -> new ApplicationMembershipCoordinator(
                        application, "test-consumer", registrar, registry.manifestRepository(),
                        registry.serviceMappingRepository(), Duration.ofMillis(100)));
    }

    private static RpcTransportClient transport(
            AtomicBoolean closed, CountDownLatch entered, CountDownLatch release) {
        return new RpcTransportClient() {
            @Override
            public CompletionStage<RpcTransportResponse> request(RpcTarget target, byte[] frame, Duration timeout) {
                return CompletableFuture.failedFuture(new AssertionError("No business RPC expected"));
            }

            @Override
            public void close() {
                if (entered != null) {
                    entered.countDown();
                    awaitLatch(release);
                }
                closed.set(true);
            }
        };
    }

    private static RpcTransportFactory transportFactory(
            RpcTransportClient transport, CountDownLatch entered, CountDownLatch release) {
        return new RpcTransportFactory() {
            @Override
            public RpcTransportClient createClient(RpcTransportOptions options) {
                if (entered != null) {
                    entered.countDown();
                    awaitLatch(release);
                }
                return transport;
            }

            @Override
            public RpcTransportServer createServer(RpcTransportOptions options) {
                throw new AssertionError("No Provider runtime expected");
            }
        };
    }

    private static void awaitLatch(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("Timed out waiting for test control signal");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(interrupted);
        }
    }

    private interface EchoService {
        String echo();
    }

    private static final class ConsumerBean {
        @OtryxRpcReference
        private EchoService service;
    }
}
