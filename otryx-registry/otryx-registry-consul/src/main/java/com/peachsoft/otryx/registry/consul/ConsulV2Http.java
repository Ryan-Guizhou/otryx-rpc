package com.peachsoft.otryx.registry.consul;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.peachsoft.otryx.registry.http.HttpRegistryClient;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Consul Agent/Health/KV/Session 的强校验 HTTP 边界。
 *
 * <p>KV 以 Consul 原生 ModifyIndex/Conditional CAS 为事实源，首次创建使用 cas=0；
 * 所有读取使用一致性模式，不将鉴权或网络错误解释为不存在。Session 通过
 * Behavior=delete 管理短寿命 Liveness Key，持久 Owner/Fencing Key 不删除。
 * 该对象所有方法均可能阻塞，只能由私有有界控制面执行器调用。
 *
 * @Author OpenAI
 * @Version 1.0.0-SNAPSHOT
 * @CreateTime 2026/10/16 14:12
 */
final class ConsulV2Http implements AutoCloseable {

    static final int MAX_MAPPING_VALUE_BYTES = 32 * 1024;
    static final int MAX_MANIFEST_VALUE_BYTES = 400 * 1024;
    private static final String KV = "/v1/kv/";
    private static final String SESSION_CREATE = "/v1/session/create";
    private static final String SESSION_RENEW = "/v1/session/renew/";
    private static final String SESSION_DESTROY = "/v1/session/destroy/";
    private static final String QUERY_CONSISTENT = "?consistent";
    private static final String QUERY_RECURSE = "?recurse&consistent";
    private static final String QUERY_CAS = "?cas=";
    private static final String QUERY_ACQUIRE = "?acquire=";
    private static final String QUERY_NAMESPACE = "&ns=";
    private static final String QUERY_FIRST_NAMESPACE = "?ns=";
    private static final String QUERY_DATACENTER = "&dc=";
    private static final String HEALTH = "/v1/health/service/";
    private static final String REGISTER = "/v1/agent/service/register";
    private static final String DEREGISTER = "/v1/agent/service/deregister/";
    private static final String PASS = "/v1/agent/check/pass/";
    private static final String CHECK_PREFIX = "service:";
    private static final String FIELD_KEY = "Key";
    private static final String FIELD_VALUE = "Value";
    private static final String FIELD_MODIFY_INDEX = "ModifyIndex";
    private static final String FIELD_SESSION = "Session";
    private static final String FIELD_ID = "ID";
    private static final String FIELD_SERVICE = "Service";
    private static final String FIELD_STATUS = "Status";
    private static final String SESSION_BEHAVIOR_DELETE = "delete";
    private static final String SESSION_LOCK_DELAY = "1s";
    private static final String SESSION_NODE_HEALTH = "serfHealth";
    private static final int MAX_SINGLE_KV_RESPONSE = 1;
    private static final int MAX_SESSION_ID_LENGTH = 64;

    private final HttpRegistryClient client;
    private final ObjectMapper mapper = new ObjectMapper();
    private final String enterpriseNamespace;
    private final String datacenter;

    ConsulV2Http(HttpRegistryClient client, String enterpriseNamespace, String datacenter) {
        this.client = Objects.requireNonNull(client, "client");
        this.enterpriseNamespace = Objects.requireNonNull(enterpriseNamespace, "enterpriseNamespace");
        this.datacenter = Objects.requireNonNull(datacenter, "datacenter");
    }

    /** 通过 Consul Agent 本地 Service 注册与 TTL Check。 */
    void register(Map<String, Object> body) throws Exception {
        success(client.request(HttpRegistryClient.METHOD_PUT,
                REGISTER + agentNamespace(), body), "service-register");
    }

    /** 服务注销幂等；不能因 Nacos 风格空目录伪装忽略网络错误。 */
    void deregister(String serviceId) throws Exception {
        HttpRegistryClient.Response result = client.request(
                HttpRegistryClient.METHOD_PUT, DEREGISTER + ConsulV2Keys.encode(serviceId) + agentNamespace(), null);
        if (result.status() != HttpURLConnection.HTTP_NOT_FOUND) {
            success(result, "service-deregister");
        }
    }

