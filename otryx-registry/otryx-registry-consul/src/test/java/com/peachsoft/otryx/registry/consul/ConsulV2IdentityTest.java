package com.peachsoft.otryx.registry.consul;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.peachsoft.otryx.api.ApplicationInstance;
import com.peachsoft.otryx.api.ApplicationKey;
import com.peachsoft.otryx.api.ApplicationRole;
import com.peachsoft.otryx.api.RpcEndpoint;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Consul V2 应用成员身份的编码、跨应用隔离及 Consumer 不可路由校验。
 *
 * @Author OpenAI
 * @Version 1.0.0-SNAPSHOT
 * @CreateTime 2026/10/16 14:50
 */
class ConsulV2IdentityTest {

    private static final ApplicationKey APPLICATION = new ApplicationKey("production", "orders");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void oneServicePerApplicationAndDistinctInstanceIds() {
        ConsulV2Identity identity = new ConsulV2Identity("production");
        ApplicationInstance first = provider("provider-1");
        ApplicationInstance second = provider("provider-2");
        assertEquals(identity.serviceName(first.application()), identity.serviceName(second.application()));
        assertTrue(!identity.serviceId(first).equals(identity.serviceId(second)));
        assertThrows(IllegalArgumentException.class,
                () -> identity.serviceName(new ApplicationKey("other", "orders")));
    }

    @Test
    void consumerWithoutEndpointRemainsDiscoverableButNotRoutable() {
        ConsulV2Identity identity = new ConsulV2Identity("production");
        ApplicationInstance consumer = new ApplicationInstance(
                "consumer-1", APPLICATION, ApplicationRole.CONSUMER, null, 0, null, Map.of("zone", "sg"));
        Map<String, String> meta = identity.metadata(consumer, "process-token");
        JsonNode entry = health(identity, consumer, meta, "", 0);
        ApplicationInstance decoded = identity.fromHealth(APPLICATION, entry);
        assertEquals(consumer, decoded);
        assertTrue(!decoded.routableProvider());
        assertNull(decoded.rpcEndpoint());
        assertNull(identity.fromHealth(APPLICATION, health(identity, consumer, meta, "", 9090)));
    }

    @Test
    void providerRequiresRealEndpointAndPassingCheck() {
        ConsulV2Identity identity = new ConsulV2Identity("production");
        ApplicationInstance provider = provider("provider-1");
        Map<String, String> meta = identity.metadata(provider, "token");
        assertEquals(provider, identity.fromHealth(APPLICATION,
                health(identity, provider, meta, "127.0.0.1", 19090)));
        assertNull(identity.fromHealth(APPLICATION,
                health(identity, provider, meta, "0.0.0.0", 19090)));
        assertNull(identity.fromHealth(APPLICATION,
                health(identity, provider, meta, "127.0.0.1", 0)));
    }

    @Test
    void rejectsForeignApplicationAndOversizedMetadata() {
        ConsulV2Identity identity = new ConsulV2Identity("production");
        ApplicationInstance consumer = new ApplicationInstance(
                "consumer", APPLICATION, ApplicationRole.CONSUMER, null, 0, null, Map.of());
        assertNull(identity.fromHealth(new ApplicationKey("production", "other"),
                health(identity, consumer, identity.metadata(consumer, "token"), "", 0)));
        ApplicationInstance oversized = new ApplicationInstance("consumer", APPLICATION,
                ApplicationRole.CONSUMER, null, 0, null, Map.of("key", "a".repeat(513)));
        assertThrows(IllegalArgumentException.class, () -> identity.metadata(oversized, "token"));
    }

    private static ApplicationInstance provider(String instanceId) {
        return new ApplicationInstance(instanceId, APPLICATION, ApplicationRole.PROVIDER,
                new RpcEndpoint("127.0.0.1", 19090), 100, "revision-1", Map.of());
    }

    private static JsonNode health(
            ConsulV2Identity identity,
            ApplicationInstance instance,
            Map<String, String> meta,
            String address,
            int port) {
        return MAPPER.valueToTree(Map.of(
                "Service", Map.of("ID", identity.serviceId(instance),
                        "Service", identity.serviceName(instance.application()),
                        "Address", address, "Port", port, "Meta", meta),
                "Checks", java.util.List.of(Map.of("Status", "passing")),
                "Node", Map.of("Address", "127.0.0.1")));
    }
}
