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
import com.peachsoft.otryx.registry.RegistrySecurityException;
import com.peachsoft.otryx.registry.RegistrySecurityFailure;
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
        assertEquals(RegistrySecurityFailure.METADATA_INTEGRITY_REJECTED,
                assertThrows(RegistrySecurityException.class,
                        () -> identity.fromHealth(APPLICATION, health(identity, consumer, meta, "", 9090))).reason());
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
        assertEquals(RegistrySecurityFailure.METADATA_REFERENCE_MISMATCH,
                assertThrows(RegistrySecurityException.class,
                        () -> identity.fromHealth(new ApplicationKey("production", "other"),
                                health(identity, consumer, identity.metadata(consumer, "token"), "", 0))).reason());
        ApplicationInstance oversized = new ApplicationInstance("consumer", APPLICATION,
                ApplicationRole.CONSUMER, null, 0, null, Map.of("key", "a".repeat(513)));
        assertThrows(IllegalArgumentException.class, () -> identity.metadata(oversized, "token"));
        // Java String.length() is insufficient for Consul native Meta UTF-8 byte caps.
        ApplicationInstance utf8Oversized = new ApplicationInstance("consumer", APPLICATION,
                ApplicationRole.CONSUMER, null, 0, null, Map.of("description", "汉".repeat(200)));
        assertThrows(IllegalArgumentException.class, () -> identity.metadata(utf8Oversized, "token"));
    }


    @Test
    void nonpassingHealthCannotHideMalformedDeclaredRole() {
        ConsulV2Identity identity = new ConsulV2Identity("production");
        ApplicationInstance member = provider("provider-1");
        Map<String, String> metadata = new java.util.HashMap<>(identity.metadata(member, "token"));
        metadata.put("otryxV2Role", "FORGED");
        com.fasterxml.jackson.databind.node.ObjectNode entry = health(
                identity, member, metadata, "127.0.0.1", 19090).deepCopy();
        entry.putArray(ConsulProtocol.FIELD_CHECKS).addObject()
                .put(ConsulProtocol.FIELD_STATUS, "critical");
        assertEquals(RegistrySecurityFailure.METADATA_INTEGRITY_REJECTED,
                assertThrows(RegistrySecurityException.class,
                        () -> identity.fromHealth(APPLICATION, entry)).reason());
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
