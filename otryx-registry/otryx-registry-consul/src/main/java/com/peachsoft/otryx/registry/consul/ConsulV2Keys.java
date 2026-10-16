package com.peachsoft.otryx.registry.consul;

import com.peachsoft.otryx.api.ApplicationKey;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/**
 * Consul V2 应用 Service、TTL Check 与 KV Key 的命名隔离。
 *
 * <p>所有服务和 KV 资源都独立于 ServiceKey；对远端键使用规范摘要，
 * 不把未受信的用户标识拼接进 URL，也不泄露含认证信息的查询参数。
 *
 * @Author OpenAI
 * @Version 1.0.0-SNAPSHOT
 * @CreateTime 2026/10/16 14:12
 */
final class ConsulV2Keys {

    static final String SERVICE_PREFIX = "otryx-v2-";
    static final String KV_PREFIX = "otryx/v2/";
    static final String OWNER_BRANCH = "owners/s";
    static final String LIVE_BRANCH = "live/s";
    static final String MANIFEST_BRANCH = "manifests/";
    static final String METADATA_PREFIX = "otryx_v2_";
    static final int OWNER_SHARDS = 8;
    static final int MAX_OWNERS_PER_SHARD = 64;
    static final int MAX_OWNERS = OWNER_SHARDS * MAX_OWNERS_PER_SHARD;

    private ConsulV2Keys() {
    }

    /** 为整个应用而非业务接口生成唯一 Consul 服务名。 */
    static String serviceName(ApplicationKey app) {
        Objects.requireNonNull(app, "application");
        return SERVICE_PREFIX + sha256(app.namespace() + '\u0000' + app.applicationName()).substring(0, 32);
    }

    /** 使用进程随机 incarnation 隔离不同世代的 Agent Service ID。 */
    static String serviceId(ApplicationKey app, String instanceId, String incarnation) {
        return SERVICE_PREFIX + sha256(app.namespace() + '\u0000'
                + app.applicationName() + '\u0000' + instanceId + '\u0000' + incarnation);
    }

    /** KV 逻辑 Namespace 与 Consul Enterprise Namespace 相互独立。 */
    static String root(String namespace) {
        return KV_PREFIX + sha256(Objects.requireNonNull(namespace, "namespace")).substring(0, 32) + '/';
    }

    static String manifest(String namespace, String appName, String revision) {
        com.peachsoft.otryx.api.ApplicationManifest.requireRevision(revision);
        return root(namespace) + MANIFEST_BRANCH + sha256(appName) + '/'
                + revision.substring(com.peachsoft.otryx.api.ApplicationManifest.REVISION_PREFIX.length());
    }

    static String ownerId(ApplicationKey app, String instanceId) {
        return sha256(app.namespace() + '\u0000' + app.applicationName() + '\u0000' + instanceId);
    }

    static int shard(ApplicationKey app, String instanceId) {
        return Integer.parseInt(ownerId(app, instanceId).substring(0, 2), 16) % OWNER_SHARDS;
    }

    static String ownerPrefix(String namespace, int shard) {
        return root(namespace) + OWNER_BRANCH + validateShard(shard) + '/';
    }

    static String livePrefix(String namespace, int shard) {
        return root(namespace) + LIVE_BRANCH + validateShard(shard) + '/';
    }

    static String owner(ApplicationKey app, String instanceId) {
        return ownerPrefix(app.namespace(), shard(app, instanceId)) + ownerId(app, instanceId);
    }

    static String live(ApplicationKey app, String instanceId, long generation) {
        return livePrefix(app.namespace(), shard(app, instanceId))
                + ownerId(app, instanceId) + '/' + generation;
    }

    static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    static String sha256(String text) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is unavailable", error);
        }
    }

    private static int validateShard(int shard) {
        if (shard < 0 || shard >= OWNER_SHARDS) {
            throw new IllegalArgumentException("Invalid Consul mapping shard");
        }
        return shard;
    }
}