    /** TTL 心跳。失去 Check 返回 404，由 Registry 重注册再续约。 */
    boolean pass(String serviceId) throws Exception {
        HttpRegistryClient.Response result = client.request(
                HttpRegistryClient.METHOD_PUT,
                PASS + ConsulV2Keys.encode(CHECK_PREFIX + serviceId) + agentNamespace(), null);
        if (result.status() == HttpURLConnection.HTTP_NOT_FOUND) {
            return false;
        }
        success(result, "check-pass");
        return true;
    }

    /** 只返回 Consul 标记 passing 的原生 Service JSON；调用侧继续验证角色、端点。 */
    List<JsonNode> healthy(String serviceName) throws Exception {
        String path = HEALTH + ConsulV2Keys.encode(serviceName) + "?passing=true"
                + (datacenter.isBlank() ? "" : QUERY_DATACENTER + ConsulV2Keys.encode(datacenter))
                + (enterpriseNamespace.isBlank() ? "" : QUERY_NAMESPACE
                + ConsulV2Keys.encode(enterpriseNamespace));
        HttpRegistryClient.Response result = client.request(HttpRegistryClient.METHOD_GET, path, null);
        if (result.status() == HttpURLConnection.HTTP_NOT_FOUND) {
            return List.of();
        }
        success(result, "service-health");
        if (!result.json().isArray()) {
            throw new IllegalStateException("Consul health response is not an array");
        }
        if (result.json().size() > ConsulV2Keys.MAX_OWNERS) {
            throw new IllegalStateException("Consul application health member capacity exceeded");
        }
        List<JsonNode> found = new ArrayList<>();
        result.json().forEach(found::add);
        return found;
    }

    /** 读取单个 KV 值及 Consul 原生 ModifyIndex。 */
    KvEntry get(String key, int maximumBytes) throws Exception {
        HttpRegistryClient.Response result = client.request(
                HttpRegistryClient.METHOD_GET, KV + key + query(QUERY_CONSISTENT), null);
        if (result.status() == HttpURLConnection.HTTP_NOT_FOUND) {
            return null;
        }
        success(result, "kv-get");
        if (!result.json().isArray() || result.json().size() != MAX_SINGLE_KV_RESPONSE) {
            throw new IllegalArgumentException("Unexpected Consul KV single-key response");
        }
        return decode(result.json().get(0), key, maximumBytes);
    }

    /** 限定返回数量的前缀扫描；单分片的响应仍受底层 HTTP 4MiB 限制。 */
    List<KvEntry> scan(String prefix, int maximumEntries, int maximumBytes) throws Exception {
        HttpRegistryClient.Response result = client.request(
                HttpRegistryClient.METHOD_GET, KV + prefix + query(QUERY_RECURSE), null);
        if (result.status() == HttpURLConnection.HTTP_NOT_FOUND) {
            return List.of();
        }
        success(result, "kv-scan");
        if (!result.json().isArray() || result.json().size() > maximumEntries) {
            throw new IllegalStateException("Consul KV prefix capacity exceeded");
        }
        List<KvEntry> entries = new ArrayList<>();
        for (JsonNode item : result.json()) {
            String key = item.path(FIELD_KEY).asText("");
            if (!key.startsWith(prefix)) {
                throw new IllegalArgumentException("Consul KV key escapes requested prefix");
            }
            entries.add(decode(item, key, maximumBytes));
        }
        return List.copyOf(entries);
    }

    /** cas=0 为原子首次创建；其余索引必须等于服务端 ModifyIndex 才可写入。 */
    boolean compareAndSet(String key, JsonNode value, long modifyIndex, int limitBytes) throws Exception {
        if (modifyIndex < 0L) {
            throw new IllegalArgumentException("Consul KV CAS index must be nonnegative");
        }
        validatePayload(value, limitBytes);
        HttpRegistryClient.Response result = client.request(
                HttpRegistryClient.METHOD_PUT, KV + key + query(QUERY_CAS + modifyIndex), value);
        success(result, "kv-cas");
        return requireBoolean(result.json());
    }

    /** 创建 30 秒续约 Session，持有短寿命 Mapping 活性标志。 */
    String createSession(int ttlSeconds) throws Exception {
        Map<String, Object> body = Map.of(
                "Name", "otryx-v2-mapping",
                "Behavior", SESSION_BEHAVIOR_DELETE,
                "LockDelay", SESSION_LOCK_DELAY,
                "TTL", ttlSeconds + "s",
                "NodeChecks", List.of(SESSION_NODE_HEALTH));
        HttpRegistryClient.Response result = client.request(
                HttpRegistryClient.METHOD_PUT, SESSION_CREATE + sessionNamespace(), body);
        success(result, "session-create");
        String session = result.json().path(FIELD_ID).asText("");
        if (!session.matches("[0-9a-fA-F-]{36}") || session.length() > MAX_SESSION_ID_LENGTH) {
            throw new IllegalStateException("Invalid Consul Session ID");
        }
        return session;
    }

