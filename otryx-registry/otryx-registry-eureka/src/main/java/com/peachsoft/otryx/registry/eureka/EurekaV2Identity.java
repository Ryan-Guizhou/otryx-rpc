package com.peachsoft.otryx.registry.eureka;

import com.fasterxml.jackson.databind.JsonNode;
import com.peachsoft.otryx.api.ApplicationInstance;
import com.peachsoft.otryx.api.ApplicationKey;
import com.peachsoft.otryx.api.ApplicationRole;
import com.peachsoft.otryx.api.RpcEndpoint;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Eureka 原生 InstanceInfo 的应用级身份、角色与端口安全映射。
 *
 * <p>同一 ApplicationKey 使用单一稳定 App 名称；Native InstanceId 还包含随机进程
 * Token，两个进程误用相同逻辑 InstanceId 时不会彼此覆盖。消费者真实登记
 * Eureka InstanceInfo，但 RPC Port=0 且 @enabled=false，永不被 RPC 池寻址。
 * 元数据不能充当认证凭据，必须搭配 HTTPS、服务端 ACL 和方法鉴权。
 *
 * @Author OpenAI
 * @Version 1.0.0-SNAPSHOT
 * @CreateTime 2026/10/16 18:09
 */
final class EurekaV2Identity {

    private static final String PREFIX = "OTRYX-";
    private static final String NATIVE_ID_PREFIX = "otryx-";
    private static final String META_VERSION = "otryxV2Version";
    private static final String META_NAMESPACE = "otryxV2Namespace";
    private static final String META_APPLICATION = "otryxV2Application";
    private static final String META_INSTANCE = "otryxV2Instance";
    private static final String META_ROLE = "otryxV2Role";
    private static final String META_REVISION = "otryxV2Revision";
    private static final String META_WEIGHT = "otryxV2Weight";
    private static final String META_TOKEN = "otryxV2Token";
    private static final String ATTR_PREFIX = "a_";
    private static final String V2 = "2";
    private static final String SHA256 = "SHA-256";
    private static final int APP_DIGEST_BYTES = 16;
    private static final int MEMBER_DIGEST_BYTES = 12;
    private static final int MAX_META_KEYS = 64;
    private static final int BASE_META_KEYS = 8;
    private static final int MAX_META_KEY_BYTES = 128;
    private static final int MAX_META_VALUE_BYTES = 512;
    private static final int MIN_RPC_PORT = 1;
    private static final int NO_RPC_PORT = 0;
    private final String namespace;

    EurekaV2Identity(String namespace) {
        this.namespace = new ApplicationKey(namespace, "eureka").namespace();
    }

    void requireNamespace(ApplicationKey application) {
        Objects.requireNonNull(application, "application");
        if (!namespace.equals(application.namespace())) {
            throw new IllegalArgumentException("Eureka application crosses Registry namespace");
        }
    }

    String appName(ApplicationKey application) {
        requireNamespace(application);
        return PREFIX + digest(application.namespace() + '\u0000'
                + application.applicationName(), APP_DIGEST_BYTES).toUpperCase(java.util.Locale.ROOT);
    }

    String nativeInstanceId(ApplicationInstance member, String token) {
        requireNamespace(member.application());
        return NATIVE_ID_PREFIX + digest(member.instanceId(), MEMBER_DIGEST_BYTES) + '-' + token;
    }

    String localKey(ApplicationInstance member) {
        requireNamespace(member.application());
        return appName(member.application()) + ':' + member.instanceId();
    }

    Map<String, String> metadata(ApplicationInstance member, String token) {
        requireNamespace(member.application());
        if (!validToken(token)) {
            throw new IllegalArgumentException("Eureka member token invalid");
        }
        Map<String, String> values = new LinkedHashMap<>();
        values.put(META_VERSION, V2);
        values.put(META_NAMESPACE, member.application().namespace());
        values.put(META_APPLICATION, member.application().applicationName());
        values.put(META_INSTANCE, member.instanceId());
        values.put(META_ROLE, member.role().name());
        values.put(META_REVISION, member.manifestRevision() == null ? "" : member.manifestRevision());
        values.put(META_WEIGHT, Integer.toString(member.weight()));
        values.put(META_TOKEN, token);
        if (member.attributes().size() > MAX_META_KEYS - BASE_META_KEYS) {
            throw new IllegalArgumentException("Eureka member attributes exceed key budget");
        }
        member.attributes().forEach((key, value) -> {
            String nativeKey = ATTR_PREFIX + key;
            if (!metaKey(nativeKey) || !fits(value, MAX_META_VALUE_BYTES)) {
                throw new IllegalArgumentException("Eureka member attribute exceeds native limits");
            }
            values.put(nativeKey, value);
        });
        for (Map.Entry<String, String> entry : values.entrySet()) {
            if (!metaKey(entry.getKey()) || !fits(entry.getValue(), MAX_META_VALUE_BYTES)) {
                throw new IllegalArgumentException("Eureka core metadata exceeds native limits");
            }
        }
        return Map.copyOf(values);
    }

