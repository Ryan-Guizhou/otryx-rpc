package com.peachsoft.otryx.registry.nacos;

import com.alibaba.nacos.api.naming.pojo.Instance;
import com.peachsoft.otryx.api.ApplicationInstance;
import com.peachsoft.otryx.api.ApplicationKey;
import com.peachsoft.otryx.api.ApplicationManifest;
import com.peachsoft.otryx.api.ApplicationRole;
import com.peachsoft.otryx.api.RpcEndpoint;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Nacos V2 成员映射：Naming 的 ip/port 是定位字段，而非自动可信 RPC Endpoint。
 *
 * <p>纯 Consumer 使用真实的本机 IP 和 Nacos SDK 支持的 port=0，
 * RPC Endpoint 始终为 null。物理 cluster 内含进程 token 摘要，允许同一
 * IP 上存在多个 Consumer 实例，而非发明虚假的可访问端口。
 * 路由严格依据已验证 Role 和 Manifest，绝不使用纯 Consumer 的 Naming 地址。
 *
 * @Author OpenAI
 * @Version 1.0.0-SNAPSHOT
 * @CreateTime 2026/10/16 13:42
 */
final class NacosV2InstanceMapper {

    private static final String INSTANCE_ID = NacosV2Names.METADATA_PREFIX + "instance";
    private static final String APPLICATION = NacosV2Names.METADATA_PREFIX + "application";
    private static final String NAMESPACE = NacosV2Names.METADATA_PREFIX + "namespace";
    private static final String ROLE = NacosV2Names.METADATA_PREFIX + "role";
    private static final String REVISION = NacosV2Names.METADATA_PREFIX + "manifest";
    private static final String CLUSTER = NacosV2Names.METADATA_PREFIX + "cluster";
    private static final String TOKEN = NacosV2Names.METADATA_PREFIX + "token";
    private static final String ATTRIBUTE_PREFIX = NacosV2Names.METADATA_PREFIX + "attribute.";
    private static final int MAX_METADATA_BYTES = 8192;
    private static final int WEIGHT_SCALE = 100;
    private static final int MAX_PORT = RpcEndpoint.MAX_PORT;

    private NacosV2InstanceMapper() {
    }

    /** 创建真实应用成员，不为 Consumer 伪造 RPC 端口。 */
    static Instance encode(ApplicationInstance member, String cluster, String memberHost, String token) {
        Objects.requireNonNull(member, "member");
        Objects.requireNonNull(cluster, "cluster");
        Objects.requireNonNull(token, "token");
        Map<String, String> metadata = new HashMap<>();
        metadata.put(INSTANCE_ID, member.instanceId());
        metadata.put(NAMESPACE, member.application().namespace());
        metadata.put(APPLICATION, member.application().applicationName());
        metadata.put(ROLE, member.role().name());
        metadata.put(CLUSTER, cluster);
        metadata.put(TOKEN, token);
        if (member.routableProvider()) {
            ApplicationManifest.requireRevision(member.manifestRevision());
        }
        if (member.manifestRevision() != null) {
            metadata.put(REVISION, member.manifestRevision());
        }
        member.attributes().forEach((key, value) ->
                metadata.put(ATTRIBUTE_PREFIX + encodeKey(key), value));
        long size = 0L;
        for (Map.Entry<String, String> entry : metadata.entrySet()) {
            size += entry.getKey().getBytes(StandardCharsets.UTF_8).length;
            size += entry.getValue().getBytes(StandardCharsets.UTF_8).length;
        }
        if (size > MAX_METADATA_BYTES) {
            throw new IllegalArgumentException("Nacos application member metadata exceeds safe limit");
        }
        String host = member.routableProvider() ? member.rpcEndpoint().host() : memberHost;
        int port = member.routableProvider() ? member.rpcEndpoint().port() : 0;
        if (!validHost(host) || member.routableProvider() && (port <= 0 || port > MAX_PORT)) {
            throw new IllegalArgumentException("Nacos application member has invalid host or RPC endpoint");
        }
        Instance result = new Instance();
        result.setIp(host);
        result.setPort(port);
        result.setClusterName(NacosV2Names.physicalCluster(cluster, member.instanceId(), token));
        result.setMetadata(Map.copyOf(metadata));
        result.setWeight(member.routableProvider() ? member.weight() / (double) WEIGHT_SCALE : 0D);
        result.setEphemeral(true);
        result.setEnabled(true);
        result.setHealthy(true);
        return result;
    }

