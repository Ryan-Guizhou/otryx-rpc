package com.peachsoft.otryx.registry.consul;

import com.peachsoft.otryx.registry.ApplicationRegistry;
import com.peachsoft.otryx.registry.ApplicationRegistryFactory;
import com.peachsoft.otryx.registry.RegistryOptions;
import com.peachsoft.otryx.spi.Extension;
import java.time.Duration;
import java.util.List;
import java.util.Objects;

/**
 * Consul V2 应用级 Registry SPI 工厂，与旧接口级工厂完全隔离。
 *
 * <p>必须指定单个本地 Agent，TTL Check 只能向登记该 Check 的 Agent 续期。
 * 创建后的 Manifest/Mapping Repository 由同一 Registry 的 close 释放。
 *
 * @Author OpenAI
 * @Version 1.0.0-SNAPSHOT
 * @CreateTime 2026/10/16 15:00
 */
@Extension(ConsulApplicationRegistryFactory.EXTENSION_NAME)
public final class ConsulApplicationRegistryFactory implements ApplicationRegistryFactory {

    /** SPI 使用的 Registry 名称。 */
    public static final String EXTENSION_NAME = "consul";
    private static final String DEFAULT_ENDPOINT = "http://127.0.0.1:8500";
    private static final String DEFAULT_NAMESPACE = "default";
    private static final String OPTION_TOKEN = "consulToken";
    private static final String OPTION_TTL_SECONDS = "consulTtlSeconds";
    private static final String OPTION_HEARTBEAT_SECONDS = "consulHeartbeatSeconds";
    private static final String OPTION_POLL_MILLIS = "consulPollIntervalMillis";
    private static final String OPTION_TIMEOUT_MILLIS = "consulRequestTimeoutMillis";
    private static final String OPTION_ENTERPRISE_NAMESPACE = "consulEnterpriseNamespace";
    private static final int DEFAULT_TTL_SECONDS = 30;
    private static final int MIN_TTL_SECONDS = 10;
    private static final int MAX_TTL_SECONDS = 3600;
    private static final int DEFAULT_HEARTBEAT_SECONDS = 10;
    private static final int MIN_HEARTBEAT_SECONDS = 1;
    private static final int DEFAULT_POLL_MILLIS = 1000;
    private static final int MIN_POLL_MILLIS = 200;
    private static final int MAX_POLL_MILLIS = 60000;
    private static final int DEFAULT_TIMEOUT_MILLIS = 3000;
    private static final int MIN_TIMEOUT_MILLIS = 100;
    private static final int MAX_TIMEOUT_MILLIS = 60000;

    /** 创建无状态 Registry Factory。 */
    public ConsulApplicationRegistryFactory() {
    }

    /** 创建每进程独立、同时支持所有应用角色的 Consul V2 Registry。 */
    @Override
    public ApplicationRegistry create(RegistryOptions options) {
        Objects.requireNonNull(options, "options");
        List<String> endpoints = options.endpoints().isEmpty()
                ? List.of(DEFAULT_ENDPOINT) : options.endpoints();
        if (endpoints.size() != 1) {
            throw new IllegalArgumentException("Consul V2 requires exactly one local Agent endpoint");
        }
        int ttl = parse(options, OPTION_TTL_SECONDS, DEFAULT_TTL_SECONDS,
                MIN_TTL_SECONDS, MAX_TTL_SECONDS);
        int heartbeat = parse(options, OPTION_HEARTBEAT_SECONDS, DEFAULT_HEARTBEAT_SECONDS,
                MIN_HEARTBEAT_SECONDS, ttl - 1);
        int poll = parse(options, OPTION_POLL_MILLIS, DEFAULT_POLL_MILLIS,
                MIN_POLL_MILLIS, MAX_POLL_MILLIS);
        int timeout = parse(options, OPTION_TIMEOUT_MILLIS, DEFAULT_TIMEOUT_MILLIS,
                MIN_TIMEOUT_MILLIS, MAX_TIMEOUT_MILLIS);
        String namespace = options.namespace().isBlank() ? DEFAULT_NAMESPACE : options.namespace();
        ConsulV2Identity identity = new ConsulV2Identity(namespace);
        ConsulV2Client client = new ConsulV2Client(
                endpoints.getFirst(),
                options.providerOption(OPTION_TOKEN, ""),
                options.providerOption(OPTION_ENTERPRISE_NAMESPACE, ""),
                Duration.ofMillis(timeout));
        ConsulV2Executor executor = new ConsulV2Executor();
        try {
            return new ConsulApplicationRegistry(client, executor, identity,
                    ttl, Duration.ofSeconds(heartbeat), Duration.ofMillis(poll), options.observer());
        } catch (RuntimeException failure) {
            executor.close();
            client.close();
            throw failure;
        }
    }

    private static int parse(
            RegistryOptions options, String key, int fallback, int minimum, int maximum) {
        int value;
        try {
            value = Integer.parseInt(options.providerOption(key, Integer.toString(fallback)));
        } catch (NumberFormatException invalid) {
            throw new IllegalArgumentException("Invalid Consul numeric setting: " + key, invalid);
        }
        if (value < minimum || value > maximum) {
            throw new IllegalArgumentException("Consul numeric setting out of range: " + key);
        }
        return value;
    }
}
