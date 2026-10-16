package com.peachsoft.otryx.registry.consul;

import com.fasterxml.jackson.databind.JsonNode;
import com.peachsoft.otryx.api.ApplicationInstance;
import com.peachsoft.otryx.api.ApplicationKey;
import com.peachsoft.otryx.api.ApplicationRole;
import com.peachsoft.otryx.api.RpcEndpoint;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Consul V2 应用成员身份、键空间与严格的原生 Service Meta 映射。
 *
 * <p>ServiceName 只根据 ApplicationKey 生成；一个实例发布多个接口不会重复登记。
 * Consumer-only 不声明 RPC Address/Port，且 Metadata 不能作为认证凭据。
 *
 * @Author OpenAI
 * @Version 1.0.0-SNAPSHOT
 * @CreateTime 2026/10/16 14:45
 */
final class ConsulV2Identity {

    private static final String ROOT = "otryx/v2/ns/";
    private static final String APPS = "apps/";
    private static final String OWNERS = "owners/";
    private static final String MANIFESTS = "manifests/";
    private static final String HASH_ALGORITHM = "SHA-256";
    private static final String SERVICE_PREFIX = "otryx-app-";
    private static final String META_VERSION = "otryxV2Version";
    private static final String META_NAMESPACE = "otryxV2Namespace";
    private static final String META_APPLICATION = "otryxV2Application";
    private static final String META_INSTANCE = "otryxV2Instance";
    private static final String META_ROLE = "otryxV2Role";
    private static final String META_REVISION = "otryxV2Revision";
    private static final String META_WEIGHT = "otryxV2Weight";
    private static final String META_TOKEN = "otryxV2Token";
    private static final String ATTRIBUTE_PREFIX = "a_";
    private static final String FORMAT_VERSION = "2";
    private static final int SERVICE_HASH_BYTES = 12;
    private static final int INSTANCE_HASH_BYTES = 12;
    private static final int MAX_META_ITEMS = 64;
    private static final int MAX_META_KEY_LENGTH = 128;
    private static final int MAX_META_VALUE_LENGTH = 512;
    private static final int BASE_META_COUNT = 8;

    private final String namespace;
    private final String keyRoot;

    ConsulV2Identity(String namespace) {
        this.namespace = new ApplicationKey(namespace, "consul").namespace();
        keyRoot = ROOT + segment(namespace) + '/';
    }

    void requireNamespace(ApplicationKey application) {
        Objects.requireNonNull(application, "application");
        if (!namespace.equals(application.namespace())) {
            throw new IllegalArgumentException("Consul application namespace mismatch");
        }
    }

    void requireNamespace(String expected) {
        if (!namespace.equals(expected)) {
            throw new IllegalArgumentException("Consul mapping namespace mismatch");
        }
    }

    String serviceName(ApplicationKey application) {
        requireNamespace(application);
        return SERVICE_PREFIX + digest(application.namespace() + '\u0000'
                + application.applicationName(), SERVICE_HASH_BYTES);
    }

    String serviceId(ApplicationInstance instance) {
        return serviceId(instance.application(), instance.instanceId());
    }

    String serviceId(ApplicationKey application, String instanceId) {
        return serviceName(application) + '-' + digest(instanceId, INSTANCE_HASH_BYTES);
    }

    String manifestKey(ApplicationKey application, String revision) {
        requireNamespace(application);
        return keyRoot + APPS + segment(application.applicationName()) + '/'
                + MANIFESTS + segment(revision);
    }

    String ownersPrefix() {
        return keyRoot + OWNERS;
    }

    String ownerKey(ApplicationKey application, String instanceId) {
        requireNamespace(application);
        return ownersPrefix() + segment(application.applicationName()) + '/' + segment(instanceId);
    }

    /**
     * 生成受 Consul Meta 原生容量和字符约束保护的元数据。
     *
     * @param member 待发布成员
     * @param token 本进程随机持有的服务所有权标识
     * @return 一次创建的原生 Meta
     */
    Map<String, String> metadata(ApplicationInstance member, String token) {
        requireNamespace(member.application());
        if (token == null || token.isBlank() || token.length() > MAX_META_VALUE_LENGTH) {
            throw new IllegalArgumentException("Invalid Consul member ownership token");
        }
        Map<String, String> values = new LinkedHashMap<>();
        values.put(META_VERSION, FORMAT_VERSION);
        values.put(META_NAMESPACE, member.application().namespace());
        values.put(META_APPLICATION, member.application().applicationName());
        values.put(META_INSTANCE, member.instanceId());
        values.put(META_ROLE, member.role().name());
        values.put(META_REVISION, member.manifestRevision() == null ? "" : member.manifestRevision());
        values.put(META_WEIGHT, Integer.toString(member.weight()));
        values.put(META_TOKEN, token);
        if (member.attributes().size() > MAX_META_ITEMS - BASE_META_COUNT) {
            throw new IllegalArgumentException("Consul metadata has too many attributes");
        }
        member.attributes().forEach((key, value) -> {
            String encodedKey = ATTRIBUTE_PREFIX + key;
            if (encodedKey.length() > MAX_META_KEY_LENGTH || !validMetaKey(encodedKey)
                    || value.length() > MAX_META_VALUE_LENGTH) {
                throw new IllegalArgumentException("Application attributes exceed native Consul Meta limits");
            }
            values.put(encodedKey, value);
        });
        return Map.copyOf(values);
    }

