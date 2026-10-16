package com.peachsoft.otryx.registry.etcd;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.peachsoft.otryx.api.ApplicationInstance;
import com.peachsoft.otryx.api.ApplicationKey;
import com.peachsoft.otryx.api.ApplicationRole;
import com.peachsoft.otryx.api.RpcEndpoint;
import com.peachsoft.otryx.api.ServiceKey;
import com.peachsoft.otryx.registry.ServiceMappingOwner;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Etcd V2 存储编解码和隔离键空间的正例、畸形与容量回归。
 *
 * @Author OpenAI
 * @Version 1.0.0-SNAPSHOT
 * @CreateTime 2026/10/16 10:33
 */
class EtcdV2CodecTest {

    private static final ApplicationKey APP = new ApplicationKey("test", "orders");

    @Test
    void consumerHasMembershipWithoutRoutableEndpoint() {
        ApplicationInstance consumer = new ApplicationInstance(
                "consumer-1", APP, ApplicationRole.CONSUMER, null, 0, null, Map.of("zone", "east"));
        EtcdV2Codec.MemberRecord decoded = EtcdV2Codec.decodeMember(
                EtcdV2Codec.encodeMember(consumer, "consumer-token"));
        assertEquals(consumer, decoded.instance());
        assertEquals("consumer-token", decoded.token());
        assertFalse(decoded.instance().routableProvider());
    }

    @Test
    void providerAttributesAndEmptyAttributeValuesRoundTrip() {
        ApplicationInstance provider = new ApplicationInstance(
                "provider-1", APP, ApplicationRole.BOTH,
                new RpcEndpoint("127.0.0.1", 18081), 100, "sha256:" + "a".repeat(64),
                Map.of("zone", "west", "unused", ""));
        EtcdV2Codec.MemberRecord decoded = EtcdV2Codec.decodeMember(
                EtcdV2Codec.encodeMember(provider, "provider-token"));
        assertEquals(provider, decoded.instance());
        assertTrue(decoded.instance().routableProvider());
        assertEquals("", decoded.instance().attributes().get("unused"));
    }

    @Test
    void ownerSupportsMultipleServicesAndPersistentTombstone() {
        ServiceMappingOwner owner = new ServiceMappingOwner(APP, "provider-1", 12);
        Set<ServiceKey> services = Set.of(
                new ServiceKey("test.Greeting", "1.0", "default"),
                new ServiceKey("test.Payment", "2.0", "west"));
        EtcdV2Codec.OwnerRecord active = EtcdV2Codec.decodeOwner(
                EtcdV2Codec.encodeOwner(owner, "claim-token", services));
        assertEquals(owner, active.owner());
        assertEquals(services, active.services());
        assertEquals(Set.of(), EtcdV2Codec.decodeOwner(
                EtcdV2Codec.encodeOwner(owner, "claim-token", Set.of())).services());
    }

    @Test
    void malformedValuesMustNotBeAcceptedAsEmptyDirectories() {
        ApplicationInstance instance = new ApplicationInstance(
                "node-a", APP, ApplicationRole.PROVIDER,
                new RpcEndpoint("127.0.0.1", 18080), 100, "manifest", Map.of());
        byte[] good = EtcdV2Codec.encodeMember(instance, "token");
        assertThrows(IllegalArgumentException.class,
                () -> EtcdV2Codec.decodeMember(Arrays.copyOf(good, good.length - 1)));
        byte[] oversized = new byte[EtcdV2Codec.MAX_MEMBER_BYTES + 1];
        assertThrows(IllegalArgumentException.class, () -> EtcdV2Codec.decodeMember(oversized));
        assertThrows(IllegalArgumentException.class, () -> EtcdV2Codec.decodeOwner(new byte[] {1, 2}));
    }

    @Test
    void pathsAreApplicationScopedAndRejectCrossNamespace() {
        EtcdV2Paths paths = new EtcdV2Paths("test");
        String path = paths.memberKey(APP, "node/a|b");
        assertTrue(path.startsWith("/otryx/v2/ns/"));
        assertFalse(path.contains("node/a|b"));
        assertTrue(path.startsWith(paths.memberPrefix(APP)));
        assertThrows(IllegalArgumentException.class,
                () -> paths.memberPrefix(new ApplicationKey("foreign", "orders")));
    }
}