    /**
     * 构造标准 Eureka InstanceInfo，纯 Consumer 使用真正禁用的原生 RPC Port。
     *
     * @param member 实际应用实例与角色
     * @param token 当前进程唯一 Token
     * @param memberHost Consumer 的本机地址或 Provider 声明的 RPC 地址
     * @param renewalSeconds 原生续约间隔
     * @param leaseSeconds 原生实例 TTL
     * @return 可交给 Eureka /apps POST 的 JSON 对象
     */
    Map<String, Object> nativeRegistration(
            ApplicationInstance member, String token, String memberHost,
            int renewalSeconds, int leaseSeconds) {
        requireNamespace(member.application());
        String host = member.routableProvider() ? member.rpcEndpoint().host() : memberHost;
        if (host == null || host.isBlank() || unspecified(host)) {
            throw new IllegalArgumentException("Eureka membership requires a real host address");
        }
        int port = member.routableProvider() ? member.rpcEndpoint().port() : NO_RPC_PORT;
        if (member.routableProvider() && port < MIN_RPC_PORT) {
            throw new IllegalArgumentException("Eureka Provider RPC port is not routable");
        }
        String app = appName(member.application());
        Map<String, Object> info = new LinkedHashMap<>();
        info.put(EurekaProtocol.FIELD_INSTANCE_ID, nativeInstanceId(member, token));
        info.put(EurekaProtocol.FIELD_APP, app);
        info.put(EurekaProtocol.FIELD_HOST_NAME, host);
        info.put(EurekaProtocol.FIELD_IP_ADDRESS, host);
        info.put(EurekaProtocol.FIELD_VIP_ADDRESS, app);
        info.put(EurekaProtocol.FIELD_SECURE_VIP_ADDRESS, app);
        info.put(EurekaProtocol.FIELD_STATUS, EurekaProtocol.STATUS_UP);
        info.put(EurekaProtocol.FIELD_PORT, Map.of(
                EurekaProtocol.FIELD_VALUE, port,
                EurekaProtocol.FIELD_ENABLED,
                member.routableProvider() ? EurekaProtocol.PORT_ENABLED : EurekaProtocol.SECURE_PORT_DISABLED));
        info.put(EurekaProtocol.FIELD_SECURE_PORT, Map.of(
                EurekaProtocol.FIELD_VALUE, EurekaProtocol.DEFAULT_DISABLED_SECURE_PORT,
                EurekaProtocol.FIELD_ENABLED, EurekaProtocol.SECURE_PORT_DISABLED));
        info.put(EurekaProtocol.FIELD_DATA_CENTER_INFO, Map.of(
                EurekaProtocol.FIELD_CLASS, EurekaProtocol.DEFAULT_DATA_CENTER_CLASS,
                EurekaProtocol.FIELD_NAME, EurekaProtocol.DEFAULT_DATA_CENTER_NAME));
        info.put(EurekaProtocol.FIELD_LEASE_INFO, Map.of(
                EurekaProtocol.FIELD_RENEWAL_INTERVAL_SECONDS, renewalSeconds,
                EurekaProtocol.FIELD_LEASE_DURATION_SECONDS, leaseSeconds));
        info.put(EurekaProtocol.FIELD_METADATA, metadata(member, token));
        return Map.of(EurekaProtocol.FIELD_INSTANCE, info);
    }

