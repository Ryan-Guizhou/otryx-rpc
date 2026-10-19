package com.peachsoft.otryx.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.peachsoft.otryx.api.ApplicationKey;
import com.peachsoft.otryx.api.OtryxRpcIdempotent;
import com.peachsoft.otryx.api.RpcSchemaFingerprint;
import com.peachsoft.otryx.api.RpcSecurityProfile;
import com.peachsoft.otryx.api.RpcUnavailableException;
import com.peachsoft.otryx.api.ServiceInstance;
import com.peachsoft.otryx.api.ServiceKey;
import com.peachsoft.otryx.codec.RpcCodecRegistry;
import com.peachsoft.otryx.loadbalance.LoadBalanceMetrics;
import com.peachsoft.otryx.loadbalance.LoadBalancer;
import com.peachsoft.otryx.registry.RegistrySecurityException;
import com.peachsoft.otryx.registry.RegistrySecurityFailure;
import com.peachsoft.otryx.registry.ServiceMappingKey;
import com.peachsoft.otryx.registry.ServiceMappingOwner;
import com.peachsoft.otryx.registry.ServiceMappingRepository;
import com.peachsoft.otryx.registry.ServiceMappingSnapshot;
import java.lang.reflect.Field;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

/**
 * Core 的发送前原候选复核及 retry scheduler 内的安全终结必须使用 Resolver 同一凭据。
 *
 * @Author OpenAI
 * @Version 1.0.0-SNAPSHOT
 * @CreateTime 2026/10/19 11:18
 */
class RpcClientDirectorySecurityTest {
    private static final ServiceKey KEY = new ServiceKey(Api.class.getName(), "1.0.0", "default");
    private static final ServiceMappingKey MAPPING = new ServiceMappingKey(RpcSecurityTestFixtures.NAMESPACE, KEY);
    private static final String SCHEMA = RpcSchemaFingerprint.serviceFingerprint(KEY, Api.class);
    private static final ServiceMappingKey OTHER = new ServiceMappingKey(RpcSecurityTestFixtures.NAMESPACE,
            new ServiceKey("example.Other", "1.0.0", "default"));

    @Test
    void securityChangeDuringLoadBalanceSelectionRejectsBeforeTransportRequest() throws Exception {
        assertSelectionGuard(false);
    }

    @Test
    void freshlyRecoveredCandidateCannotBlessThePreviouslySelectedOldView() throws Exception {
        assertSelectionGuard(true);
    }

    private static void assertSelectionGuard(boolean recover) throws Exception {
        AtomicBoolean denied = new AtomicBoolean();
        try (var control = new RpcSecurityTestFixtures.ControlPlane(source -> new ControlledMappings(source, denied))) {
            var transport = new RpcSecurityTestFixtures.ClientTransport(control.clock);
            var codec = new RpcSecurityTestFixtures.TestCodec();
            try (OtryxRpcClient client = control.clientBuilder(transport, RpcCodecRegistry.of(codec))
                    .securityProfile(RpcSecurityProfile.TRUSTED_DEVELOPMENT).loadBalancer(new LoadBalancer() {
                        @Override
                        public ServiceInstance select(ServiceInstance[] instances, LoadBalanceMetrics metrics) {
                            rejectAnotherRoute(control, denied);
                            if (recover) {
                                denied.set(false);
                                control.resolver.refresh(MAPPING, SCHEMA).toCompletableFuture().join();
                            }
                            return instances[0];
                        }
                    }).build()) {
                control.publish(Api.class);
                Api api = client.refer(Api.class, "1.0.0", "default");
                ApplicationTestControlPlane.awaitReady(client, KEY);
                assertEquals(RegistrySecurityFailure.AUTHORIZATION_REJECTED,
                        securityFailure(assertThrows(CompletionException.class, api::call)).reason());
                assertEquals(0, transport.requests.get());
                assertEquals(0, codec.resultDecodes.get());
                for (Object value : statistics(client).values()) {
                    EndpointStats stats = (EndpointStats) value;
                    assertEquals(0, stats.inflight());
                    assertEquals(1_000_000L, stats.ewma());
                }
            }
        }
    }