    boolean renewSession(String sessionId) throws Exception {
        HttpRegistryClient.Response result = client.request(
                HttpRegistryClient.METHOD_PUT,
                SESSION_RENEW + ConsulV2Keys.encode(sessionId) + sessionNamespace(), null);
        success(result, "session-renew");
        return result.json().isArray() && !result.json().isEmpty();
    }

    void destroySession(String sessionId) throws Exception {
        HttpRegistryClient.Response result = client.request(
                HttpRegistryClient.METHOD_PUT,
                SESSION_DESTROY + ConsulV2Keys.encode(sessionId) + sessionNamespace(), null);
        success(result, "session-destroy");
    }

    /** Session 的 Behavior=delete 自动 GC 活性键，不触碰持久 fencing 墓碑。 */
    boolean acquire(String key, JsonNode value, String sessionId) throws Exception {
        validatePayload(value, MAX_MAPPING_VALUE_BYTES);
        HttpRegistryClient.Response result = client.request(
                HttpRegistryClient.METHOD_PUT,
                KV + key + query(QUERY_ACQUIRE + ConsulV2Keys.encode(sessionId)), value);
        success(result, "kv-acquire");
        return requireBoolean(result.json());
    }

    private KvEntry decode(JsonNode row, String expectedKey, int maximumBytes) throws IOException {
        if (!expectedKey.equals(row.path(FIELD_KEY).asText())
                || !row.path(FIELD_MODIFY_INDEX).canConvertToLong()) {
            throw new IllegalArgumentException("Consul KV identity or ModifyIndex mismatch");
        }
        long index = row.path(FIELD_MODIFY_INDEX).asLong();
        if (index <= 0L || row.path(FIELD_VALUE).isNull() || !row.path(FIELD_VALUE).isTextual()) {
            throw new IllegalArgumentException("Consul KV has no valid value or ModifyIndex");
        }
        String encoded = row.path(FIELD_VALUE).asText();
        if (encoded.length() > maximumBytes * 2L) {
            throw new IllegalArgumentException("Consul KV encoded value capacity exceeded");
        }
        byte[] bytes = Base64.getDecoder().decode(encoded);
        if (bytes.length > maximumBytes) {
            throw new IllegalArgumentException("Consul KV value capacity exceeded");
        }
        JsonNode json = mapper.readTree(bytes);
        if (json == null || !json.isObject()) {
            throw new IllegalArgumentException("Consul KV payload must be a JSON object");
        }
        return new KvEntry(expectedKey, index, json, row.path(FIELD_SESSION).asText(""));
    }

    private void validatePayload(JsonNode value, int maximumBytes) throws Exception {
        Objects.requireNonNull(value, "value");
        if (!value.isObject() || mapper.writeValueAsBytes(value).length > maximumBytes) {
            throw new IllegalArgumentException("Consul KV value exceeds safety limit or is not an object");
        }
    }

    private static boolean requireBoolean(JsonNode response) {
        if (response == null || !response.isBoolean()) {
            throw new IllegalStateException("Invalid Consul KV boolean result");
        }
        return response.booleanValue();
    }

    private static void success(HttpRegistryClient.Response result, String operation) {
        if (!result.successful()) {
            throw new IllegalStateException("Consul control operation failed: operation="
                    + operation + ", status=" + result.status());
        }
    }

    private String agentNamespace() {
        return enterpriseNamespace.isBlank() ? "" : QUERY_FIRST_NAMESPACE
                + ConsulV2Keys.encode(enterpriseNamespace);
    }

    private String sessionNamespace() {
        return agentNamespace();
    }

    private String query(String query) {
        return query + (enterpriseNamespace.isBlank() ? "" : QUERY_NAMESPACE
                + ConsulV2Keys.encode(enterpriseNamespace));
    }

    @Override
    public void close() {
        client.close();
    }

    record KvEntry(String key, long modifyIndex, JsonNode value, String sessionId) {
    }
}
