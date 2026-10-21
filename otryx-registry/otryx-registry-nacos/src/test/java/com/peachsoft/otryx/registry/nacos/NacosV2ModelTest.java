package com.peachsoft.otryx.registry.nacos;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.alibaba.nacos.api.naming.pojo.Instance;
import com.peachsoft.otryx.api.ApplicationInstance;
import com.peachsoft.otryx.api.ApplicationKey;
import com.peachsoft.otryx.api.ApplicationRole;
import com.peachsoft.otryx.api.RpcEndpoint;
import com.peachsoft.otryx.api.ServiceKey;
import com.peachsoft.otryx.registry.RegistrySecurityException;
import com.peachsoft.otryx.registry.RegistrySecurityFailure;
import com.peachsoft.otryx.registry.ServiceMappingOwner;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * 不依赖 Nacos 服务端的 Consumer 无端点和分片 CAS 编码回归。
 *
 * @Author OpenAI
 * @Version 1.0.0-SNAPSHOT
 * @CreateTime 2026/10/16 13:42
 */
class NacosV2ModelTest {

    private static final ApplicationKey APP = new ApplicationKey("public", "orders");
    private static final String CLUSTER = "DEFAULT";

    @Test
    void consumerUsesZeroNamingPortButHasNoRpcEndpoint() {
        ApplicationInstance consumer = consumer("process-a");
        Instance record = NacosV2InstanceMapper.encode(
                consumer, CLUSTER, "127.0.0.1", "fencing-a");
        assertEquals(0, record.getPort());
        assertTrue(record.isEphemeral());
        assertEquals(0D, record.getWeight());
        assertEquals(consumer, NacosV2InstanceMapper.decode(APP, CLUSTER, record));
        assertFalse(NacosV2InstanceMapper.decode(APP, CLUSTER, record).routableProvider());
    }

    @Test
    void consumersOnSameHostHaveSeparatePhysicalClusterIdentity() {
        Instance left = NacosV2InstanceMapper.encode(consumer("left"), CLUSTER,
                "127.0.0.1", "token-left");
        Instance right = NacosV2InstanceMapper.encode(consumer("right"), CLUSTER,
                "127.0.0.1", "token-right");
        assertEquals(left.getIp(), right.getIp());
        assertEquals(0, right.getPort());
        assertNotEquals(left.getClusterName(), right.getClusterName());
    }

    @Test
    void providerPublishesActualEndpointAndImmutableAttributes() {
        ApplicationInstance provider = new ApplicationInstance("node-a", APP, ApplicationRole.BOTH,
                new RpcEndpoint("127.0.0.1", 19101), 100,
                "sha256:" + "a".repeat(NacosV2Names.SHA_HEX_LENGTH),
                Map.of("zone", "east", "unused", ""));
        Instance encoded = NacosV2InstanceMapper.encode(
                provider, CLUSTER, "127.0.0.1", "token-provider");
        assertEquals(19101, encoded.getPort());
        assertEquals(provider, NacosV2InstanceMapper.decode(APP, CLUSTER, encoded));
        assertTrue(NacosV2InstanceMapper.decode(APP, CLUSTER, encoded).routableProvider());
    }

    @Test
    void wrongIdentityOrForgedConsumerPortMustFailClosed() {
        Instance member = NacosV2InstanceMapper.encode(consumer("node-a"), CLUSTER,
                "127.0.0.1", "token");
        member.setPort(19090);
        assertThrows(IllegalArgumentException.class,
                () -> NacosV2InstanceMapper.decode(APP, CLUSTER, member));
        assertThrows(IllegalArgumentException.class,
                () -> NacosV2InstanceMapper.encode(consumer("node-b"),
                        CLUSTER, "0.0.0.0", "token"));
    }

