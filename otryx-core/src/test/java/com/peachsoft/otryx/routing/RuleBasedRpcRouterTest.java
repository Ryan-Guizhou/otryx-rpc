package com.peachsoft.otryx.routing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.peachsoft.otryx.api.ApplicationKey;
import com.peachsoft.otryx.api.RpcEndpoint;
import com.peachsoft.otryx.api.ServiceInstance;
import com.peachsoft.otryx.api.ServiceKey;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * Router 规则的原子变更、完整应用约束与灰度分桶的确定性验证。
 *
 * @Author OpenAI
 * @Version 1.0.0-SNAPSHOT
 * @CreateTime 2026/10/21 13:38
 */
class RuleBasedRpcRouterTest {
    private static final ServiceKey SERVICE = new ServiceKey("com.example.Greeting", "1.0.0", "default");
    private static final ApplicationKey BLUE = new ApplicationKey("tenant", "blue");
    private static final ApplicationKey GREEN = new ApplicationKey("tenant", "green");
    private static final ApplicationKey OTHER = new ApplicationKey("another-tenant", "blue");

    @Test
    void noRuleMustReuseVerifiedArrayWithoutAllocatingAnotherCandidateSet() {
        RuleBasedRpcRouter router = new RuleBasedRpcRouter();
        ServiceInstance[] instances = {provider(BLUE, 19001, Map.of())};
        assertFalse(router.hasRules(SERVICE));
        assertTrue(instances == router.route(SERVICE, BLUE, Map.of(), instances));
        assertEquals(0L, router.snapshot().revision());
    }

    @Test
    void applicationTagZoneAndReleaseMustSelectOnlyMatchingVerifiedProvider() {
        RuleBasedRpcRouter router = new RuleBasedRpcRouter();
        RpcRouteRule rule = new RpcRouteRule(Set.of(BLUE), List.of(BLUE),
                Map.of("tier", "gold"), "west", "release-2", null);
        assertTrue(router.publish(new RpcRoutingSnapshot(1, Map.of(SERVICE, rule))));
        ServiceInstance matched = provider(BLUE, 19001,
                Map.of("tier", "gold", "zone", "west", "release", "release-2"));
        ServiceInstance[] instances = {
                matched,
                provider(BLUE, 19002, Map.of("tier", "gold", "zone", "east", "release", "release-2")),
                provider(GREEN, 19003, Map.of("tier", "gold", "zone", "west", "release", "release-2")),
                provider(OTHER, 19004, Map.of("tier", "gold", "zone", "west", "release", "release-2"))
        };
        ServiceInstance[] routed = router.route(SERVICE, BLUE, Map.of(), instances);
        assertEquals(1, routed.length);
        assertTrue(routed[0] == matched);
        assertEquals(4, instances.length, "A router must not alter the input array");
    }

    @Test
    void preferredApplicationMustBeChosenBeforeLoadBalancingAndFallbackWhenUnavailable() {
        RuleBasedRpcRouter router = new RuleBasedRpcRouter();
        RpcRouteRule rule = new RpcRouteRule(Set.of(BLUE, GREEN), List.of(GREEN, BLUE),
                Map.of(), null, null, null);
        router.publish(new RpcRoutingSnapshot(1, Map.of(SERVICE, rule)));
        ServiceInstance blue = provider(BLUE, 19001, Map.of());
        ServiceInstance green = provider(GREEN, 19002, Map.of());
        assertTrue(router.route(SERVICE, BLUE, Map.of(), new ServiceInstance[]{blue, green})[0] == green);
        assertTrue(router.route(SERVICE, BLUE, Map.of(), new ServiceInstance[]{blue})[0] == blue);
        assertEquals(0, router.route(SERVICE, BLUE, Map.of(),
                new ServiceInstance[]{provider(OTHER, 19003, Map.of())}).length);
    }

    @Test
    void tenPercentGrayMustRemainWithinStatisticalTolerance() {
        RuleBasedRpcRouter router = new RuleBasedRpcRouter();
        router.publish(new RpcRoutingSnapshot(1, Map.of(SERVICE,
                new RpcRouteRule(Set.of(), List.of(), Map.of(), null, null, 10))));
        ServiceInstance stable = provider(BLUE, 19001, Map.of("track", "stable"));
        ServiceInstance gray = provider(BLUE, 19002, Map.of("track", "gray"));
        ServiceInstance[] verified = {stable, gray};
        AtomicInteger grayCount = new AtomicInteger();
        final int iterations = 10_000;
        for (int index = 0; index < iterations; index++) {
            ServiceInstance[] selected = router.route(SERVICE, BLUE, Map.of(), verified);
            assertEquals(1, selected.length);
            if (selected[0] == gray) {
                grayCount.incrementAndGet();
            } else {
                assertTrue(selected[0] == stable);
            }
        }
        assertTrue(grayCount.get() >= 700 && grayCount.get() <= 1300,
                "Expected the configured 10/90 statistical rollout");
    }

    @Test
    void missingGrayTrackMustReturnEmptyRatherThanFallbackToUnselectedApplication() {
        RuleBasedRpcRouter router = new RuleBasedRpcRouter();
        router.publish(new RpcRoutingSnapshot(1, Map.of(SERVICE,
                new RpcRouteRule(Set.of(), List.of(), Map.of(), null, null, 100))));
        assertEquals(0, router.route(SERVICE, BLUE, Map.of(),
                new ServiceInstance[]{provider(BLUE, 19001, Map.of("track", "stable"))}).length);
    }

    @Test
    void rollbackMustUseNewerRevisionAndRejectStaleOrInvalidUpdates() {
        RuleBasedRpcRouter router = new RuleBasedRpcRouter();
        RpcRoutingSnapshot first = new RpcRoutingSnapshot(1, Map.of(SERVICE,
                new RpcRouteRule(Set.of(BLUE), List.of(), Map.of(), null, null, null)));
        RpcRoutingSnapshot second = new RpcRoutingSnapshot(2, Map.of(SERVICE,
                new RpcRouteRule(Set.of(GREEN), List.of(), Map.of(), null, null, null)));
        assertTrue(router.publish(first));
        assertTrue(router.publish(second));
        assertFalse(router.publish(first));
        assertFalse(router.rollback(first, 2));
        assertTrue(router.rollback(first, 3));
        assertEquals(3, router.snapshot().revision());
        assertEquals(Set.of(BLUE), router.snapshot().rules().get(SERVICE).allowedApplications());
        assertThrows(IllegalArgumentException.class, () -> new RpcRoutingSnapshot(-1, Map.of()));
        assertThrows(IllegalArgumentException.class, () -> new RpcRouteRule(
                Set.of(BLUE), List.of(GREEN), Map.of(), null, null, null));
        assertThrows(IllegalArgumentException.class, () -> new RpcRouteRule(
                Set.of(), List.of(), Map.of(), null, null, 101));
    }

    private static ServiceInstance provider(ApplicationKey application, int port, Map<String, String> metadata) {
        return new ServiceInstance("instance-" + port, application, SERVICE,
                new RpcEndpoint("127.0.0.1", port), 100, metadata);
    }
}
