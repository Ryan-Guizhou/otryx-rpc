package com.peachsoft.otryx.registry.etcd;

import com.peachsoft.otryx.api.ApplicationKey;
import com.peachsoft.otryx.registry.ApplicationRegistry;
import com.peachsoft.otryx.registry.ApplicationRegistryFactory;
import com.peachsoft.otryx.registry.CredentialKind;
import com.peachsoft.otryx.registry.RegistryChannel;
import com.peachsoft.otryx.registry.RegistryOptions;
import com.peachsoft.otryx.registry.RegistrySecurityException;
import com.peachsoft.otryx.registry.RegistrySecurityFailure;
import com.peachsoft.otryx.registry.RegistrySecuritySupport;
import com.peachsoft.otryx.registry.RegistryTlsMaterial;
import com.peachsoft.otryx.spi.Extension;
import io.etcd.jetcd.ByteSequence;
import io.etcd.jetcd.Client;
import io.etcd.jetcd.ClientBuilder;
import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.ForwardingClientCall;
import io.grpc.ForwardingClientCallListener;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.Status;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLException;

/**
 * 以实际 Netty gRPC TLS Context 与独立用户凭据创建 Etcd 应用控制面。
 *
 * <p>明确 CA、hostname 校验和凭据安装先于真实来源描述；不接受 authority 覆盖、
 * trustAll、隐式系统信任或原始字符串安全开关。客户端证书可附于用户密码认证。
 *
 * @Author OpenAI
 * @Version 1.0.0-SNAPSHOT
 * @CreateTime 2026/10/16 10:33
 */
@Extension(EtcdApplicationRegistryFactory.EXTENSION_NAME)
public final class EtcdApplicationRegistryFactory implements ApplicationRegistryFactory {
    /** 应用级 Etcd SPI 名称。 */
    public static final String EXTENSION_NAME = "etcd";
    private static final String OPTION_LEASE_TTL_SECONDS = "leaseTtlSeconds";
    private static final long DEFAULT_LEASE_TTL_SECONDS = 30L;
    private static final int MAX_INBOUND_BYTES = 12 * 1024 * 1024;
    private static final long REQUEST_TIMEOUT_SECONDS = 3;
    private static final String DEFAULT_NAMESPACE = "default";
    private static final List<String> DEFAULT_ENDPOINTS = List.of("http://127.0.0.1:2379");
    private static final Set<String> ALLOWED_OPTIONS = Set.of(OPTION_LEASE_TTL_SECONDS);
    private static final Set<CredentialKind> CREDENTIALS = Set.of(CredentialKind.NONE, CredentialKind.USER_PASSWORD);

    /** 创建无状态 Etcd Factory。 */
    public EtcdApplicationRegistryFactory() {
    }

