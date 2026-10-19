package com.peachsoft.otryx.registry.nacos;

import com.alibaba.nacos.api.NacosFactory;
import com.alibaba.nacos.api.config.ConfigService;
import com.peachsoft.otryx.registry.RegistryOptions;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

/**
 * Nacos V2 Config 分片的独占维护期初始化工具。
 *
 * <p>Nacos 3.2.4 的空 casMd5 在服务端不会执行真正的 CAS；因此运行时永远不创建
 * 不存在的 Mapping 分片，而是拒绝启动发布。初始化必须由部署管道在任何
 * Provider/Consumer 写入 Mapping 之前执行，禁止与业务运行时并发初始化。
 * 已初始化的有效文档严格保留，既不清空也不覆盖。
 *
 * @Author OpenAI
 * @Version 1.0.0-SNAPSHOT
 * @CreateTime 2026/10/16 13:42
 */
public final class NacosV2MetadataBootstrap {

    private static final String OPTION_GROUP = "nacosGroup";
    private static final int COMMAND_ARGUMENT_COUNT = 4;
    private static final String VALID_GROUP = "[A-Za-z0-9._:-]{1,128}";
    private static final int VERIFY_ATTEMPTS = 16;
    private static final long VERIFY_DELAY_MILLIS = 250L;

    private NacosV2MetadataBootstrap() {
    }

    /**
     * 在独立部署进程中初始化显式指定的 Nacos Mapping Scope。
     *
     * <p>此入口执行阻塞控制面 IO，只能在应用写入前的独占维护窗口运行，
     * 不得嵌入 Spring 启动、EventLoop 或业务故障恢复回调。连接与验证失败直接
     * 终止命令；已有分片仍遵守 {@link #initialize(RegistryOptions)} 的保留规则。
     *
     * @param args 依次为 endpoint、namespace、group 和显式 TRUSTED_DEVELOPMENT
     * @throws IllegalArgumentException 参数缺失、目标为空或 endpoint 内嵌凭据时抛出
     * @throws IllegalStateException Nacos 初始化或已有分片验证失败时抛出
     */
    public static void main(String[] args) {
        initialize(commandLineOptions(args));
    }

    /** 解析显式部署目标；验证阶段不创建 SDK Client，也不允许回退到默认目标。 */
    static RegistryOptions commandLineOptions(String[] args) {
        if (args == null || args.length != COMMAND_ARGUMENT_COUNT) {
            throw new IllegalArgumentException(
                    "Nacos bootstrap requires endpoint, namespace, group and TRUSTED_DEVELOPMENT");
        }
        for (String argument : args) {
            if (argument == null || argument.isBlank()) {
                throw new IllegalArgumentException("Nacos bootstrap endpoint, namespace and group must be explicit");
            }
        }
        String group = args[2].trim();
        if (!group.matches(VALID_GROUP)) {
            throw new IllegalArgumentException("Invalid Nacos bootstrap group");
        }
        if (!"TRUSTED_DEVELOPMENT".equals(args[3])) {
            throw new com.peachsoft.otryx.registry.RegistrySecurityException(
                    com.peachsoft.otryx.registry.RegistrySecurityFailure.UNSUPPORTED_PROTECTION);
        }
        RegistryOptions parsed = RegistryOptions.fromCsv(args[0], args[1], Map.of(OPTION_GROUP, group));
        RegistryOptions options = RegistryOptions.trustedDevelopment(
                parsed.endpoints(), parsed.namespace(), parsed.providerOptions());
        if (options.endpoints().isEmpty()) {
            throw new IllegalArgumentException("Nacos bootstrap requires at least one explicit endpoint");
        }
        return options;
    }

    /**
     * 在独占维护窗口创建缺失的固定空分片，并严格验证已有非空分片的格式。
     *
     * @param options Nacos Config endpoint、namespace、group 和可选 SDK 凭据
     * @throws IllegalStateException SDK 连接、权限、验证或创建失败时抛出
     */
    public static void initialize(RegistryOptions options) {
        Objects.requireNonNull(options, "options");
        NacosApplicationRegistryFactory.requireSecurity(options);
        List<String> endpoints = options.endpoints().isEmpty()
                ? List.of(NacosApplicationRegistryFactory.DEFAULT_ENDPOINT) : options.endpoints();
        for (String endpoint : endpoints) {
            if (endpoint.contains("@")) {
                throw new IllegalArgumentException("Nacos endpoint must not contain credentials");
            }
        }
        String namespace = options.namespace().isBlank()
                ? NacosApplicationRegistryFactory.DEFAULT_NAMESPACE : options.namespace();
        String group = options.providerOption(OPTION_GROUP, NacosApplicationRegistryFactory.DEFAULT_GROUP);
        Properties properties = NacosApplicationRegistryFactory.clientProperties(options, endpoints, namespace);
        try {
            ConfigService config = NacosFactory.createConfigService(properties);
            try {
                NacosV2ConfigStore store = new NacosV2ConfigStore(config, group);
                for (int shard = 0; shard < NacosV2Names.MAPPING_SHARDS; shard++) {
                    String dataId = NacosV2Names.shardId(shard);
                    NacosV2ConfigStore.Entry existing = store.read(dataId);
                    if (existing.content() == null) {
                        String empty = NacosV2MappingCodec.encode(Map.of());
                        if (!config.publishConfig(dataId, group, empty)) {
                            throw new IllegalStateException("Nacos Mapping bootstrap could not publish a shard");
                        }
                    }
                    // Nacos Config publish ACK and query visibility can be briefly out of phase.
                    // Never turn a missing CAS MD5 into a computed or locally cached substitute.
                    for (int attempt = 0; attempt < VERIFY_ATTEMPTS
                            && (existing.content() == null || existing.md5() == null); attempt++) {
                        TimeUnit.MILLISECONDS.sleep(VERIFY_DELAY_MILLIS);
                        existing = store.read(dataId);
                    }
                    if (existing.content() == null || existing.md5() == null) {
                        throw new IllegalStateException(
                                "Nacos bootstrap has no authoritative Config state: contentPresent="
                                        + (existing.content() != null)
                                        + ", md5Present=" + (existing.md5() != null));
                    }
                    NacosV2MappingCodec.decode(existing.content(), namespace, shard);
                }
            } finally {
                config.shutDown();
            }
        } catch (Exception error) {
            if (error instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            Throwable safe = NacosFailures.normalize(error);
            if (safe instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new IllegalStateException("Nacos Mapping bootstrap failed; keep application writes disabled");
        }
    }
}