    @Test
    void shardCodecPreservesGenerationAndRejectsCrossShard() {
        ServiceMappingOwner owner = new ServiceMappingOwner(APP, "instance-a", 77);
        int shard = NacosV2Names.shard(APP, "instance-a");
        ServiceKey one = new ServiceKey("demo.Inventory", "1.0.0", "default");
        ServiceKey two = new ServiceKey("demo.Payment", "1.0.0", "default");
        NacosV2MappingCodec.OwnerState original = new NacosV2MappingCodec.OwnerState(
                owner, "token-1", Set.of(one, two));
        String key = NacosV2MappingCodec.identifier(owner);
        String bytes = NacosV2MappingCodec.encode(Map.of(key, original));
        assertEquals(original, NacosV2MappingCodec.decode(bytes, APP.namespace(), shard).get(key));
        assertEquals(RegistrySecurityFailure.METADATA_REFERENCE_MISMATCH,
                assertThrows(RegistrySecurityException.class,
                        () -> NacosV2MappingCodec.decode(bytes, APP.namespace(),
                                (shard + 1) % NacosV2Names.MAPPING_SHARDS)).reason());
        assertThrows(IllegalArgumentException.class,
                () -> NacosV2MappingCodec.decode(bytes + "x", APP.namespace(), shard));
    }

    @Test
    void invalidServiceKeyCannotEnterMapping() {
        ServiceMappingOwner owner = new ServiceMappingOwner(APP, "instance-a", 1);
        NacosV2MappingCodec.OwnerState bad = new NacosV2MappingCodec.OwnerState(
                owner, "token-1", Set.of(new ServiceKey("bad/service", "1.0", "default")));
        assertThrows(IllegalArgumentException.class,
                () -> NacosV2MappingCodec.encode(Map.of(NacosV2MappingCodec.identifier(owner), bad)));
    }

    @ParameterizedTest
    @ValueSource(ints = {50, 100, 200})
    void providerWeightsRoundTripWithoutLosingRoutingAttributes(int weight) {
        ApplicationInstance provider = new ApplicationInstance("weighted", APP, ApplicationRole.PROVIDER,
                new RpcEndpoint("127.0.0.1", 19101), weight, "sha256:" + "a".repeat(64),
                Map.of("zone", "east", "otryx.v2.role", "CONSUMER"));
        Instance encoded = NacosV2InstanceMapper.encode(provider, CLUSTER, "127.0.0.1", "token");
        assertEquals(weight / 100D, encoded.getWeight());
        assertEquals(provider, NacosV2InstanceMapper.decode(APP, CLUSTER, encoded));
        assertTrue(NacosV2InstanceMapper.decode(APP, CLUSTER, encoded).routableProvider());
    }

    @Test
    void unhealthyDisabledAndForeignClusterMembersAreExcluded() {
        Instance encoded = encodedProvider();
        encoded.setHealthy(false);
        assertNull(NacosV2InstanceMapper.decode(APP, CLUSTER, encoded));
        encoded.setHealthy(true);
        encoded.setEnabled(false);
        assertNull(NacosV2InstanceMapper.decode(APP, CLUSTER, encoded));
        encoded.setEnabled(true);
        assertNull(NacosV2InstanceMapper.decode(APP, "OTHER", encoded));
    }

    @Test
    void invalidProviderWeightEndpointAndIdentityMustFailClosed() {
        for (double weight : new double[] {0D, -1D, Double.NaN, Double.POSITIVE_INFINITY}) {
            Instance encoded = encodedProvider();
            encoded.setWeight(weight);
            assertThrows(IllegalArgumentException.class,
                    () -> NacosV2InstanceMapper.decode(APP, CLUSTER, encoded));
        }
        Instance invalidPort = encodedProvider();
        invalidPort.setPort(0);
        assertThrows(IllegalArgumentException.class,
                () -> NacosV2InstanceMapper.decode(APP, CLUSTER, invalidPort));
        Instance wildcard = encodedProvider();
        wildcard.setIp("0.0.0.0");
        assertThrows(IllegalArgumentException.class,
                () -> NacosV2InstanceMapper.decode(APP, CLUSTER, wildcard));
        assertEquals(RegistrySecurityFailure.METADATA_REFERENCE_MISMATCH,
                assertThrows(RegistrySecurityException.class,
                        () -> NacosV2InstanceMapper.decode(new ApplicationKey("foreign", APP.applicationName()),
                                CLUSTER, encodedProvider())).reason());
        assertThrows(IllegalArgumentException.class,
                () -> NacosV2InstanceMapper.encode(new ApplicationInstance("wildcard", APP,
                        ApplicationRole.PROVIDER, new RpcEndpoint("0.0.0.0", 19101), 100,
                        "sha256:" + "a".repeat(64), Map.of()), CLUSTER, "127.0.0.1", "token"));
    }


