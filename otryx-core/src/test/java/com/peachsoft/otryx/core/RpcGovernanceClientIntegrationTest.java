package com.peachsoft.otryx.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.peachsoft.otryx.api.ApplicationKey;
import com.peachsoft.otryx.api.RpcIds;
import com.peachsoft.otryx.api.RpcSecurityProfile;
import com.peachsoft.otryx.api.ServiceKey;
import com.peachsoft.otryx.codec.RpcCodecRegistry;
import com.peachsoft.otryx.governance.RpcGovernanceMethodRule;
import com.peachsoft.otryx.governance.RpcGovernanceServiceRule;
import com.peachsoft.otryx.governance.RpcGovernanceSnapshot;
import com.peachsoft.otryx.routing.RpcRouteRule;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * 验证 Consumer 的真实 Deadline、路由和原可信候选与签名治理控制面集成。
 *
 * @Author OpenAI
 * @Version 1.0.0-SNAPSHOT
 * @CreateTime 2026/10/21 17:50
 */
class RpcGovernanceClientIntegrationTest {
    private static final ServiceKey SERVICE = new ServiceKey(Api.class.getName(), "1.0.0", "default");

    @Test
    void verifiedDynamicTimeoutMustApplyAndRouteMayOnlyNarrowVerifiedProvider() throws Exception {
        var control = new RpcSecurityTestFixtures.ControlPlane();
        var source = new RpcGovernanceTestFixtures.MemoryRepository();
        var methodId = RpcIds.methodId(Api.class.getMethod("say"));
        var provider = RpcSecurityTestFixtures.PROVIDER;
        var allowed = new RpcGovernanceServiceRule(
                new RpcRouteRule(Set.of(provider), List.of(), Map.of(), null, null, null),
                Map.of(provider, 3),
                Map.of(methodId, new RpcGovernanceMethodRule(Duration.ofSeconds(2), 2, 0, null)));
        source.initial.complete(RpcGovernanceTestFixtures.sign(
                new RpcGovernanceSnapshot(1L, Map.of(SERVICE, allowed))));
        try (var governance = new RpcGovernanceRuntime(source, RpcGovernanceTestFixtures.verifier(),
                Duration.ofSeconds(1))) {
            governance.start().toCompletableFuture().get(2, TimeUnit.SECONDS);
            var transport = new RpcSecurityTestFixtures.ClientTransport(control.clock);
            AtomicReference<Duration> actualTimeout = new AtomicReference<>();
            var originalResponder = transport.responder;
            transport.responder = (target, frame, timeout) -> {
                actualTimeout.set(timeout);
                return originalResponder.request(target, frame, timeout);
            };
            try (var client = control.clientBuilder(transport,
                    RpcCodecRegistry.of(new RpcSecurityTestFixtures.TestCodec()))
                    .securityProfile(RpcSecurityProfile.TRUSTED_DEVELOPMENT)
                    .governanceRuntime(governance).build()) {
                control.publish(Api.class);
                Api api = client.refer(Api.class, "1.0.0", "default");
                ApplicationTestControlPlane.awaitReady(client, SERVICE);
                assertEquals("ok", api.say());
                assertTrue(actualTimeout.get().compareTo(Duration.ofSeconds(2)) <= 0);
                assertEquals(1, transport.requests.get());

                ApplicationKey unrelated = new ApplicationKey("somewhere", "other-provider");
                var invalidRoute = new RpcGovernanceServiceRule(
                        new RpcRouteRule(Set.of(unrelated), List.of(), Map.of(), null, null, null),
                        Map.of(unrelated, 10), Map.of());
                source.send(RpcGovernanceTestFixtures.sign(
                        new RpcGovernanceSnapshot(2L, Map.of(SERVICE, invalidRoute))));
                awaitRevision(governance, 2L);
                assertThrows(RuntimeException.class, api::say);
                assertEquals(1, transport.requests.get(), "No excluded endpoint may reach the transport");
            } finally {
                control.close();
            }
        }
    }

    @Test
    void subscribedButUnverifiedSourceMustRejectConsumerWithoutSending() throws Exception {
        try (var control = new RpcSecurityTestFixtures.ControlPlane()) {
            var source = new RpcGovernanceTestFixtures.MemoryRepository();
            try (var governance = new RpcGovernanceRuntime(source, RpcGovernanceTestFixtures.verifier(),
                    Duration.ofSeconds(1))) {
                governance.start();
                var transport = new RpcSecurityTestFixtures.ClientTransport(control.clock);
                try (var client = control.clientBuilder(transport,
                        RpcCodecRegistry.of(new RpcSecurityTestFixtures.TestCodec()))
                        .securityProfile(RpcSecurityProfile.TRUSTED_DEVELOPMENT)
                        .governanceRuntime(governance).build()) {
                    control.publish(Api.class);
                    Api api = client.refer(Api.class, "1.0.0", "default");
                    ApplicationTestControlPlane.awaitReady(client, SERVICE);
                    assertThrows(RuntimeException.class, api::say);
                    assertEquals(0, transport.requests.get());
                }
            }
        }
    }

    private static void awaitRevision(RpcGovernanceRuntime governance, long expected) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (governance.revision() != expected && System.nanoTime() < deadline) {
            Thread.sleep(2L);
        }
        assertEquals(expected, governance.revision());
    }

    public interface Api {
        String say();
    }
}