    /**
     * 解码 UP 的 V2 成员，严校归属、Native ID、角色与端口 enabled 标记。
     *
     * @param application 调用方当前目标应用
     * @param input Eureka 返回的 InstanceInfo JSON
     * @return 符合本注册中心协议的成员，非 V2 或不可路由数据返回 null
     */
    ApplicationInstance fromInstance(ApplicationKey application, JsonNode input) {
        requireNamespace(application);
        if (!EurekaProtocol.STATUS_UP.equals(input.path(EurekaProtocol.FIELD_STATUS).asText())) {
            return null;
        }
        String override = input.path(EurekaProtocol.FIELD_OVERRIDDEN_STATUS).asText("");
        if (EurekaProtocol.STATUS_DOWN.equals(override)
                || EurekaProtocol.STATUS_OUT_OF_SERVICE.equals(override)) {
            return null;
        }
        Map<String, String> meta = readMeta(input.path(EurekaProtocol.FIELD_METADATA));
        if (!V2.equals(meta.get(META_VERSION))
                || !application.namespace().equals(meta.get(META_NAMESPACE))
                || !application.applicationName().equals(meta.get(META_APPLICATION))) {
            return null;
        }
        try {
            String token = meta.get(META_TOKEN);
            String instanceId = meta.get(META_INSTANCE);
            String expected = NATIVE_ID_PREFIX + digest(instanceId, MEMBER_DIGEST_BYTES) + '-' + token;
            if (!validToken(token)
                    || !expected.equals(input.path(EurekaProtocol.FIELD_INSTANCE_ID).asText())
                    || !appName(application).equalsIgnoreCase(input.path(EurekaProtocol.FIELD_APP).asText())) {
                return null;
            }
            ApplicationRole role = ApplicationRole.valueOf(meta.get(META_ROLE));
            String revision = meta.get(META_REVISION);
            int weight = Integer.parseInt(meta.get(META_WEIGHT));
            JsonNode nativePort = input.path(EurekaProtocol.FIELD_PORT);
            int port = nativePort.isObject()
                    ? nativePort.path(EurekaProtocol.FIELD_VALUE).asInt(-1)
                    : nativePort.asInt(-1);
            String enabled = nativePort.path(EurekaProtocol.FIELD_ENABLED).asText("");
            RpcEndpoint endpoint = null;
            if (role.providesRpc()) {
                String host = input.path(EurekaProtocol.FIELD_IP_ADDRESS).asText("");
                if (host.isBlank()) {
                    host = input.path(EurekaProtocol.FIELD_HOST_NAME).asText("");
                }
                if (port < MIN_RPC_PORT || unspecified(host)
                        || !EurekaProtocol.PORT_ENABLED.equals(enabled)) {
                    return null;
                }
                endpoint = new RpcEndpoint(host, port);
            } else if (port != NO_RPC_PORT || weight != 0 || !revision.isEmpty()
                    || !EurekaProtocol.SECURE_PORT_DISABLED.equals(enabled)) {
                return null;
            }
            Map<String, String> attrs = new HashMap<>();
            for (Map.Entry<String, String> entry : meta.entrySet()) {
                if (entry.getKey().startsWith(ATTR_PREFIX)) {
                    attrs.put(entry.getKey().substring(ATTR_PREFIX.length()), entry.getValue());
                }
            }
            return new ApplicationInstance(instanceId, application, role, endpoint, weight,
                    role.providesRpc() ? revision : null, attrs);
        } catch (IllegalArgumentException | NullPointerException malformed) {
            return null;
        }
    }

    private static Map<String, String> readMeta(JsonNode json) {
        if (!json.isObject() || json.size() > MAX_META_KEYS) {
            return Map.of();
        }
        Map<String, String> values = new HashMap<>();
        var entries = json.fields();
        while (entries.hasNext()) {
            Map.Entry<String, JsonNode> entry = entries.next();
            if (!metaKey(entry.getKey()) || !entry.getValue().isValueNode()
                    || !fits(entry.getValue().asText(), MAX_META_VALUE_BYTES)) {
                return Map.of();
            }
            values.put(entry.getKey(), entry.getValue().asText());
        }
        return values;
    }

    static boolean unspecified(String address) {
        return address == null || address.isBlank()
                || RpcEndpoint.UNSPECIFIED_IPV4_HOST.equals(address)
                || RpcEndpoint.UNSPECIFIED_IPV6_HOST.equals(address)
                || RpcEndpoint.UNSPECIFIED_IPV6_BRACKETED_HOST.equals(address);
    }

    private static boolean fits(String input, int budget) {
        return input != null && input.getBytes(StandardCharsets.UTF_8).length <= budget;
    }

    private static boolean metaKey(String key) {
        if (!fits(key, MAX_META_KEY_BYTES)) {
            return false;
        }
        for (int index = 0; index < key.length(); index++) {
            char c = key.charAt(index);
            if (!Character.isLetterOrDigit(c) && c != '_' && c != '-') {
                return false;
            }
        }
        return true;
    }

    private static boolean validToken(String token) {
        if (token == null) {
            return false;
        }
        try {
            return token.equals(UUID.fromString(token).toString());
        } catch (IllegalArgumentException invalid) {
            return false;
        }
    }

    private static String digest(String value, int bytes) {
        try {
            byte[] input = value.getBytes(StandardCharsets.UTF_8);
            return HexFormat.of().formatHex(MessageDigest.getInstance(SHA256).digest(input), 0, bytes);
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("Eureka V2 SHA-256 unavailable", error);
        }
    }
}