    @Test
    void declaredV2MetadataCannotBecomeHealthyEmptyByRemovingOwnershipFields() {
        for (String field : List.of("token", "cluster")) {
            Instance source = encodedProvider();
            Map<String, String> fields = new java.util.HashMap<>(source.getMetadata());
            fields.remove(NacosV2Names.METADATA_PREFIX + field);
            source.setMetadata(fields);
            assertEquals(RegistrySecurityFailure.METADATA_INTEGRITY_REJECTED,
                    assertThrows(RegistrySecurityException.class,
                            () -> NacosV2InstanceMapper.decode(APP, CLUSTER, source)).reason());
        }
    }

    @Test
    void unhealthyStatusCannotHideForeignOwnership() {
        Instance source = encodedProvider();
        source.setHealthy(false);
        assertEquals(RegistrySecurityFailure.METADATA_REFERENCE_MISMATCH,
                assertThrows(RegistrySecurityException.class,
                        () -> NacosV2InstanceMapper.decode(new ApplicationKey("foreign", APP.applicationName()),
                                CLUSTER, source)).reason());
    }

    @Test
    void oversizedAndMalformedUtf8MemberMetadataMustFailClosed() {
        Instance source = encodedProvider();
        Map<String, String> fields = new java.util.HashMap<>(source.getMetadata());
        fields.put("extra", "x".repeat(8193));
        source.setMetadata(fields);
        assertEquals(RegistrySecurityFailure.RESPONSE_LIMIT_EXCEEDED,
                assertThrows(RegistrySecurityException.class,
                        () -> NacosV2InstanceMapper.decode(APP, CLUSTER, source)).reason());
        Instance malformed = encodedProvider();
        fields = new java.util.HashMap<>(malformed.getMetadata());
        String invalidKey = java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString(new byte[] {(byte) 0xc3, 0x28});
        fields.put(NacosV2Names.METADATA_PREFIX + "attribute." + invalidKey, "value");
        malformed.setMetadata(fields);
        assertEquals(RegistrySecurityFailure.METADATA_INTEGRITY_REJECTED,
                assertThrows(RegistrySecurityException.class,
                        () -> NacosV2InstanceMapper.decode(APP, CLUSTER, malformed)).reason());
    }

    @Test
    void namingIdentityUsesApplicationAndLeavesNamespaceToSdkScope() {
        assertEquals(NacosV2Names.serviceName(APP),
                NacosV2Names.serviceName(new ApplicationKey(APP.namespace(), APP.applicationName())));
        assertEquals(NacosV2Names.serviceName(APP),
                NacosV2Names.serviceName(new ApplicationKey("foreign", APP.applicationName())));
        assertNotEquals(NacosV2Names.serviceName(APP),
                NacosV2Names.serviceName(new ApplicationKey(APP.namespace(), "payments")));
    }

    private static Instance encodedProvider() {
        return NacosV2InstanceMapper.encode(new ApplicationInstance("node-a", APP, ApplicationRole.PROVIDER,
                new RpcEndpoint("127.0.0.1", 19101), 100, "sha256:" + "a".repeat(64), Map.of()),
                CLUSTER, "127.0.0.1", "token-provider");
    }

    private static ApplicationInstance consumer(String id) {
        return new ApplicationInstance(id, APP, ApplicationRole.CONSUMER, null, 0, null, Map.of());
    }
}
