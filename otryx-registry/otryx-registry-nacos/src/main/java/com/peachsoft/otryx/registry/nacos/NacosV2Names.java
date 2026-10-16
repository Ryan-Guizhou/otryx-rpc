package com.peachsoft.otryx.registry.nacos;

import com.peachsoft.otryx.api.ApplicationKey;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/**
 * Nacos V2 应用级 Naming 与 Config 的独立键空间。
 *
 * <p>Config dataId 使用小写十六进制摘要，不直接把实例 ID、ServiceKey
 * 或 Manifest 字节写入 Nacos 的配置键，防止长度限制与路径冲突。
 *
 * @Author OpenAI
 * @Version 1.0.0-SNAPSHOT
 * @CreateTime 2026/10/16 13:42
 */
final class NacosV2Names {

    static final String SERVICE_PREFIX = "otryx.v2.application.";
    static final String MANIFEST_PREFIX = "otryx.v2.manifest.";
    static final String MAPPING_PREFIX = "otryx.v2.mapping.s";
    static final int MAPPING_SHARDS = 8;
    static final String METADATA_PREFIX = "otryx.v2.";
    static final int SHA_HEX_LENGTH = 64;
    private static final String CONSUMER_CLUSTER_MARKER = "-v2-";

    private NacosV2Names() {
    }

    /** 一个应用对应一个 Naming Service，与导出的接口数无关。 */
    static String serviceName(ApplicationKey application) {
        Objects.requireNonNull(application, "application");
        return SERVICE_PREFIX + application.applicationName();
    }

    /** 版本化 Manifest 使用应用摘要与内容摘要，满足 Config dataId 字符集。 */
    static String manifestId(ApplicationKey application, String revision) {
        Objects.requireNonNull(application, "application");
        com.peachsoft.otryx.api.ApplicationManifest.requireRevision(revision);
        return MANIFEST_PREFIX + sha256(application.applicationName()) + '.'
                + revision.substring(com.peachsoft.otryx.api.ApplicationManifest.REVISION_PREFIX.length());
    }

    /** Owner 应用及实例身份分片；单个实例永远只落在一份 CAS 文档。 */
    static int shard(ApplicationKey application, String instanceId) {
        Objects.requireNonNull(application, "application");
        Objects.requireNonNull(instanceId, "instanceId");
        byte[] digest = MessageDigestHolder.sha256(
                application.applicationName() + '\u0000' + instanceId);
        return Byte.toUnsignedInt(digest[0]) % MAPPING_SHARDS;
    }

    static String shardId(int shard) {
        if (shard < 0 || shard >= MAPPING_SHARDS) {
            throw new IllegalArgumentException("Invalid Nacos mapping shard");
        }
        return MAPPING_PREFIX + shard;
    }

    /** 每个成员在 Naming 中使用固定的、与本进程 token 绑定的独立物理 cluster。 */
    static String physicalCluster(String logicalCluster, String instanceId, String token) {
        return logicalCluster + CONSUMER_CLUSTER_MARKER
                + sha256(instanceId + '\u0000' + token).substring(0, 24);
    }

    static String sha256(String text) {
        return HexFormat.of().formatHex(MessageDigestHolder.sha256(text));
    }

    private static final class MessageDigestHolder {

        private MessageDigestHolder() {
        }

        private static byte[] sha256(String value) {
            try {
                return MessageDigest.getInstance("SHA-256")
                        .digest(value.getBytes(StandardCharsets.UTF_8));
            } catch (NoSuchAlgorithmException error) {
                throw new IllegalStateException("SHA-256 is unavailable", error);
            }
        }
    }
}
