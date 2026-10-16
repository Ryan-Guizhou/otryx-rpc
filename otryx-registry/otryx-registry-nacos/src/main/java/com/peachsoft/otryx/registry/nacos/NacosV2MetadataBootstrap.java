package com.peachsoft.otryx.registry.nacos;

import com.alibaba.nacos.api.NacosFactory;
import com.alibaba.nacos.api.config.ConfigService;
import com.peachsoft.otryx.registry.RegistryOptions;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;

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

    private NacosV2MetadataBootstrap() {
    }

    /**
     * 在独占维护窗口创建缺失的固定空分片，并严格验证已有非空分片的格式。
     *
     * @param options Nacos Config endpoint、namespace、group 和可选 SDK 凭据
     * @throws IllegalStateException SDK 连接、权限、验证或创建失败时抛出
     */
    public static void initialize(RegistryOptions options) {
        Objects.requireNonNull(options, "options");
        List<String> endpoints = options.endpoints().isEmpty()
                ? List.of(NacosRegistryFactory.DEFAULT_ENDPOINT) : options.endpoints();
        for (String endpoint : endpoints) {
            if (endpoint.contains("@")) {
                throw new IllegalArgumentException("Nacos endpoint must not contain credentials");
            }
        }
        String namespace = options.namespace().isBlank()
                ? NacosRegistryFactory.DEFAULT_NAMESPACE : options.namespace();
        String group = options.providerOption("nacosGroup", NacosRegistryFactory.DEFAULT_GROUP);
        Properties properties = NacosRegistryFactory.clientProperties(options, endpoints, namespace);
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
                        existing = store.read(dataId);
                    }
                    if (existing.content() == null || existing.md5() == null) {
                        throw new IllegalStateException("Nacos Mapping bootstrap has no authoritative MD5");
                    }
                    NacosV2MappingCodec.decode(existing.content(), namespace, shard);
                }
            } finally {
                config.shutDown();
            }
        } catch (Exception error) {
            throw new IllegalStateException("Nacos Mapping bootstrap failed; keep application writes disabled", error);
        }
    }
}
