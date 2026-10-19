package com.peachsoft.otryx.registry.consul;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Consul 应用控制面专用的有界 HTTP/KV 客户端。
 *
 * <p>所有方法均为阻塞式；调用方只能在专属有界控制面 Executor 中调用。
 * ACL Token 仅写入 Header，错误消息不包含请求正文、URL 或凭据。
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
    private static final String JSON_CONTENT_TYPE = "application/json";
    private static final String BINARY_CONTENT_TYPE = "application/octet-stream";
    private static final String TOKEN_HEADER = "X-Consul-Token";
    private static final String HEADER_CONTENT_TYPE = "Content-Type";
    private static final String QUERY_NAMESPACE = "ns=";
    private static final String PATH_KV = "/v1/kv/";
    private static final String FIELD_VALUE = "Value";
    private static final String FIELD_KEY = "Key";
    private static final String FIELD_MODIFY_INDEX = "ModifyIndex";
    private static final String FIELD_SESSION = "Session";

    private final HttpClient client;
    private final URI endpoint;
    private final String token;
    private final String enterpriseNamespace;
    private final Duration timeout;
    private final ObjectMapper mapper = new ObjectMapper();

    ConsulV2Client(String endpoint, String token, String enterpriseNamespace, Duration timeout) {
        URI parsed = URI.create(Objects.requireNonNull(endpoint, "endpoint"));
        if (parsed.getHost() == null || parsed.getUserInfo() != null || parsed.getQuery() != null
                || parsed.getFragment() != null || !(parsed.getScheme().equalsIgnoreCase("http")
                || parsed.getScheme().equalsIgnoreCase("https"))
                || (parsed.getPath() != null && !parsed.getPath().replace("/", "").isBlank())) {
            throw new IllegalArgumentException("Consul endpoint must be a bare HTTP(S) Agent URL");
        }
        if (timeout == null || timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("Consul request timeout must be positive");
        }
        this.endpoint = URI.create(parsed.toString().replaceAll("/+$", ""));
        this.token = token == null ? "" : token;
        this.enterpriseNamespace = enterpriseNamespace == null ? "" : enterpriseNamespace;
        this.timeout = timeout;
        this.client = HttpClient.newBuilder()
                .connectTimeout(timeout).followRedirects(HttpClient.Redirect.NEVER).build();
    }

    /** 执行 JSON Agent API；404 由调用方显式识别，不吞掉其他 HTTP 错误。 */
    JsonResult json(String method, String path, Object payload) throws Exception {
        byte[] bytes = payload == null ? null : mapper.writeValueAsBytes(payload);
        RawResult result = request(method, scoped(path), bytes, JSON_CONTENT_TYPE);
        if (result.status() == HttpURLConnection.HTTP_NOT_FOUND) {
            return new JsonResult(result.status(), null);
        }
        return new JsonResult(result.status(),
                result.body().length == 0 ? null : mapper.readTree(result.body()));
    }

    /** 读取强一致 KV 单键；只有真实 404 才是未命中。 */
    Optional<KvEntry> get(String key) throws Exception {
        RawResult response = request(METHOD_GET, kvPath(key) + namespaceQuery("?"), null, null);
        if (response.status() == HttpURLConnection.HTTP_NOT_FOUND) {
            return Optional.empty();
        }
        JsonNode rows = mapper.readTree(response.body());
        if (!rows.isArray() || rows.size() != 1) {
            throw new IllegalStateException("Invalid Consul KV single-key response");
        }
        KvEntry entry = decode(rows.get(0));
        if (!entry.key().equals(key)) {
            throw new IllegalStateException("Consul KV key identity mismatch");
        }
        return Optional.of(entry);
    }

    /** 有界扫描 KV 前缀；调用者还须按业务进行命名空间/Owner 校验。 */
    List<KvEntry> list(String prefix) throws Exception {
        RawResult response = request(METHOD_GET, kvPath(prefix) + "?recurse" + namespaceQuery("&"), null, null);
        if (response.status() == HttpURLConnection.HTTP_NOT_FOUND) {
            return List.of();
        }
        JsonNode rows = mapper.readTree(response.body());
        if (!rows.isArray() || rows.size() > MAX_KV_ITEMS) {
            throw new IllegalStateException("Consul KV owner scan exceeds item budget");
        }
        List<KvEntry> entries = new ArrayList<>();
        int totalBytes = 0;
        for (JsonNode row : rows) {
            KvEntry entry = decode(row);
            if (!entry.key().startsWith(prefix)) {
                throw new IllegalStateException("Consul KV owner prefix mismatch");
            }
            totalBytes += entry.value().length;
            if (totalBytes > MAX_KV_TOTAL_BYTES) {
                throw new IllegalStateException("Consul KV owner scan exceeds byte budget");
            }
            entries.add(entry);
        }
        return List.copyOf(entries);
    }

    /**
     * 以 Consul ModifyIndex CAS 更新原始 KV；index=0 表示仅在不存在时创建。
     *
     * @return 真实 Consul 布尔成功值；false 表示合法并发冲突
     */
    boolean cas(String key, long index, byte[] payload) throws Exception {
        Objects.requireNonNull(payload, "payload");
        if (index < 0 || payload.length == 0 || payload.length > MAX_KV_VALUE_BYTES) {
            throw new IllegalArgumentException("Invalid Consul CAS index or KV value size");
        }
        RawResult response = request(METHOD_PUT, kvPath(key) + "?cas=" + index + namespaceQuery("&"),
                payload, BINARY_CONTENT_TYPE);
        String result = new String(response.body(), StandardCharsets.US_ASCII).trim();
        if (!"true".equals(result) && !"false".equals(result)) {
            throw new IllegalStateException("Consul KV CAS did not return a boolean");
        }
        return "true".equals(result);
    }

    /**
     * 使用原生 Consul Session 原子获取成员声明锁；不允许非持有者强行发布同一实例。
     *
     * @return Consul 实际报告的锁申请成功状态
     */
    boolean acquire(String key, String session, byte[] payload) throws Exception {
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(payload, "payload");
        if (session.isBlank() || payload.length == 0 || payload.length > MAX_KV_VALUE_BYTES) {
            throw new IllegalArgumentException("Invalid Consul member Session claim");
        }
        RawResult response = request(METHOD_PUT,
                kvPath(key) + "?acquire=" + encode(session) + namespaceQuery("&"),
                payload, BINARY_CONTENT_TYPE);
        String result = new String(response.body(), StandardCharsets.US_ASCII).trim();
        if (!"true".equals(result) && !"false".equals(result)) {
            throw new IllegalStateException("Consul session acquire did not return a boolean");
        }
        return "true".equals(result);
    }

    private KvEntry decode(JsonNode node) {
        if (!node.isObject()) {
            throw new IllegalArgumentException("Malformed Consul KV entry");
        }
        String key = node.path(FIELD_KEY).asText("");
        JsonNode encoded = node.path(FIELD_VALUE);
        long index = node.path(FIELD_MODIFY_INDEX).asLong(0);
        if (key.isBlank() || !encoded.isTextual() || index < 1
                || encoded.asText().length() > (MAX_KV_VALUE_BYTES * 4 / 3) + 8) {
            throw new IllegalArgumentException("Invalid Consul KV value or revision");
        }
        byte[] bytes = Base64.getDecoder().decode(encoded.asText());
        if (bytes.length == 0 || bytes.length > MAX_KV_VALUE_BYTES) {
            throw new IllegalArgumentException("Consul KV value exceeds safe budget");
        }
        return new KvEntry(key, bytes, index, node.path(FIELD_SESSION).asText(""));
    }

    private String kvPath(String key) {
        if (!key.startsWith("otryx/v2/ns/") || key.contains("?") || key.contains("#")) {
            throw new IllegalArgumentException("Invalid Consul V2 KV path");
        }
        return PATH_KV + key;
    }

    private String namespaceQuery(String separator) {
        return enterpriseNamespace.isBlank()
                ? "" : separator + QUERY_NAMESPACE + encode(enterpriseNamespace);
    }

    private String scoped(String path) {
        if (!path.startsWith("/v1/") || path.startsWith("//") || path.contains("#")) {
            throw new IllegalArgumentException("Invalid Consul Agent API path");
        }
        if (enterpriseNamespace.isBlank()) {
            return path;
        }
        return path + namespaceQuery(path.contains("?") ? "&" : "?");
    }

    private RawResult request(String method, String path, byte[] payload, String contentType)
            throws Exception {
        URI target = endpoint.resolve(path);
        HttpRequest.Builder builder = HttpRequest.newBuilder(target).timeout(timeout);
        if (!token.isBlank()) {
            builder.header(TOKEN_HEADER, token);
        }
        if (payload != null) {
            builder.header(HEADER_CONTENT_TYPE, contentType);
        }
        HttpRequest request = builder.method(method, payload == null
                ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(payload))
                .build();
        HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        try (InputStream body = response.body()) {
            int status = response.statusCode();
            if (status >= HttpURLConnection.HTTP_BAD_REQUEST && status != HttpURLConnection.HTTP_NOT_FOUND) {
                throw new IOException("Consul control-plane request rejected: status=" + status);
            }
            if (status < HttpURLConnection.HTTP_OK || status >= HttpURLConnection.HTTP_MULT_CHOICE
                    && status != HttpURLConnection.HTTP_NOT_FOUND) {
                throw new IOException("Consul unexpected HTTP response: status=" + status);
            }
            byte[] received = body.readNBytes(MAX_JSON_BYTES + 1);
            if (received.length > MAX_JSON_BYTES) {
                throw new IOException("Consul HTTP response exceeds byte budget");
            }
            return new RawResult(status, received);
        }
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    @Override
    public void close() {
        client.close();
    }

    record KvEntry(String key, byte[] value, long modifyIndex, String session) {
    }

    record JsonResult(int status, JsonNode body) {
        boolean missing() {
            return status == HttpURLConnection.HTTP_NOT_FOUND;
        }
    }

    private record RawResult(int status, byte[] body) {
    }
}
