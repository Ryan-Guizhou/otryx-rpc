package com.peachsoft.otryx.registry.http;

import com.peachsoft.otryx.api.RpcEndpoint;
import com.peachsoft.otryx.api.ServiceInstance;
import com.peachsoft.otryx.api.ServiceKey;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;

/**
 * 注册中心服务名、Provider 身份与不可覆写元数据的映射。
 *
 * @Author Ryan
 * @Version 1.0.0
 * @CreateTime 2026/10/10 18:00
 */
public final class HttpRegistryIdentity {

    /** Adapter 所有的原始实例 ID 元数据键。 */
    public static final String INSTANCE_ID = "otryx.rpc.instance-id";
    /** Adapter 所有的完整服务身份元数据键。 */
    public static final String SERVICE_KEY = "otryx.rpc.service-key";
    /** Adapter 所有的权重元数据键。 */
    public static final String WEIGHT = "otryx.rpc.weight";
    private static final String SERVICE_NAME_HASH_ALGORITHM = "SHA-256";
    private static final String SERVICE_NAME_PREFIX = "otryx-";
    private static final int SERVICE_HASH_BYTES = 16;
    private static final int MAX_ENCODED_INSTANCE_ID_LENGTH = 256;

    private HttpRegistryIdentity() {
    }

    /**
     * 生成不依赖注册中心名称规则的确定性服务名。
     *
     * @param namespace 逻辑命名空间
     * @param key RPC 服务键
     * @return DNS 兼容的服务名
     */
    public static String serviceName(String namespace, ServiceKey key) {
        Objects.requireNonNull(key, "key");
        String input = (namespace == null ? "" : namespace)
                + "\u0000" + key.canonicalName();
        try {
            byte[] digest = MessageDigest.getInstance(SERVICE_NAME_HASH_ALGORITHM)
                    .digest(input.getBytes(StandardCharsets.UTF_8));
            return SERVICE_NAME_PREFIX + HexFormat.of().formatHex(digest, 0, SERVICE_HASH_BYTES);
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is unavailable", error);
        }
    }

    /**
     * 返回按完整 ServiceKey 隔离的注册中心实例 ID。
     *
     * @param namespace 逻辑命名空间
     * @param instance Provider 实例
     * @return 注册中心唯一实例 ID
     */
    public static String scopedInstanceId(String namespace, ServiceInstance instance) {
        String encoded = Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(instance.instanceId().getBytes(StandardCharsets.UTF_8));
        if (encoded.isEmpty() || encoded.length() > MAX_ENCODED_INSTANCE_ID_LENGTH) {
            throw new IllegalArgumentException("RPC instanceId length is invalid");
        }
        return serviceName(namespace, instance.serviceKey()) + "-" + encoded;
    }

    /**
     * 验证外部可路由地址。
     *
     * @param instance Provider 实例
     */
    public static void requireRoutable(ServiceInstance instance) {
        String host = instance.endpoint().host();
        if (host.isBlank()
                || RpcEndpoint.UNSPECIFIED_IPV4_HOST.equals(host)
                || RpcEndpoint.UNSPECIFIED_IPV6_HOST.equals(host)

                || RpcEndpoint.UNSPECIFIED_IPV6_BRACKETED_HOST.equals(host) || instance.endpoint().port() < 1) {
            throw new IllegalArgumentException("Registry endpoint must be routable");
        }
    }

    /**
     * 追加 Adapter 身份字段，完整保留框架兼容元数据。
     *
     * @param instance Provider 实例
     * @return 待注册的元数据
     */
    public static Map<String, String> metadata(ServiceInstance instance) {
        Map<String, String> values = new HashMap<>(instance.metadata());
        for (String key : ListOfKeys.VALUES) {
            if (values.containsKey(key)) {
                throw new IllegalArgumentException(
                        "Metadata must not override registry-owned key: " + key);
            }
        }
        values.put(INSTANCE_ID, instance.instanceId());
        values.put(SERVICE_KEY, instance.serviceKey().canonicalName());
        values.put(WEIGHT, Integer.toString(instance.weight()));
        return Map.copyOf(values);
    }

    /**
     * 将远端元数据转换成 OTRYX 路由实例；错误或异源实例不参与路由。
     *
     * @param key 请求的完整 RPC 服务身份
     * @param host 远端发布地址
     * @param port 远端端口
     * @param metadata 远端元数据
     * @return 合法实例或 null
     */
    public static ServiceInstance fromRemote(
            ServiceKey key, String host, int port, Map<String, String> metadata) {
        if (metadata == null || !key.canonicalName().equals(metadata.get(SERVICE_KEY))
                || host == null || host.isBlank()
                || RpcEndpoint.UNSPECIFIED_IPV4_HOST.equals(host)

                || RpcEndpoint.UNSPECIFIED_IPV6_HOST.equals(host)
                || RpcEndpoint.UNSPECIFIED_IPV6_BRACKETED_HOST.equals(host)
                || port < 1 || port > RpcEndpoint.MAX_PORT) {
            return null;
        }
        String id = metadata.get(INSTANCE_ID);
        if (id == null || id.isBlank()) {
            return null;
        }
        try {
            int weight = Integer.parseInt(metadata.getOrDefault(WEIGHT, ""));
            return weight > 0
                    ? new ServiceInstance(id, key, new RpcEndpoint(host, port), weight,
                            Map.copyOf(metadata))
                    : null;
        } catch (NumberFormatException error) {
            return null;
        }
    }

    private static final class ListOfKeys {
        private static final java.util.List<String> VALUES =
                java.util.List.of(INSTANCE_ID, SERVICE_KEY, WEIGHT);

        private ListOfKeys() {
        }
    }
}