    /**
     * 恢复经过身份与端点验证的真实成员。非 OTRYX 或其他 logical cluster 的实例忽略；
     * 同应用但造假/损坏的 V2 数据必须 fail closed，而非当作合法空成员。
     */
    static ApplicationInstance decode(
            ApplicationKey application, String logicalCluster, Instance source) {
        if (source == null || !source.isEnabled() || !source.isHealthy()) {
            return null;
        }
        Map<String, String> values = source.getMetadata();
        if (values == null || !values.containsKey(TOKEN)) {
            return null;
        }
        if (!logicalCluster.equals(values.get(CLUSTER))) {
            return null;
        }
        if (!application.namespace().equals(values.get(NAMESPACE))
                || !application.applicationName().equals(values.get(APPLICATION))) {
            throw new IllegalArgumentException("Nacos application member identity mismatch");
        }
        String token = requireText(values.get(TOKEN), "token");
        String instanceId = requireText(values.get(INSTANCE_ID), "instanceId");
        if (!NacosV2Names.physicalCluster(logicalCluster, instanceId, token)
                .equals(source.getClusterName())) {
            throw new IllegalArgumentException("Nacos physical member cluster does not match identity");
        }
        ApplicationRole role = ApplicationRole.valueOf(requireText(values.get(ROLE), "role"));
        if (!validHost(source.getIp())) {
            throw new IllegalArgumentException("Nacos member IP is invalid");
        }
        RpcEndpoint endpoint = role.providesRpc()
                ? new RpcEndpoint(source.getIp(), source.getPort())
                : null;
        if (role.providesRpc() && source.getPort() <= 0
                || !role.providesRpc() && source.getPort() != 0) {
            throw new IllegalArgumentException("Nacos RPC endpoint and member role disagree");
        }
        String revision = role.providesRpc() ? requireText(values.get(REVISION), "revision") : null;
        if (role.providesRpc()) {
            ApplicationManifest.requireRevision(revision);
        } else if (values.containsKey(REVISION)) {
            throw new IllegalArgumentException("Consumer-only member publishes a Manifest revision");
        }
        double scaledWeight = source.getWeight() * WEIGHT_SCALE;
        if (!Double.isFinite(scaledWeight) || scaledWeight > Integer.MAX_VALUE
                || role.providesRpc() && scaledWeight <= 0D
                || !role.providesRpc() && scaledWeight != 0D) {
            throw new IllegalArgumentException("Invalid Nacos member weight");
        }
        Map<String, String> attributes = new HashMap<>();
        for (Map.Entry<String, String> entry : values.entrySet()) {
            if (entry.getKey().startsWith(ATTRIBUTE_PREFIX)) {
                String key = decodeKey(entry.getKey().substring(ATTRIBUTE_PREFIX.length()));
                if (attributes.putIfAbsent(key, entry.getValue()) != null) {
                    throw new IllegalArgumentException("Duplicate application member attribute");
                }
            }
        }
        return new ApplicationInstance(instanceId, application, role, endpoint,
                role.providesRpc() ? Math.max(1, (int) Math.round(scaledWeight)) : 0,
                revision, attributes);
    }

    /** 验证 Consumer 注册的真实主机 IP 不为全网通配或空地址。 */
    static boolean validHost(String host) {
        return host != null && !host.isBlank()
                && !RpcEndpoint.UNSPECIFIED_IPV4_HOST.equals(host)
                && !RpcEndpoint.UNSPECIFIED_IPV6_HOST.equals(host)
                && !RpcEndpoint.UNSPECIFIED_IPV6_BRACKETED_HOST.equals(host);
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing Nacos application member " + field);
        }
        return value;
    }

    private static String encodeKey(String value) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String decodeKey(String value) {
        return new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
    }
}
