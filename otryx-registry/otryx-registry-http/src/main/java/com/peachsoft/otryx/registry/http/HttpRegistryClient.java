package com.peachsoft.otryx.registry.http;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.core.exc.StreamConstraintsException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.NullNode;
import com.peachsoft.otryx.registry.RegistrySecurityException;
import com.peachsoft.otryx.registry.RegistrySecurityFailure;
import com.peachsoft.otryx.registry.RegistryTlsMaterial;
import com.peachsoft.otryx.registry.RegistryTlsOptions;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLParameters;

/**
 * 在实际接收过程中限制字节、校验 JSON 并以单一绝对截止时间管理 HTTP 控制面请求。
 *
 * <p>只允许在既有有界控制面 Executor 中同步等待；不增加解析工作线程或定时任务。
 * 所有端点使用相同明确 TLS Context，不转发重定向，不重试非 GET 请求。
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
    /** Eureka 原始 UTF-8 响应字节上限。 */
    public static final int DEFAULT_MAX_RESPONSE_BYTES = 4 * 1024 * 1024;
    private static final int MAX_RESPONSE_BYTES = 12 * 1024 * 1024;
    private static final int MAX_ENDPOINTS = 16;
    private static final int MAX_ENDPOINT_CHARACTERS = 2048;
    private static final int MAX_HEADER_CHARACTERS = 8192;
    private static final int MAX_IN_FLIGHT = 64;
    private static final int MAX_JSON_DEPTH = 64;
    private static final int MAX_JSON_STRING = 512 * 1024;
    private static final int MAX_JSON_NUMBER = 64;
    private static final int MAX_JSON_NAME = 256;
    private static final int MAX_CAUSE_DEPTH = 32;
    private static final Duration MAX_TIMEOUT = Duration.ofMinutes(1);
    private static final String HEADER_ACCEPT = "Accept";
    private static final String HEADER_CONTENT_TYPE = "Content-Type";
    private static final String HEADER_CONTENT_LENGTH = "Content-Length";
    private static final String HEADER_CONTENT_ENCODING = "Content-Encoding";
    private static final String MEDIA_TYPE_JSON = "application/json";
    private static final Set<String> METHODS = Set.of(METHOD_GET, METHOD_POST, METHOD_PUT, METHOD_DELETE);
    private static final ObjectMapper MAPPER = new ObjectMapper(JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .streamReadConstraints(StreamReadConstraints.builder()
                    .maxNestingDepth(MAX_JSON_DEPTH).maxStringLength(MAX_JSON_STRING)
                    .maxNumberLength(MAX_JSON_NUMBER).maxNameLength(MAX_JSON_NAME).build())
            .build()).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private final HttpClient client;
    private final List<String> endpoints;
    private final String credentialHeader;
    private final String credential;
    private final Duration timeout;
    private final int maximumResponseBytes;
    private final AtomicInteger nextEndpoint = new AtomicInteger();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Semaphore capacity = new Semaphore(MAX_IN_FLIGHT);
    private final Set<Exchange> exchanges = ConcurrentHashMap.newKeySet();

    /**
     * 创建明确开发场景中的明文客户端；HTTPS 必须改用带独立 TLS 材料的构造入口。
     *
     * @param endpoints 完整 HTTP URL，可含 Eureka Context Path
     * @param credentialHeader 空字符串或支持的凭据 Header 名称
     * @param credential 凭据值
     * @param timeout 单次逻辑请求总预算
     */
    public HttpRegistryClient(
            List<String> endpoints, String credentialHeader, String credential, Duration timeout) {
        this(endpoints, credentialHeader, credential, timeout, DEFAULT_MAX_RESPONSE_BYTES, Optional.empty());
    }

    /**
     * 创建安装明确 CA 与可选客户端身份的有界 HTTP 客户端。
     *
     * @param endpoints 全部使用同一种已指定传输保护的完整 URL
     * @param credentialHeader 空字符串、Authorization 或 X-Consul-Token
     * @param credential 实际发送的后端凭据
     * @param timeout body、故障切换及解析共用的总预算
     * @param maximumResponseBytes 该后端原有的响应 envelope 字节上限
     * @param tls 独立 TLS 材料，不读取全局默认信任材料
     */
    public HttpRegistryClient(
            List<String> endpoints, String credentialHeader, String credential, Duration timeout,
            int maximumResponseBytes, Optional<RegistryTlsOptions> tls) {
        if (timeout == null || timeout.isNegative() || timeout.isZero() || timeout.compareTo(MAX_TIMEOUT) > 0
                || maximumResponseBytes < 1 || maximumResponseBytes > MAX_RESPONSE_BYTES) {
            throw new IllegalArgumentException("Invalid Registry HTTP budget");
        }
        Objects.requireNonNull(tls, "tls");
        this.endpoints = parseEndpoints(endpoints, tls.isPresent());
        this.credentialHeader = credentialHeader == null ? "" : credentialHeader;
        this.credential = credential == null ? "" : credential;
        if (!(this.credentialHeader.isEmpty() || "Authorization".equals(this.credentialHeader)
                || "X-Consul-Token".equals(this.credentialHeader))
                || this.credential.length() > MAX_HEADER_CHARACTERS
                || this.credential.indexOf('\r') >= 0 || this.credential.indexOf('\n') >= 0
                || this.credentialHeader.isEmpty() && !this.credential.isEmpty()) {
            throw new IllegalArgumentException("Invalid Registry HTTP credential configuration");
        }
        this.timeout = timeout;
        this.maximumResponseBytes = maximumResponseBytes;
        HttpClient.Builder builder = HttpClient.newBuilder()
                .connectTimeout(timeout).followRedirects(HttpClient.Redirect.NEVER);
        if (tls.isPresent()) {
            RegistryTlsMaterial material = RegistryTlsMaterial.load(tls.orElseThrow());
            SSLParameters parameters = new SSLParameters();
            parameters.setEndpointIdentificationAlgorithm("HTTPS");
            parameters.setProtocols(new String[] {"TLSv1.3", "TLSv1.2"});
            builder.sslContext(material.sslContext()).sslParameters(parameters);
        }
        this.client = builder.build();
    }

    /**
     * 发送并解析 JSON，请求的总预算在序列化之前开始。
     *
     * @param method 受支持的 HTTP 方法
     * @param path 以单个 / 开头的服务端相对路径
     * @param payload 可空 JSON 请求对象
     * @return 不包含凭据和完整 URL 的响应；404 保留缺失状态，正文返回 NullNode
     * @throws IOException 普通网络不可用或总预算到期
     * @throws InterruptedException 调用线程被中断且实际请求已取消
     */
    public Response request(String method, String path, Object payload)
            throws IOException, InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        byte[] bytes;
        try {
            bytes = payload == null ? null : MAPPER.writeValueAsBytes(payload);
        } catch (IOException | RuntimeException invalid) {
            throw integrity();
        }
        return exchange(method, path, bytes, MEDIA_TYPE_JSON,
                (status, body) -> new Response(status, status == HttpURLConnection.HTTP_NOT_FOUND
                        ? NullNode.getInstance() : readJson(body)), deadline);
    }

    /**
     * 使后端特有的有界解析处于同一次 HTTP 绝对截止时间之内。
     *
     * @param method HTTP 方法；只有 GET 允许有限故障切换
     * @param path 服务端相对路径
     * @param payload 可空原始请求字节
     * @param contentType 有 payload 时的媒体类型
     * @param reader 只处理受限内存字节的后端解析器，不执行 IO
     * @param <T> 已验证的后端结果
     * @return 在同一预算内解析完成的结果
     * @throws IOException 普通网络错误或总预算到期
     * @throws InterruptedException 调用方取消
     */
    public <T> T requestBytes(
            String method, String path, byte[] payload, String contentType, BodyReader<T> reader)
            throws IOException, InterruptedException {
        return exchange(method, path, payload, contentType, reader, System.nanoTime() + timeout.toNanos());
    }

    /**
     * 严格 UTF-8 和有限深度、scalar、字段名 JSON 解析，不启用多态反序列化。
     *
     * @param bytes 已由接收器执行字节预算的响应
     * @return 空 body 为 NullNode
     */
    public static JsonNode readJson(byte[] bytes) {
        try {
            String text = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
            return text.isBlank() ? NullNode.getInstance() : MAPPER.readTree(text);
        } catch (StreamConstraintsException excessive) {
            throw new RegistrySecurityException(RegistrySecurityFailure.RESPONSE_LIMIT_EXCEEDED);
        } catch (CharacterCodingException invalid) {
            throw integrity();
        } catch (IOException invalid) {
            throw integrity();
        }
    }

    private <T> T exchange(
            String method, String path, byte[] payload, String contentType, BodyReader<T> reader, long deadline)
            throws IOException, InterruptedException {
        if (!METHODS.contains(method) || path == null || !path.startsWith("/")
                || path.startsWith("//") || path.indexOf('#') >= 0 || reader == null) {
            throw new IllegalArgumentException("Invalid Registry HTTP request");
        }
        remaining(deadline);
        if (closed.get() || !capacity.tryAcquire()) {
            throw new IOException("Registry HTTP client unavailable");
        }
        try {
            int offset = Math.floorMod(nextEndpoint.getAndIncrement(), endpoints.size());
            int attempts = METHOD_GET.equals(method) ? endpoints.size() : 1;
            for (int attempt = 0; attempt < attempts; attempt++) {
                remaining(deadline);
                String endpoint = endpoints.get((offset + attempt) % endpoints.size());
                HttpResponse<byte[]> response;
                try {
                    response = send(method, endpoint + path, payload, contentType, deadline);
                } catch (HttpTimeoutException timeoutFailure) {
                    throw timeoutFailure;
                } catch (IOException unavailable) {
                    if (attempt + 1 == attempts || closed.get()) {
                        throw new IOException("Registry HTTP endpoint unavailable");
                    }
                    continue;
                }
                int status = response.statusCode();
                if (status == HttpURLConnection.HTTP_UNAUTHORIZED) {
                    throw new RegistrySecurityException(RegistrySecurityFailure.AUTHENTICATION_REJECTED);
                }
                if (status == HttpURLConnection.HTTP_FORBIDDEN) {
                    throw new RegistrySecurityException(RegistrySecurityFailure.AUTHORIZATION_REJECTED);
                }
                if (status >= HttpURLConnection.HTTP_INTERNAL_ERROR && attempt + 1 < attempts) {
                    continue;
                }
                if (!(status >= HttpURLConnection.HTTP_OK && status < HttpURLConnection.HTTP_MULT_CHOICE)
                        && status != HttpURLConnection.HTTP_NOT_FOUND) {
                    throw new IOException("Registry HTTP request rejected: status=" + status);
                }
                remaining(deadline);
                T value;
                try {
                    value = reader.read(status, response.body());
                } catch (RegistrySecurityException rejected) {
                    throw rejected;
                } catch (RuntimeException invalid) {
                    throw integrity();
                }
                remaining(deadline);
                if (closed.get()) {
                    throw new IOException("Registry HTTP client closed");
                }
                return value;
            }
            throw new IOException("Registry HTTP endpoint unavailable");
        } finally {
            capacity.release();
        }
    }

    private HttpResponse<byte[]> send(
            String method, String endpoint, byte[] payload, String contentType, long deadline)
            throws IOException, InterruptedException {
        Exchange exchange = new Exchange();
        exchanges.add(exchange);
        try {
            if (closed.get()) {
                throw new IOException("Registry HTTP client closed");
            }
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(endpoint)).timeout(Duration.ofNanos(remaining(deadline)))
                    .header(HEADER_ACCEPT, MEDIA_TYPE_JSON);
            if (!credentialHeader.isEmpty() && !credential.isEmpty()) {
                builder.header(credentialHeader, credential);
            }
            if (payload != null) {
                builder.header(HEADER_CONTENT_TYPE, contentType);
            }
            HttpRequest request = builder.method(method, payload == null
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofByteArray(payload)).build();
            CompletableFuture<HttpResponse<byte[]>> actual = client.sendAsync(request, info -> {
                BoundedBodySubscriber subscriber = new BoundedBodySubscriber(maximumResponseBytes);
                exchange.attach(subscriber);
                if (!info.headers().firstValue(HEADER_CONTENT_ENCODING).orElse("identity")
                        .equalsIgnoreCase("identity")) {
                    subscriber.reject(integrity());
                }
                try {
                    if (info.headers().firstValueAsLong(HEADER_CONTENT_LENGTH).orElse(0) > maximumResponseBytes) {
                        subscriber.reject(new RegistrySecurityException(
                                RegistrySecurityFailure.RESPONSE_LIMIT_EXCEEDED));
                    }
                } catch (NumberFormatException invalid) {
                    subscriber.reject(integrity());
                }
                return subscriber;
            });
            exchange.attach(actual);
            return actual.get(remaining(deadline), TimeUnit.NANOSECONDS);
        } catch (TimeoutException expired) {
            throw new HttpTimeoutException("Registry HTTP total deadline exceeded");
        } catch (ExecutionException failed) {
            Throwable cause = unwrap(failed);
            RegistrySecurityException security = securityCause(cause);
            if (security != null) {
                throw security;
            }
            if (hasTlsCause(cause)) {
                throw new RegistrySecurityException(RegistrySecurityFailure.TLS_REJECTED);
            }
            if (cause instanceof HttpTimeoutException) {
                throw new HttpTimeoutException("Registry HTTP total deadline exceeded");
            }
            throw new IOException("Registry HTTP endpoint unavailable");
        } catch (CancellationException cancelled) {
            throw new IOException("Registry HTTP exchange cancelled");
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException("Invalid Registry HTTP request");
        } finally {
            exchange.cancel();
            exchanges.remove(exchange);
        }
    }

    private static List<String> parseEndpoints(List<String> values, boolean tls) {
        if (values == null || values.isEmpty() || values.size() > MAX_ENDPOINTS) {
            throw new IllegalArgumentException("Invalid Registry HTTP endpoints");
        }
        List<String> parsed = new ArrayList<>();
        for (String endpoint : values) {
            try {
                if (endpoint == null || endpoint.length() > MAX_ENDPOINT_CHARACTERS) {
                    throw new IllegalArgumentException("Invalid Registry HTTP endpoint");
                }
                URI uri = URI.create(endpoint);
                if (!(tls ? "https" : "http").equalsIgnoreCase(uri.getScheme())
                        || uri.getHost() == null || uri.getUserInfo() != null
                        || uri.getQuery() != null || uri.getFragment() != null) {
                    throw new IllegalArgumentException("Invalid Registry HTTP endpoint");
                }
                parsed.add(endpoint.replaceAll("/+$", ""));
            } catch (IllegalArgumentException invalid) {
                throw new IllegalArgumentException("Invalid Registry HTTP endpoint");
            }
        }
        return List.copyOf(parsed);
    }

    private static long remaining(long deadline) throws HttpTimeoutException {
        long value = deadline - System.nanoTime();
        if (value <= 0) {
            throw new HttpTimeoutException("Registry HTTP total deadline exceeded");
        }
        return value;
    }

    private static Throwable unwrap(Throwable failure) {
        Throwable current = failure;
        for (int depth = 0; depth < MAX_CAUSE_DEPTH; depth++) {
            if ((current instanceof CompletionException || current instanceof ExecutionException)
                    && current.getCause() != null && current.getCause() != current) {
                current = current.getCause();
            } else {
                return current;
            }
        }
        return current;
    }

    private static RegistrySecurityException securityCause(Throwable failure) {
        Throwable current = failure;
        for (int depth = 0; current != null && depth < MAX_CAUSE_DEPTH; depth++) {
            if (current instanceof RegistrySecurityException security) {
                return security;
            }
            if (current.getCause() == current) {
                break;
            }
            current = current.getCause();
        }
        return null;
    }

    private static boolean hasTlsCause(Throwable failure) {
        Throwable current = failure;
        for (int depth = 0; current != null && depth < MAX_CAUSE_DEPTH; depth++) {
            if (current instanceof SSLException || current instanceof java.security.cert.CertificateException) {
                return true;
            }
            Throwable next = current.getCause();
            if (next == current) {
                break;
            }
            current = next;
        }
        return false;
    }

    private static RegistrySecurityException integrity() {
        return new RegistrySecurityException(RegistrySecurityFailure.METADATA_INTEGRITY_REJECTED);
    }

    /**
     * 后端只解析已被实际接收字节预算保护的内存内容。
     *
     * @param <T> 已验证的返回值类型
     */
    @FunctionalInterface
    public interface BodyReader<T> {
        /**
         * 完成后端特有的结构与引用校验，不执行 IO。
         *
         * @param status 已接受的 HTTP 状态
         * @param body 受限原始字节
         * @return 后端结果
         */
        T read(int status, byte[] body);
    }

    /**
     * HTTP 控制面响应，默认描述不包含原始响应或秘密。
     *
     * @param status HTTP 响应状态码
     * @param json 已解析的 JSON 内容
     */
    public record Response(int status, JsonNode json) {
        /**
         * 返回真实 HTTP 2xx 状态。
         *
         * @return 状态码属于 HTTP 2xx 时返回 true
         */
        public boolean successful() {
            return status >= HttpURLConnection.HTTP_OK && status < HttpURLConnection.HTTP_MULT_CHOICE;
        }

        /** 只描述状态，不输出服务端正文。 */
        @Override
        public String toString() {
            return "RegistryHttpResponse[status=" + status + ']';
        }
    }

    /** 先终止在途真实 exchange 和 body，再立即关闭客户端资源。 */
    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            exchanges.forEach(Exchange::cancel);
            client.shutdownNow();
        }
    }

    private static final class Exchange {
        private BoundedBodySubscriber subscriber;
        private CompletableFuture<?> actual;
        private boolean cancelled;

        synchronized void attach(BoundedBodySubscriber value) {
            subscriber = value;
            if (cancelled) {
                value.cancel();
            }
        }

        synchronized void attach(CompletableFuture<?> value) {
            actual = value;
            if (cancelled) {
                value.cancel(true);
            }
        }

        synchronized void cancel() {
            if (!cancelled) {
                cancelled = true;
                if (subscriber != null) {
                    subscriber.cancel();
                }
                if (actual != null) {
                    actual.cancel(true);
                }
            }
        }
    }
}
