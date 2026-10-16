package com.peachsoft.otryx.registry.nacos;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.alibaba.nacos.api.naming.pojo.Instance;
import com.peachsoft.otryx.api.ApplicationInstance;
import com.peachsoft.otryx.api.ApplicationKey;
import com.peachsoft.otryx.api.ApplicationRole;
import com.peachsoft.otryx.api.RpcEndpoint;
import com.peachsoft.otryx.api.ServiceKey;
import com.peachsoft.otryx.registry.ServiceMappingOwner;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

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
        assertThrows(IllegalArgumentException.class,
                () -> NacosV2MappingCodec.decode(bytes, APP.namespace(),
                        (shard + 1) % NacosV2Names.MAPPING_SHARDS));
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

    private static ApplicationInstance consumer(String id) {
        return new ApplicationInstance(id, APP, ApplicationRole.CONSUMER, null, 0, null, Map.of());
    }
}
