package com.peachsoft.otryx.registry.consul;

import com.fasterxml.jackson.databind.JsonNode;
import com.peachsoft.otryx.registry.RegistryChannel;
import com.peachsoft.otryx.registry.RegistrySecurityDescriptor;
import com.peachsoft.otryx.registry.RegistrySecurityException;
import com.peachsoft.otryx.registry.RegistrySecurityFailure;
import com.peachsoft.otryx.registry.RegistrySecurityOptions;
import com.peachsoft.otryx.registry.RegistrySecuritySupport;
import com.peachsoft.otryx.registry.http.HttpRegistryClient;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Consul 应用控制面专用的有界 HTTP/KV 客户端。
 *
 * <p>所有方法只由专属有界控制面 Executor 调用。独立 TLS Context 与 ACL Header
 * 安装在同一实际客户端上；响应、KV 解析和故障切换共用一个绝对截止时间。
 *
 * @Author OpenAI
 * @Version 1.0.0-SNAPSHOT
 * @CreateTime 2026/10/16 15:00
 */
final class ConsulV2Client implements AutoCloseable {

    static final int MAX_KV_VALUE_BYTES = 256 * 1024;
    static final int MAX_KV_ITEMS = 512;
    private static final int MAX_JSON_BYTES = 12 * 1024 * 1024;
    private static final int MAX_KV_TOTAL_BYTES = 8 * 1024 * 1024;
    private static final String METHOD_GET = "GET";
    private static final String METHOD_PUT = "PUT";
    private static final String BINARY_CONTENT_TYPE = "application/octet-stream";
    private static final String TOKEN_HEADER = "X-Consul-Token";
    private static final String QUERY_NAMESPACE = "ns=";
    private static final String PATH_KV = "/v1/kv/";
    private static final String FIELD_VALUE = "Value";
    private static final String FIELD_KEY = "Key";
    private static final String FIELD_MODIFY_INDEX = "ModifyIndex";
    private static final String FIELD_SESSION = "Session";

    private final HttpRegistryClient client;
    private final String enterpriseNamespace;
    private final RegistrySecurityDescriptor descriptor;

    ConsulV2Client(String endpoint, String token, String enterpriseNamespace, Duration timeout) {
        this(endpoint, token, enterpriseNamespace, timeout, RegistrySecurityOptions.none(), null);
    }

    ConsulV2Client(
            String endpoint, RegistrySecurityOptions security, String namespace,
            String enterpriseNamespace, Duration timeout) {
        this(endpoint, token(security), enterpriseNamespace, timeout, security, namespace);
    }

    private ConsulV2Client(
            String endpoint, String token, String enterpriseNamespace, Duration timeout,
            RegistrySecurityOptions security, String namespace) {
        URI parsed;
        try {
            parsed = URI.create(Objects.requireNonNull(endpoint, "endpoint"));
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException("Invalid Consul Agent endpoint");
        }
        if (parsed.getPath() != null && !parsed.getPath().replace("/", "").isBlank()) {
            throw new IllegalArgumentException("Consul endpoint must be a bare Agent URL");
        }
        this.enterpriseNamespace = enterpriseNamespace == null ? "" : enterpriseNamespace;
        String value = token == null ? "" : token;
        this.client = new HttpRegistryClient(List.of(endpoint), value.isEmpty() ? "" : TOKEN_HEADER,
                value, timeout, MAX_JSON_BYTES, security.tls());
        this.descriptor = namespace == null ? RegistrySecurityDescriptor.unknown()
                : RegistrySecuritySupport.installed(security, namespace,
                        this.enterpriseNamespace.isBlank() ? Optional.empty() : Optional.of(this.enterpriseNamespace),
                        RegistryChannel.MEMBERSHIP, RegistryChannel.MANIFEST, RegistryChannel.MAPPING);
    }

    RegistrySecurityDescriptor securityDescriptor() {
        return descriptor;
    }

    /** 执行 JSON Agent API；真实 404 保持缺失语义，其他拒绝不转换为健康空结果。 */
    JsonResult json(String method, String path, Object payload) throws Exception {
        HttpRegistryClient.Response result = client.request(method, scoped(path), payload);
        return new JsonResult(result.status(), result.status() == HttpURLConnection.HTTP_NOT_FOUND
                ? null : result.json());
    }

    /** 读取单键，并在同一请求预算内校验实际返回的完整 key。 */
    Optional<KvEntry> get(String key) throws Exception {
        return client.requestBytes(METHOD_GET, kvPath(key) + namespaceQuery("?"), null, null, (status, body) -> {
            if (status == HttpURLConnection.HTTP_NOT_FOUND) {
                return Optional.empty();
            }
            JsonNode rows = HttpRegistryClient.readJson(body);
            if (!rows.isArray() || rows.size() != 1) {
                throw integrity();
            }
            KvEntry entry = decode(rows.get(0));
            if (!entry.key().equals(key)) {
                throw new RegistrySecurityException(RegistrySecurityFailure.METADATA_REFERENCE_MISMATCH);
            }
            return Optional.of(entry);
        });
    }