    /**
     * 解析 Passing Health 响应，并校验完整归属及角色；非法或异源成员直接排除。
     *
     * @param application 请求的应用
     * @param entry Health API 中的一条原生记录
     * @return 合规应用成员；非法值返回 null
     */
    ApplicationInstance fromHealth(ApplicationKey application, JsonNode entry) {
        requireNamespace(application);
        JsonNode service = entry.path("Service");
        Map<String, String> meta = readMeta(service.path("Meta"));
        if (!FORMAT_VERSION.equals(meta.get(META_VERSION))
                || !application.namespace().equals(meta.get(META_NAMESPACE))
                || !application.applicationName().equals(meta.get(META_APPLICATION))
                || !serviceName(application).equals(service.path("Service").asText())
                || meta.getOrDefault(META_TOKEN, "").isBlank()) {
            return null;
        }
        JsonNode checks = entry.path("Checks");
        if (!checks.isArray() || checks.isEmpty()) {
            return null;
        }
        for (JsonNode check : checks) {
            if (!"passing".equals(check.path("Status").asText())) {
                return null;
            }
        }
        try {
            String instanceId = meta.get(META_INSTANCE);
            if (!serviceId(application, instanceId).equals(service.path("ID").asText())) {
                return null;
            }
            ApplicationRole role = ApplicationRole.valueOf(meta.get(META_ROLE));
            int weight = Integer.parseInt(meta.get(META_WEIGHT));
            String revision = meta.get(META_REVISION);
            RpcEndpoint endpoint = null;
            int port = service.path("Port").asInt(0);
            if (role.providesRpc()) {
                String address = service.path("Address").asText("");
                if (address.isBlank()) {
                    address = entry.path("Node").path("Address").asText("");
                }
                if (port < 1 || address.isBlank() || isUnspecified(address)) {
                    return null;
                }
                endpoint = new RpcEndpoint(address, port);
            } else if (port != 0 || weight != 0 || !revision.isEmpty()) {
                return null;
            }
            Map<String, String> attributes = new HashMap<>();
            for (Map.Entry<String, String> item : meta.entrySet()) {
                if (item.getKey().startsWith(ATTRIBUTE_PREFIX)) {
                    attributes.put(item.getKey().substring(ATTRIBUTE_PREFIX.length()), item.getValue());
                }
            }
            return new ApplicationInstance(instanceId, application, role, endpoint, weight,
                    role.providesRpc() ? revision : null, attributes);
        } catch (IllegalArgumentException | NullPointerException invalid) {
            return null;
        }
    }

    static boolean isUnspecified(String host) {
        return RpcEndpoint.UNSPECIFIED_IPV4_HOST.equals(host)
                || RpcEndpoint.UNSPECIFIED_IPV6_HOST.equals(host)
                || RpcEndpoint.UNSPECIFIED_IPV6_BRACKETED_HOST.equals(host);
    }

    static String segment(String value) {
        Objects.requireNonNull(value, "Consul key segment");
        if (value.isBlank()) {
            throw new IllegalArgumentException("Consul key segment must not be blank");
        }
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static Map<String, String> readMeta(JsonNode meta) {
        if (!meta.isObject() || meta.size() > MAX_META_ITEMS) {
            return Map.of();
        }
        Map<String, String> values = new HashMap<>();
        meta.fields().forEachRemaining(item -> {
            if (item.getValue().isTextual()
                    && item.getKey().length() <= MAX_META_KEY_LENGTH
                    && validMetaKey(item.getKey())
                    && item.getValue().asText().length() <= MAX_META_VALUE_LENGTH) {
                values.put(item.getKey(), item.getValue().asText());
            }
        });
        return values;
    }

    private static boolean validMetaKey(String key) {
        for (int index = 0; index < key.length(); index++) {
            char value = key.charAt(index);
            if (!(value >= 'a' && value <= 'z')
                    && !(value >= 'A' && value <= 'Z')
                    && !(value >= '0' && value <= '9')
                    && value != '_' && value != '-') {
                return false;
            }
        }
        return !key.isBlank();
    }

    private static String digest(String input, int bytes) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance(HASH_ALGORITHM)
                            .digest(input.getBytes(StandardCharsets.UTF_8)), 0, bytes);
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 unavailable", error);
        }
    }
}
