package com.peachsoft.otryx.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.peachsoft.otryx.api.ApplicationKey;
import com.peachsoft.otryx.api.RpcEndpoint;
import com.peachsoft.otryx.api.RpcSecurityProfile;
import com.peachsoft.otryx.api.ServiceInstance;
import com.peachsoft.otryx.api.ServiceKey;
import com.peachsoft.otryx.codec.RpcCodecRegistry;
import com.peachsoft.otryx.routing.RpcRouter;
import java.util.Map;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * 核查任意自定义 Router 无法绕过已验证 Provider 集合与实际应用身份。
 *
 * @Author OpenAI
 * @Version 1.0.0-SNAPSHOT
 * @CreateTime 2026/10/21 13:38
 */
class RpcClientRoutingSecurityTest {
    private static final ServiceKey KEY = new ServiceKey(Api.class.getName(), "1.0.0", "default");

    @Test
    void customRouterMustNotForgeAnUnverifiedApplicationOrEndpoint() throws Exception {
        RpcRouter forged = (key, caller, metadata, verified) -> new ServiceInstance[]{
                new ServiceInstance("attacker", new ApplicationKey("security-tests", "unregistered"),
                        key, new RpcEndpoint("127.0.0.1", 29999), 100, Map.of())};
        assertRejectedWithoutTransport(forged, "unverified");
    }

    @Test
    void customRouterMustNotMutateOriginalVerifiedCandidateArray() throws Exception {
        RpcRouter malicious = (key, caller, metadata, candidates) -> {
            candidates[0] = new ServiceInstance("attacker", new ApplicationKey("security-tests", "unregistered"),
                    key, new RpcEndpoint("127.0.0.1", 29999), 100, Map.of());
            return candidates;
        };
        assertRejectedWithoutTransport(malicious, "unverified");
    }

    @Test
    void customRouterMustNotDuplicateValidProvider() throws Exception {
        RpcRouter duplicate = (key, caller, metadata, verified) ->
                new ServiceInstance[]{verified[0], verified[0]};
        assertRejectedWithoutTransport(duplicate, "invalid");
    }

    @Test
    void legitimateRoutingMustRunBeforeLoadBalancerAndUseActualCallerIdentity() throws Exception {
        try (var control = new RpcSecurityTestFixtures.ControlPlane()) {
            var transport = new RpcSecurityTestFixtures.ClientTransport(control.clock);
            AtomicInteger routed = new AtomicInteger();
            RpcRouter router = (key, caller, metadata, verified) -> {
                assertEquals(RpcSecurityTestFixtures.CONSUMER, caller);
                assertEquals(KEY, key);
                routed.incrementAndGet();
                return verified;
            };
            try (OtryxRpcClient client = control.clientBuilder(transport,
                    RpcCodecRegistry.of(new RpcSecurityTestFixtures.TestCodec()))
                    .securityProfile(RpcSecurityProfile.TRUSTED_DEVELOPMENT).router(router).build()) {
                control.publish(Api.class);
                Api api = client.refer(Api.class, "1.0.0", "default");
                ApplicationTestControlPlane.awaitReady(client, KEY);
                assertEquals("ok", api.call());
                assertTrue(routed.get() > 0);
                assertEquals(1, transport.requests.get());
            }
        }
    }

    @Test
    void emptyRouteMustNotSendToAnExcludedProvider() throws Exception {
        RpcRouter rejectAll = (key, caller, metadata, verified) -> new ServiceInstance[0];
        assertRejectedWithoutTransport(rejectAll, "router");
    }

    private static void assertRejectedWithoutTransport(RpcRouter router, String expectedMessage) throws Exception {
        try (var control = new RpcSecurityTestFixtures.ControlPlane()) {
            var transport = new RpcSecurityTestFixtures.ClientTransport(control.clock);
            try (OtryxRpcClient client = control.clientBuilder(transport,
                    RpcCodecRegistry.of(new RpcSecurityTestFixtures.TestCodec()))
                    .securityProfile(RpcSecurityProfile.TRUSTED_DEVELOPMENT).router(router).build()) {
                control.publish(Api.class);
                Api api = client.refer(Api.class, "1.0.0", "default");
                ApplicationTestControlPlane.awaitReady(client, KEY);
                CompletionException rejection = assertThrows(CompletionException.class, api::call);
                assertTrue(rejection.getCause().getMessage().contains(expectedMessage));
                assertEquals(0, transport.requests.get(), "Router rejection cannot reach transport");
            }
        }
    }

    public interface Api {
        String call();
    }
}
