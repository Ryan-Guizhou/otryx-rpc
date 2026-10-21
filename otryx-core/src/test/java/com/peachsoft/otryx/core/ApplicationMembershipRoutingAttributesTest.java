package com.peachsoft.otryx.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.peachsoft.otryx.api.ApplicationInstance;
import com.peachsoft.otryx.api.ApplicationKey;
import com.peachsoft.otryx.api.ApplicationRole;
import com.peachsoft.otryx.api.ExportedService;
import com.peachsoft.otryx.api.RpcEndpoint;
import com.peachsoft.otryx.api.ServiceKey;
import com.peachsoft.otryx.registry.memory.MemoryApplicationRegistry;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 确认 Spring/Builder 所用同一成员协调器发布真实路由属性，不伪造 Consumer 端点。
 *
 * @Author OpenAI
 * @Version 1.0.0-SNAPSHOT
 * @CreateTime 2026/10/21 13:38
 */
class ApplicationMembershipRoutingAttributesTest {
    private static final ApplicationKey APPLICATION = new ApplicationKey("routing", "service");
    private static final Map<String, String> LABELS = Map.of(
            "zone", "cn-shanghai", "release", "v2", "track", "stable");

    @Test
    void providerAndConsumerRolesMustRetainImmutablePublicRoutingAttributes() {
        try (MemoryApplicationRegistry registry = new MemoryApplicationRegistry()) {
            ApplicationMembershipCoordinator membership = new ApplicationMembershipCoordinator(
                    APPLICATION, "member-1", registry, registry.manifestRepository(),
                    registry.serviceMappingRepository(), Duration.ofSeconds(3), LABELS);
            try {
                membership.startConsumer().toCompletableFuture().join();
                ApplicationInstance consumer = member(registry);
                assertEquals(ApplicationRole.CONSUMER, consumer.role());
                assertEquals(LABELS, consumer.attributes());
                assertNull(consumer.rpcEndpoint());
                assertFalse(consumer.routableProvider());

                ServiceKey service = new ServiceKey(Api.class.getName(), "1.0.0", "default");
                membership.prepareProvider(List.of(ExportedService.fromContract(service, Api.class)))
                        .toCompletableFuture().join();
                RpcEndpoint endpoint = new RpcEndpoint("127.0.0.1", 19090);
                membership.providerReady(endpoint).toCompletableFuture().join();
                ApplicationInstance both = member(registry);
                assertEquals(ApplicationRole.BOTH, both.role());
                assertEquals(endpoint, both.rpcEndpoint());
                assertEquals(LABELS, both.attributes());
                assertTrue(both.routableProvider());
                membership.stopProvider().toCompletableFuture().join();

                ApplicationInstance restored = member(registry);
                assertEquals(ApplicationRole.CONSUMER, restored.role());
                assertNull(restored.rpcEndpoint());
                assertFalse(restored.routableProvider());
                assertEquals(LABELS, restored.attributes());
            } finally {
                membership.shutdown().toCompletableFuture().join();
            }
        }
    }

    private static ApplicationInstance member(MemoryApplicationRegistry registry) {
        var directory = registry.lookup(APPLICATION).toCompletableFuture().join();
        assertEquals(1, directory.instances().size());
        return directory.instances().getFirst();
    }

    public interface Api {
        String call();
    }
}
