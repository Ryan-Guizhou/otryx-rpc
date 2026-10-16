package com.peachsoft.otryx.registry.consul;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.peachsoft.otryx.api.ApplicationInstance;
import com.peachsoft.otryx.api.ApplicationKey;
import com.peachsoft.otryx.api.ApplicationRole;
import com.peachsoft.otryx.api.RpcEndpoint;
import com.peachsoft.otryx.api.ServiceKey;
import com.peachsoft.otryx.registry.RegistryOptions;
import com.peachsoft.otryx.registry.ServiceMappingOwner;
import com.peachsoft.otryx.spi.ExtensionLoader;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Consul V2 应用 Service/Consumer 端点隔离及 Mapping Fencing JSON 格式单元验收。
 *
 * @Author OpenAI
 * @Version 1.0.0-SNAPSHOT
 * @CreateTime 2026/10/16 14:12
 */
class ConsulV2ModelTest {

    private static final ApplicationKey APP = new ApplicationKey("dev", "orders");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void factoryLoadsThroughNewApplicationRegistrySpi() {
        assertTrue(ExtensionLoader.getLoader(
                com.peachsoft.otryx.registry.ApplicationRegistryFactory.class)
                .getExtension("consul") instanceof ConsulApplicationRegistryFactory);
    }

    @Test
    void providerAndConsumerHaveDifferentNativeServiceAddresses() {
        ApplicationInstance consumer = consumer("consumer-one");
        Map<String, Object> encoded = ConsulV2MemberCodec.encode(consumer, "token-a", 30);
        assertEquals("", encoded.get("Address"));
        assertEquals(0, encoded.get("Port"));
        assertFalse(encoded.containsKey("ServiceKey"));
        assertFalse(consumer.routableProvider());
        ApplicationInstance provider = provider("provider-one", 19501);
        Map<String, Object> providerJson = ConsulV2MemberCodec.encode(provider, "token-a", 30);
        assertEquals("127.0.0.1", providerJson.get("Address"));
        assertEquals(19501, providerJson.get("Port"));
        assertEquals("critical", ((Map<?, ?>) providerJson.get("Check")).get("Status"));
        assertNotEquals(ConsulV2Keys.serviceId(APP, "consumer-one", "inc1"),
                ConsulV2Keys.serviceId(APP, "consumer-one", "inc2"));
    }

    @Test
    void malformedConsumerRpcEndpointAndWrongServiceIdFailClosed() {
        Map<String, Object> raw = ConsulV2MemberCodec.encode(consumer("consumer-one"), "inc1", 30);
        JsonNode healthy = healthy(raw);
        assertEquals(consumer("consumer-one"), ConsulV2MemberCodec.decode(APP, healthy));
        Map<String, Object> nodeAddress = new HashMap<>(raw);
        nodeAddress.put("Address", "127.0.0.1");
        assertEquals(consumer("consumer-one"), ConsulV2MemberCodec.decode(APP, healthy(nodeAddress)));
        assertFalse(ConsulV2MemberCodec.decode(APP, healthy(nodeAddress)).routableProvider());
        Map<String, Object> forged = new HashMap<>(raw);
        forged.put("Port", 19090);
        assertThrows(IllegalArgumentException.class, () ->
                ConsulV2MemberCodec.decode(APP, healthy(forged)));
        forged.put("Port", 0);
        forged.put("ID", "wrong-service-id");
        assertThrows(IllegalArgumentException.class, () ->
                ConsulV2MemberCodec.decode(APP, healthy(forged)));
    }

    @Test
    void ownerCodecPreservesGenerationAndRejectsCrossKeyIdentity() {
        ServiceMappingOwner owner = new ServiceMappingOwner(APP, "node-a", 7);
        ConsulV2MappingCodec.OwnerState original = new ConsulV2MappingCodec.OwnerState(
                owner, "c3a5de62-58be-4a04-a128-64b0be8cc739",
                Set.of(new ServiceKey("demo.Inventory", "1.0.0", "default")));
        JsonNode encoded = ConsulV2MappingCodec.encode(original);
        String key = ConsulV2Keys.owner(APP, owner.instanceId());
        assertEquals(original, ConsulV2MappingCodec.decode(key, APP.namespace(), encoded));
        assertThrows(IllegalArgumentException.class, () ->
                ConsulV2MappingCodec.decode(key + "-forged", APP.namespace(), encoded));
        assertThrows(IllegalArgumentException.class, () ->
                ConsulV2MappingCodec.decode(key, "foreign", encoded));
    }

    @Test
    void invalidNetworkConfigurationCannotEmbedAclSecretOrMultipleAgents() {
        RegistryOptions options = new RegistryOptions(
                List.of("http://user:secret@127.0.0.1:8500"), "dev", Map.of(
                ConsulProtocol.OPTION_TOKEN, "private-token"));
        assertThrows(IllegalArgumentException.class,
                () -> new ConsulApplicationRegistryFactory().create(options));
        assertFalse(options.toString().contains("private-token"));
        assertThrows(IllegalArgumentException.class, () ->
                new ConsulApplicationRegistryFactory().create(new RegistryOptions(
                        List.of("http://127.0.0.1:8500", "http://127.0.0.1:8501"), "dev", Map.of())));
    }

    @Test
    void excessConsulMembershipAttributesRejectedBeforeNetwork() {
        Map<String, String> attributes = new HashMap<>();
        for (int index = 0; index < 64; index++) {
            attributes.put("tag" + index, "v".repeat(256));
        }
        ApplicationInstance member = new ApplicationInstance(
                "node-a", APP, ApplicationRole.CONSUMER, null, 0, null, attributes);
        assertThrows(IllegalArgumentException.class,
                () -> ConsulV2MemberCodec.encode(member, "inc", 30));
    }

    private static JsonNode healthy(Map<String, Object> registration) {
        // Consul Agent register uses Name, while /v1/health/service uses Service.Service.
        Map<String, Object> service = new HashMap<>(registration);
        Object name = service.remove("Name");
        service.put("Service", name);
        return MAPPER.valueToTree(Map.of(
                "Service", service,
                "Node", Map.of("Address", "127.0.0.1"),
                "Checks", List.of(Map.of("Status", "passing"))));
    }

    private static ApplicationInstance consumer(String id) {
        return new ApplicationInstance(id, APP, ApplicationRole.CONSUMER, null, 0, null, Map.of());
    }

    private static ApplicationInstance provider(String id, int port) {
        return new ApplicationInstance(id, APP, ApplicationRole.PROVIDER,
                new RpcEndpoint("127.0.0.1", port), 100,
                "sha256:" + "a".repeat(64), Map.of());
    }
}