    @Test
    void retryAttemptSecurityRejectionTerminatesWithoutWaitingForDeadline() throws Exception {
        AtomicBoolean denied = new AtomicBoolean();
        try (var control = new RpcSecurityTestFixtures.ControlPlane(source -> new ControlledMappings(source, denied))) {
            var transport = new RpcSecurityTestFixtures.ClientTransport(control.clock);
            var codec = new RpcSecurityTestFixtures.TestCodec();
            transport.responder = (target, frame, timeout) -> {
                rejectAnotherRoute(control, denied);
                return CompletableFuture.failedFuture(new RpcUnavailableException("Synthetic ordinary disconnect"));
            };
            ExecutorService caller = Executors.newSingleThreadExecutor();
            try (OtryxRpcClient client = control.clientBuilder(transport, RpcCodecRegistry.of(codec))
                    .securityProfile(RpcSecurityProfile.TRUSTED_DEVELOPMENT).timeout(Duration.ofSeconds(10)).build()) {
                control.publish(Api.class);
                Api api = client.refer(Api.class, "1.0.0", "default");
                ApplicationTestControlPlane.awaitReady(client, KEY);
                CompletableFuture<String> response = CompletableFuture.supplyAsync(api::call, caller);
                ExecutionException wrapper = assertThrows(ExecutionException.class,
                        () -> response.get(2, TimeUnit.SECONDS));
                assertEquals(RegistrySecurityFailure.AUTHORIZATION_REJECTED, securityFailure(wrapper).reason());
                assertEquals(1, transport.requests.get(), "The rejected retry cannot send another request");
                assertEquals(0, codec.resultDecodes.get());
                for (Object value : statistics(client).values()) {
                    assertEquals(0, ((EndpointStats) value).inflight());
                }
                int tracked = client.trackedEndpointCount();
                client.evictUnusedEndpointStats();
                client.evictUnusedEndpointStats();
                assertEquals(tracked, client.trackedEndpointCount(),
                        "A rejected directory cannot erase prior endpoint failure history during cleanup");
            } finally {
                caller.shutdownNow();
                assertTrue(caller.awaitTermination(2, TimeUnit.SECONDS));
            }
        }
    }

    @Test
    void missingCandidateStillReportsTheCurrentSecurityReason() throws Exception {
        AtomicBoolean denied = new AtomicBoolean();
        try (var control = new RpcSecurityTestFixtures.ControlPlane(source -> new ControlledMappings(source, denied))) {
            var transport = new RpcSecurityTestFixtures.ClientTransport(control.clock);
            rejectAnotherRoute(control, denied);
            try (OtryxRpcClient client = control.clientBuilder(transport,
                    RpcCodecRegistry.of(new RpcSecurityTestFixtures.TestCodec()))
                    .securityProfile(RpcSecurityProfile.TRUSTED_DEVELOPMENT).build()) {
                client.membershipReady().toCompletableFuture().get(2, TimeUnit.SECONDS);
                Api api = client.refer(Api.class, "1.0.0", "default");
                assertEquals(RegistrySecurityFailure.AUTHORIZATION_REJECTED,
                        securityFailure(assertThrows(CompletionException.class, api::call)).reason());
                assertEquals(0, transport.requests.get());
            }
        }
    }

    private static void rejectAnotherRoute(RpcSecurityTestFixtures.ControlPlane control, AtomicBoolean denied) {
        denied.set(true);
        CompletionException failure = assertThrows(CompletionException.class,
                () -> control.resolver.refresh(OTHER, SCHEMA).toCompletableFuture().join());
        assertEquals(RegistrySecurityFailure.AUTHORIZATION_REJECTED, securityFailure(failure).reason());
    }

    private static RegistrySecurityException securityFailure(Throwable failure) {
        Throwable current = failure;
        while ((current instanceof CompletionException || current instanceof ExecutionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        assertTrue(current instanceof RegistrySecurityException, "Expected the stable registry security failure");
        return (RegistrySecurityException) current;
    }

    private static Map<?, ?> statistics(OtryxRpcClient client) throws Exception {
        Field field = OtryxRpcClient.class.getDeclaredField("stats");
        field.setAccessible(true);
        return (Map<?, ?>) field.get(client);
    }

    public interface Api {
        @OtryxRpcIdempotent
        String call();
    }

    private record ControlledMappings(ServiceMappingRepository delegate, AtomicBoolean denied)
            implements ServiceMappingRepository {
        @Override
        public CompletionStage<ServiceMappingSnapshot> lookup(ServiceMappingKey key) {
            return denied.get()
                    ? CompletableFuture.failedFuture(new RegistrySecurityException(
                            RegistrySecurityFailure.AUTHORIZATION_REJECTED))
                    : delegate.lookup(key);
        }

        @Override
        public CompletionStage<ServiceMappingOwner> claim(ApplicationKey application, String instanceId) {
            return delegate.claim(application, instanceId);
        }

        @Override
        public CompletionStage<Void> publish(ServiceMappingOwner owner, Set<ServiceKey> services) {
            return delegate.publish(owner, services);
        }

        @Override
        public CompletionStage<Void> revoke(ServiceMappingOwner owner) {
            return delegate.revoke(owner);
        }
    }
}