    /** 按既有条数、单 value 与总 value 预算扫描前缀，同时独立复核每条返回的 key。 */
    List<KvEntry> list(String prefix) throws Exception {
        return client.requestBytes(METHOD_GET, kvPath(prefix) + "?recurse" + namespaceQuery("&"),
                null, null, (status, body) -> {
                    if (status == HttpURLConnection.HTTP_NOT_FOUND) {
                        return List.of();
                    }
                    JsonNode rows = HttpRegistryClient.readJson(body);
                    if (!rows.isArray()) {
                        throw integrity();
                    }
                    if (rows.size() > MAX_KV_ITEMS) {
                        throw limit();
                    }
                    List<KvEntry> entries = new ArrayList<>();
                    int totalBytes = 0;
                    for (JsonNode row : rows) {
                        KvEntry entry = decode(row);
                        if (!entry.key().startsWith(prefix)) {
                            throw new RegistrySecurityException(
                                    RegistrySecurityFailure.METADATA_REFERENCE_MISMATCH);
                        }
                        if (entry.value().length > MAX_KV_TOTAL_BYTES - totalBytes) {
                            throw limit();
                        }
                        totalBytes += entry.value().length;
                        entries.add(entry);
                    }
                    return List.copyOf(entries);
                });
    }

    /** 只执行一次 CAS；false 为合法并发冲突，不能自动重放可能已经成功的写入。 */
    boolean cas(String key, long index, byte[] payload) throws Exception {
        Objects.requireNonNull(payload, "payload");
        if (index < 0 || payload.length == 0 || payload.length > MAX_KV_VALUE_BYTES) {
            throw new IllegalArgumentException("Invalid Consul CAS index or KV value size");
        }
        return client.requestBytes(METHOD_PUT, kvPath(key) + "?cas=" + index + namespaceQuery("&"),
                payload, BINARY_CONTENT_TYPE, ConsulV2Client::booleanResult);
    }

    /** 原生 Session 声明不重试非持有者可能已经提交的同一实例写入。 */
    boolean acquire(String key, String session, byte[] payload) throws Exception {
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(payload, "payload");
        if (session.isBlank() || payload.length == 0 || payload.length > MAX_KV_VALUE_BYTES) {
            throw new IllegalArgumentException("Invalid Consul member Session claim");
        }
        return client.requestBytes(METHOD_PUT,
                kvPath(key) + "?acquire=" + encode(session) + namespaceQuery("&"),
                payload, BINARY_CONTENT_TYPE, ConsulV2Client::booleanResult);
    }

    private static boolean booleanResult(int status, byte[] body) {
        JsonNode result = HttpRegistryClient.readJson(body);
        if (status == HttpURLConnection.HTTP_NOT_FOUND || !result.isBoolean()) {
            throw integrity();
        }
        return result.booleanValue();
    }

    private KvEntry decode(JsonNode node) {
        if (!node.isObject()) {
            throw integrity();
        }
        String key = node.path(FIELD_KEY).asText("");
        JsonNode encoded = node.path(FIELD_VALUE);
        JsonNode indexNode = node.path(FIELD_MODIFY_INDEX);
        if (key.isBlank() || !encoded.isTextual() || !indexNode.isIntegralNumber() || !indexNode.canConvertToLong()
                || indexNode.longValue() < 1) {
            throw integrity();
        }
        if (encoded.asText().length() > (MAX_KV_VALUE_BYTES * 4 / 3) + 8) {
            throw limit();
        }
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(encoded.asText());
        } catch (IllegalArgumentException invalid) {
            throw integrity();
        }
        if (bytes.length == 0) {
            throw integrity();
        }
        if (bytes.length > MAX_KV_VALUE_BYTES) {
            throw limit();
        }
        return new KvEntry(key, bytes, indexNode.longValue(), node.path(FIELD_SESSION).asText(""));
    }

    private String kvPath(String key) {
        if (!key.startsWith("otryx/v2/ns/") || key.contains("?") || key.contains("#")) {
            throw new IllegalArgumentException("Invalid Consul V2 KV path");
        }
        return PATH_KV + key;
    }

    private String namespaceQuery(String separator) {
        return enterpriseNamespace.isBlank() ? "" : separator + QUERY_NAMESPACE + encode(enterpriseNamespace);
    }

    private String scoped(String path) {
        if (!path.startsWith("/v1/") || path.startsWith("//") || path.contains("#")) {
            throw new IllegalArgumentException("Invalid Consul Agent API path");
        }
        return path + namespaceQuery(path.contains("?") ? "&" : "?");
    }

    private static String token(RegistrySecurityOptions security) {
        char[] value = security.credentials().token();
        try {
            return new String(value);
        } finally {
            Arrays.fill(value, '\0');
        }
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static RegistrySecurityException integrity() {
        return new RegistrySecurityException(RegistrySecurityFailure.METADATA_INTEGRITY_REJECTED);
    }

    private static RegistrySecurityException limit() {
        return new RegistrySecurityException(RegistrySecurityFailure.RESPONSE_LIMIT_EXCEEDED);
    }

    @Override
    public void close() {
        client.close();
    }

    record KvEntry(String key, byte[] value, long modifyIndex, String session) {
        @Override
        public String toString() {
            return "ConsulKvEntry[valueBytes=" + value.length + ", revision=" + modifyIndex + ']';
        }
    }

    record JsonResult(int status, JsonNode body) {
        boolean missing() {
            return status == HttpURLConnection.HTTP_NOT_FOUND;
        }

        @Override
        public String toString() {
            return "ConsulJsonResult[status=" + status + ']';
        }
    }
}