    /** 创建共享同一受保护连接与命名空间的 Membership、Manifest 和 Mapping。 */
    @Override
    public ApplicationRegistry create(RegistryOptions options) {
        Objects.requireNonNull(options, "options");
        RegistrySecuritySupport.requireOptions(options, ALLOWED_OPTIONS, false);
        List<String> endpoints = options.endpoints().isEmpty() ? DEFAULT_ENDPOINTS : options.endpoints();
        RegistrySecuritySupport.requireTransport(options, endpoints, CREDENTIALS);
        for (String endpoint : endpoints) {
            String path = URI.create(endpoint).getPath();
            if (path != null && !path.isEmpty() && !"/".equals(path)) {
                throw new RegistrySecurityException(RegistrySecurityFailure.UNSUPPORTED_PROTECTION);
            }
        }
        long ttl;
        try {
            ttl = Long.parseLong(options.providerOption(
                    OPTION_LEASE_TTL_SECONDS, Long.toString(DEFAULT_LEASE_TTL_SECONDS)));
        } catch (NumberFormatException invalid) {
            throw new IllegalArgumentException("Invalid Etcd lease TTL");
        }
        if (ttl < 1 || ttl > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Invalid Etcd lease TTL");
        }
        String namespace = new ApplicationKey(
                options.namespace().isBlank() ? DEFAULT_NAMESPACE : options.namespace(), "registry").namespace();
        ClientBuilder builder = Client.builder().endpoints(endpoints.toArray(String[]::new))
                .maxInboundMessageSize(MAX_INBOUND_BYTES)
                .connectTimeout(Duration.ofSeconds(REQUEST_TIMEOUT_SECONDS))
                .retryMaxAttempts(1).retryMaxDuration(Duration.ofSeconds(REQUEST_TIMEOUT_SECONDS))
                .waitForReady(false)
                .interceptor(new RequestDeadline()).authInterceptors(new RequestDeadline());
        if (options.security().tls().isPresent()) {
            RegistryTlsMaterial material = RegistryTlsMaterial.load(options.security().tls().orElseThrow());
            try {
                builder.sslContext(context -> {
                    context.trustManager(material.trustManagerFactory()).protocols("TLSv1.3", "TLSv1.2");
                    material.privateKey().ifPresent(key -> context.keyManager(key, material.certificateChain()));
                });
            } catch (SSLException invalid) {
                throw new RegistrySecurityException(RegistrySecurityFailure.TLS_REJECTED);
            }
        }
        if (options.security().credentials().kind() == CredentialKind.USER_PASSWORD) {
            char[] password = options.security().credentials().password();
            try {
                builder.user(ByteSequence.from(options.security().credentials().username(), StandardCharsets.UTF_8));
                builder.password(ByteSequence.from(new String(password), StandardCharsets.UTF_8));
            } finally {
                Arrays.fill(password, '\0');
            }
        }
        Client client = builder.build();
        try {
            return new EtcdApplicationRegistry(client, ttl, namespace, options.observer(),
                    RegistrySecuritySupport.installed(options.security(), namespace, Optional.empty(),
                            RegistryChannel.MEMBERSHIP, RegistryChannel.MANIFEST, RegistryChannel.MAPPING));
        } catch (RuntimeException invalid) {
            client.close();
            throw invalid;
        }
    }

    /** Unary 请求和认证使用有限实际 gRPC deadline；Watch/keepalive 流保持既有生命周期。 */
    private static final class RequestDeadline implements ClientInterceptor {
        @Override
        public <RequestT, ResponseT> ClientCall<RequestT, ResponseT> interceptCall(
                MethodDescriptor<RequestT, ResponseT> method, CallOptions options, Channel next) {
            if (method.getType() == MethodDescriptor.MethodType.UNARY
                    && (options.getDeadline() == null
                    || options.getDeadline().timeRemaining(TimeUnit.NANOSECONDS)
                            > TimeUnit.SECONDS.toNanos(REQUEST_TIMEOUT_SECONDS))) {
                options = options.withDeadlineAfter(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            }
            ClientCall<RequestT, ResponseT> call = next.newCall(method, options);
            if (!method.getFullMethodName().endsWith("/Authenticate")) {
                return call;
            }
            return new ForwardingClientCall.SimpleForwardingClientCall<>(call) {
                @Override
                public void start(Listener<ResponseT> listener, Metadata headers) {
                    super.start(new ForwardingClientCallListener.SimpleForwardingClientCallListener<>(listener) {
                        @Override
                        public void onClose(Status status, Metadata trailers) {
                            // Etcd Auth/Authenticate 对错误用户/密码返回 INVALID_ARGUMENT。
                            // 只在该实际认证 RPC 处理此状态，不扩展到任意 KV 请求。
                            if (status.getCode() == Status.Code.INVALID_ARGUMENT
                                    || status.getCode() == Status.Code.UNAUTHENTICATED) {
                                status = Status.UNAUTHENTICATED;
                            }
                            super.onClose(status, trailers);
                        }
                    }, headers);
                }
            };
        }
    }
}
