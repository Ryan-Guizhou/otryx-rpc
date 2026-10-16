package com.peachsoft.otryx.registry.consul;

import com.peachsoft.otryx.registry.ApplicationRegistry;
import com.peachsoft.otryx.registry.ApplicationRegistryFactory;
import com.peachsoft.otryx.registry.RegistryOptions;
import com.peachsoft.otryx.registry.http.HttpRegistryClient;
import com.peachsoft.otryx.spi.Extension;
import java.time.Duration;
import java.util.List;
import java.util.Objects;

/**
 * Consul V2 应用级注册 SPI 工厂。
 *
 * <p>为 TTL Agent Service 固定单个本地 Agent HTTP(S) 端点，
 * 避免跨节点随机轮换导致续约或注销作用到错误的 Agent。
 * ACL Token 仅通过 HttpRegistryClient Header 传递，不进入日志、KV 或成员元数据。
 *
 * @Author OpenAI
 * @Version 1.0.0-SNAPSHOT
 * @CreateTime 2026/10/16 14:12
 */
@Extension(ConsulProtocol.ADAPTER_TYPE)
public final class ConsulApplicationRegistryFactory implements ApplicationRegistryFactory {

    private static final String OPTION_SESSION_TTL_SECONDS = "consulSessionTtlSeconds";
    private static final long DEFAULT_TTL_SECONDS = 30L;
    private static final long MIN_TTL_SECONDS = 10L;
    private static final long MAX_TTL_SECONDS = 3600L;
    private static final long DEFAULT_HEARTBEAT_SECONDS = 10L;
    private static final long MIN_HEARTBEAT_SECONDS = 1L;
    private static final long DEFAULT_SESSION_TTL_SECONDS = 30L;
    private static final long MIN_SESSION_TTL_SECONDS = 10L;
    private static final long MAX_SESSION_TTL_SECONDS = 300L;
    private static final long DEFAULT_POLL_MILLIS = 1000L;
    private static final long MIN_POLL_MILLIS = 200L;
    private static final long MAX_POLL_MILLIS = 60_000L;
    private static final long DEFAULT_TIMEOUT_MILLIS = 3000L;
    private static final long MIN_TIMEOUT_MILLIS = 100L;
    private static final long MAX_TIMEOUT_MILLIS = 60_000L;
    private static final String VALID_SCOPE = "[A-Za-z0-9._-]{0,128}";

    /** SPI Loader 创建 Consul V2 Factory。 */
    public ConsulApplicationRegistryFactory() {
    }

    /**
     * 创建隔离的 HTTP Agent、线程池、Session 管理和 KV 存储生命周期。
     *
     * @param options endpoint、逻辑 Namespace、ACL Token 与 TTL/Session/Poll 时间配置
     * @return 调用方负责关闭的 Consul V2 ApplicationRegistry
     */
    @Override
    public ApplicationRegistry create(RegistryOptions options) {
        Objects.requireNonNull(options, "options");
        long ttl = number(options, ConsulProtocol.OPTION_TTL_SECONDS,
                DEFAULT_TTL_SECONDS, MIN_TTL_SECONDS, MAX_TTL_SECONDS);
        long heartbeat = number(options, ConsulProtocol.OPTION_HEARTBEAT_SECONDS,
                DEFAULT_HEARTBEAT_SECONDS, MIN_HEARTBEAT_SECONDS, ttl - 1L);
        long session = number(options, OPTION_SESSION_TTL_SECONDS,
                DEFAULT_SESSION_TTL_SECONDS, MIN_SESSION_TTL_SECONDS, MAX_SESSION_TTL_SECONDS);
        long poll = number(options, ConsulProtocol.OPTION_POLL_INTERVAL_MILLIS,
                DEFAULT_POLL_MILLIS, MIN_POLL_MILLIS, MAX_POLL_MILLIS);
        long timeout = number(options, ConsulProtocol.OPTION_REQUEST_TIMEOUT_MILLIS,
                DEFAULT_TIMEOUT_MILLIS, MIN_TIMEOUT_MILLIS, MAX_TIMEOUT_MILLIS);
        String datacenter = options.providerOption(ConsulProtocol.OPTION_DATACENTER, "");
        String enterprise = options.providerOption(ConsulProtocol.OPTION_ENTERPRISE_NAMESPACE, "");
        if (!datacenter.matches(VALID_SCOPE) || !enterprise.matches(VALID_SCOPE)) {
            throw new IllegalArgumentException("Invalid Consul datacenter or Enterprise Namespace");
        }
        List<String> endpoints = options.endpoints().isEmpty()
                ? List.of(ConsulProtocol.DEFAULT_AGENT_ENDPOINT) : options.endpoints();
        if (endpoints.size() != 1) {
            throw new IllegalArgumentException("Consul V2 TTL membership requires one local Agent");
        }
        HttpRegistryClient client = new HttpRegistryClient(endpoints, ConsulProtocol.TOKEN_HEADER,
                options.providerOption(ConsulProtocol.OPTION_TOKEN, ""), Duration.ofMillis(timeout));
        ConsulV2Http http = new ConsulV2Http(client, enterprise, datacenter);
        try {
            return new ConsulApplicationRegistry(http, options.namespace(),
                    Duration.ofMillis(poll), Duration.ofSeconds(heartbeat),
                    Math.toIntExact(ttl), Math.toIntExact(session), options.observer());
        } catch (RuntimeException failure) {
            http.close();
            throw failure;
        }
    }

    private static long number(RegistryOptions options, String key, long fallback, long min, long max) {
        long parsed;
        try {
            parsed = Long.parseLong(options.providerOption(key, Long.toString(fallback)));
        } catch (NumberFormatException failure) {
            throw new IllegalArgumentException("Invalid Consul numeric option: " + key);
        }
        if (parsed < min || parsed > max) {
            throw new IllegalArgumentException("Consul numeric option out of range: " + key);
        }
        return parsed;
    }
}
