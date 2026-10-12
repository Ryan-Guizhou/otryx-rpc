package com.peachsoft.otryx.registry.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.NullNode;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 有超时、端点故障转移和凭证隔离的注册中心 HTTP 客户端。
 *
 * <p>同步 HTTP 调用只允许由 HttpRegistry 的有界控制面线程执行。
 *
 * @Author Ryan
 * @Version 1.0.0
 * @CreateTime 2026/10/10 18:00
 */
public final class HttpRegistryClient implements AutoCloseable {

    /** Registry HTTP GET 方法。 */
    public static final String METHOD_GET = "GET";

    /** Registry HTTP POST 方法。 */
    public static final String METHOD_POST = "POST";

    /** Registry HTTP PUT 方法。 */
    public static final String METHOD_PUT = "PUT";

    /** Registry HTTP DELETE 方法。 */
    public static final String METHOD_DELETE = "DELETE";
    private static final String HTTP_SCHEME = "http";
    private static final String HTTPS_SCHEME = "https";
    private static final String HEADER_ACCEPT = "Accept";
    private static final String HEADER_CONTENT_TYPE = "Content-Type";
    private static final String MEDIA_TYPE_JSON = "application/json";
    private static final int MAX_RESPONSE_CHARS = 4_194_304;

    private final HttpClient client;
    private final ObjectMapper mapper = new ObjectMapper();
    private final List<String> endpoints;
    private final String credentialHeader;
    private final String credential;
    private final Duration timeout;
    private final AtomicInteger nextEndpoint = new AtomicInteger();

    /**
     * 构造具备端点故障转移的 HTTP 控制面客户端。
     *
     * @param endpoints 完整 http/https URL，可含 Eureka Context Path
     * @param credentialHeader 凭证 Header 名称，空字符串表示关闭鉴权
     * @param credential 凭证值
     * @param timeout 每次 HTTP 请求上限
     */
    public HttpRegistryClient(
            List<String> endpoints, String credentialHeader,
            String credential, Duration timeout) {
        if (endpoints == null || endpoints.isEmpty()) {
            throw new IllegalArgumentException("Registry endpoints must not be empty");
        }
        List<String> parsed = new ArrayList<>();
        for (String endpoint : endpoints) {
            URI url = URI.create(endpoint);
            if (!(HTTP_SCHEME.equalsIgnoreCase(url.getScheme())
                    || HTTPS_SCHEME.equalsIgnoreCase(url.getScheme()))
                    || url.getHost() == null || url.getUserInfo() != null
                    || url.getQuery() != null || url.getFragment() != null) {
                throw new IllegalArgumentException(
                        "Registry endpoint must be an http(s) URL without credentials");
            }
            parsed.add(endpoint.replaceAll("/+$", ""));
        }
        this.endpoints = List.copyOf(parsed);
        this.credentialHeader = credentialHeader == null ? "" : credentialHeader;
        this.credential = credential == null ? "" : credential;
        if (timeout == null || timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("HTTP request timeout must be positive");
        }
        this.timeout = timeout;
        this.client = HttpClient.newBuilder()
                .connectTimeout(timeout)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    /**
     * 发送 JSON 控制面请求，网络或 5xx 错误可尝试备用节点。
     *
     * @param method HTTP 方法
     * @param path 服务端相对路径，以 / 开头
     * @param payload JSON 请求对象，可为 null
     * @return 远端状态与响应对象
     * @throws IOException 网络、JSON 或 HTTP 状态错误
     * @throws InterruptedException 请求被中断
     */
    public Response request(String method, String path, Object payload)
            throws IOException, InterruptedException {
        if (!path.startsWith("/") || path.startsWith("//")) {
            throw new IllegalArgumentException("Invalid registry API path");
        }
        String body = payload == null ? "" : mapper.writeValueAsString(payload);
        IOException last = null;
        int offset = Math.floorMod(nextEndpoint.getAndIncrement(), endpoints.size());
        for (int attempt = 0; attempt < endpoints.size(); attempt++) {
            String endpoint = endpoints.get((offset + attempt) % endpoints.size());
            try {
                HttpRequest.Builder builder = HttpRequest.newBuilder()
                        .uri(URI.create(endpoint + path))
                        .timeout(timeout)
                        .header(HEADER_ACCEPT, MEDIA_TYPE_JSON);
                if (!credentialHeader.isBlank() && !credential.isBlank()) {
                    builder.header(credentialHeader, credential);
                }
                if (payload != null) {
                    builder.header(HEADER_CONTENT_TYPE, MEDIA_TYPE_JSON);
                }
                HttpRequest req = builder.method(method,
                        payload == null ? HttpRequest.BodyPublishers.noBody()
                                : HttpRequest.BodyPublishers.ofString(
                                        body, StandardCharsets.UTF_8)).build();
                HttpResponse<String> resp;
                try {
                    resp = client.send(
                            req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                } catch (IOException networkError) {
                    last = new IOException(
                            "Registry HTTP endpoint unavailable", networkError);
                    continue;
                }
                if (resp.statusCode() >= HttpURLConnection.HTTP_INTERNAL_ERROR) {
                    last = new IOException("Registry HTTP server unavailable: status="
                            + resp.statusCode());
                    continue;
                }
                if (resp.statusCode() >= HttpURLConnection.HTTP_BAD_REQUEST
                        && resp.statusCode() != HttpURLConnection.HTTP_NOT_FOUND) {
                    throw new IOException("Registry HTTP request rejected: status="
                            + resp.statusCode());
                }
                String responseBody = resp.body();
                if (responseBody.length() > MAX_RESPONSE_CHARS) {
                    throw new IOException("Registry HTTP response exceeds safety limit");
                }
                JsonNode json = responseBody.isBlank()
                        ? NullNode.getInstance() : mapper.readTree(responseBody);
                return new Response(resp.statusCode(), json);
            } catch (IllegalArgumentException invalidUrl) {
                throw new IllegalArgumentException("Invalid registry HTTP request", invalidUrl);
            }
        }
        throw last == null
                ? new IOException("Registry HTTP request failed") : last;
    }

    /**
     * HTTP 控制面响应，不包含凭证或完整 URL。
     *
     * @param status HTTP 响应状态码
     * @param json 已解析的 JSON 内容
     */
    public record Response(int status, JsonNode json) {
        /**
         * 判断远端请求是否完成。
         *
         * @return 请求是否为 HTTP 2xx
         */
        public boolean successful() {
            return status >= HttpURLConnection.HTTP_OK
                    && status < HttpURLConnection.HTTP_MULT_CHOICE;
        }
    }

    /** 停止 HTTP 客户端。 */
    @Override
    public void close() {
        client.close();
    }
}
