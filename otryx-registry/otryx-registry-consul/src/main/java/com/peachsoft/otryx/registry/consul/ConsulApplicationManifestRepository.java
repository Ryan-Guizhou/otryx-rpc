package com.peachsoft.otryx.registry.consul;

import com.peachsoft.otryx.api.ApplicationKey;
import com.peachsoft.otryx.api.ApplicationManifest;
import com.peachsoft.otryx.api.ApplicationManifestCodec;
import com.peachsoft.otryx.registry.ApplicationManifestRepository;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletionStage;

/**
 * Consul KV 上的不可变、内容寻址应用 Manifest 仓库。
 *
 * <p>所有 Manifest 只通过 cas=0 创建；已发布的 Revision 不可覆盖。
 * 由于远端安全 GC 依赖仍在运行的旧 Revision，当前版本保留历史 Revision。
 *
 * @Author OpenAI
 * @Version 1.0.0-SNAPSHOT
 * @CreateTime 2026/10/16 15:00
 */
public final class ConsulApplicationManifestRepository implements ApplicationManifestRepository {

    private final ConsulV2Client client;
    private final ConsulV2Executor executor;
    private final ConsulV2Identity identity;

    ConsulApplicationManifestRepository(
            ConsulV2Client client, ConsulV2Executor executor, ConsulV2Identity identity) {
        this.client = Objects.requireNonNull(client, "client");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.identity = Objects.requireNonNull(identity, "identity");
    }

    /** 按规范二进制 Revision 幂等创建，绝不以普通 PUT 覆盖既有数据。 */
    @Override
    public CompletionStage<Void> publish(ApplicationManifest manifest) {
        Objects.requireNonNull(manifest, "manifest");
        identity.requireNamespace(manifest.application());
        byte[] value = ApplicationManifestCodec.encode(manifest);
        if (value.length > ConsulV2Client.MAX_KV_VALUE_BYTES) {
            throw new IllegalArgumentException("Consul Manifest exceeds KV safe value budget");
        }
        return executor.submit("manifest-publish", () -> {
            String key = identity.manifestKey(manifest.application(), manifest.revision());
            Optional<ConsulV2Client.KvEntry> current = client.get(key);
            if (current.isEmpty()) {
                client.cas(key, 0, value);
            }
            ConsulV2Client.KvEntry stored = client.get(key).orElseThrow(
                    () -> new IllegalStateException("Consul Manifest was not persisted"));
            if (!Arrays.equals(value, stored.value())) {
                throw new IllegalStateException("Consul immutable Manifest revision collision");
            }
            return null;
        });
    }

    /** 明确区分不存在与 ACL/网络失败，并验证 Revision、归属及字节完整性。 */
    @Override
    public CompletionStage<Optional<ApplicationManifest>> lookup(ApplicationKey application, String revision) {
        Objects.requireNonNull(application, "application");
        ApplicationManifest.requireRevision(revision);
        identity.requireNamespace(application);
        return executor.submit("manifest-lookup", () -> {
            Optional<ConsulV2Client.KvEntry> stored = client.get(identity.manifestKey(application, revision));
            if (stored.isEmpty()) {
                return Optional.empty();
            }
            ApplicationManifest manifest = ApplicationManifestCodec.decode(stored.orElseThrow().value());
            return Optional.of(manifest.requireReference(application, revision));
        });
    }
}
