package com.peachsoft.otryx.registry.nacos;

import com.alibaba.nacos.api.naming.pojo.Instance;
import com.peachsoft.otryx.api.RpcEndpoint;
import com.peachsoft.otryx.api.ServiceInstance;
import com.peachsoft.otryx.api.ServiceKey;
import java.util.HashMap;
import java.util.Map;

/**
 * Nacos Instance 与 OTRYX RPC ServiceInstance 的映射器。
 *
 * @Author Ryan
 * @Version 1.0.0
 * @CreateTime 2026/9/28 11:35
 */
final class NacosInstanceMapper {

    static final int CORE_WEIGHT_SCALE = 100;

    private NacosInstanceMapper() {
    }

    static Instance toNacos(ServiceInstance source, String cluster) {
        if (!routableHost(source.endpoint().host())) {
            throw new IllegalArgumentException(
                    "Nacos registration endpoint host must be routable");
        }
        if (source.endpoint().port() <= 0) {
            throw new IllegalArgumentException(
                    "Nacos registration endpoint port must be positive");
        }
        double nacosWeight =
                source.weight() / (double) CORE_WEIGHT_SCALE;
        if (!Double.isFinite(nacosWeight)
                || nacosWeight <= 0D) {
            throw new IllegalArgumentException(
                    "Nacos registration weight must be positive and finite");
        }
        Map<String, String> metadata = new HashMap<>(source.metadata());
        for (String key : metadata.keySet()) {
            if (NacosReservedMetadata.adapterOwned(key)) {
                throw new IllegalArgumentException(
                        "RPC metadata must not override Nacos adapter key: "
                                + key);
            }
        }
        metadata.put(NacosReservedMetadata.INSTANCE_ID, source.instanceId());
        metadata.put(
                NacosReservedMetadata.INTERFACE,
                source.serviceKey().serviceName());
        metadata.put(
                NacosReservedMetadata.VERSION,
                source.serviceKey().version());
        metadata.put(
                NacosReservedMetadata.GROUP,
                source.serviceKey().group());
        metadata.put(NacosReservedMetadata.PROTOCOL, NacosReservedMetadata.PROTOCOL_NAME);
        metadata.put(NacosReservedMetadata.CLUSTER, cluster);

        Instance target = new Instance();
        target.setIp(source.endpoint().host());
        target.setPort(source.endpoint().port());
        target.setWeight(nacosWeight);
        target.setClusterName(cluster);
        target.setEphemeral(true);
        target.setEnabled(true);
        target.setMetadata(metadata);
        return target;
    }

    static ServiceInstance fromNacos(ServiceKey key, Instance source) {
        if (source == null
                || !source.isHealthy()
                || !source.isEnabled()
                || source.getWeight() <= 0D
                || !routableHost(source.getIp())
                || source.getPort() <= 0
                || source.getPort() > 65_535) {
            return null;
        }
        double scaled = source.getWeight() * CORE_WEIGHT_SCALE;
        if (!Double.isFinite(scaled) || scaled > Integer.MAX_VALUE) {
            return null;
        }
        int weight = Math.max(1, (int) Math.round(scaled));
        Map<String, String> metadata = source.getMetadata() == null
                ? new HashMap<>()
                : new HashMap<>(source.getMetadata());
        String instanceId = metadata.get(NacosReservedMetadata.INSTANCE_ID);
        if (instanceId == null || instanceId.isBlank()) {
            instanceId = fallbackInstanceId(key, source);
        }
        return new ServiceInstance(
                instanceId,
                key,
                new RpcEndpoint(source.getIp(), source.getPort()),
                weight,
                Map.copyOf(metadata));
    }

    private static boolean routableHost(String host) {
        return host != null
                && !host.isBlank()
                && !RpcEndpoint.UNSPECIFIED_IPV4_HOST.equals(host)
                && !RpcEndpoint.UNSPECIFIED_IPV6_HOST.equals(host)
                && !RpcEndpoint.UNSPECIFIED_IPV6_BRACKETED_HOST.equals(host);
    }

    private static String fallbackInstanceId(
            ServiceKey key,
            Instance source) {
        String cluster = source.getClusterName() == null
                ? NacosRegistryFactory.DEFAULT_CLUSTER
                : source.getClusterName();
        return key.canonicalName()
                + '@'
                + cluster
                + '@'
                + source.getIp()
                + ':'
                + source.getPort();
    }
}
