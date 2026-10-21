package com.peachsoft.otryx.registry.etcd;

import com.peachsoft.otryx.api.ApplicationKey;
import com.peachsoft.otryx.api.ApplicationManifest;
import com.peachsoft.otryx.api.ApplicationManifestCodec;
import com.peachsoft.otryx.registry.ApplicationManifestRepository;
import com.peachsoft.otryx.registry.RegistrySecurityDescriptor;
import com.peachsoft.otryx.registry.RegistrySecurityException;
import com.peachsoft.otryx.registry.RegistrySecurityFailure;
import io.etcd.jetcd.ByteSequence;
import io.etcd.jetcd.Client;
import io.etcd.jetcd.op.Cmp;
import io.etcd.jetcd.op.CmpTarget;
import io.etcd.jetcd.op.Op;
import io.etcd.jetcd.options.PutOption;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * Etcd 内容寻址的不可变 Manifest 仓库，使用 Version=0 事务保证只创建一次。
 *
 * <p>任何已存在的同修订号不同内容均被视为存储冲突；不自动删除其他发布版本。
 * Repository 由共享的 EtcdApplicationRegistry 生命周期拥有。
 *
 * @Author OpenAI
 * @Version 1.0.0-SNAPSHOT
 * @CreateTime 2026/10/16 10:33
 */
public final class EtcdApplicationManifestRepository implements ApplicationManifestRepository {

    private final RegistrySecurityDescriptor descriptor;
    private final Client client;
    private final EtcdV2Paths paths;

    EtcdApplicationManifestRepository(Client client, EtcdV2Paths paths) {
        this(client, paths, RegistrySecurityDescriptor.unknown());
    }

    EtcdApplicationManifestRepository(Client client, EtcdV2Paths paths, RegistrySecurityDescriptor descriptor) {
        this.descriptor = Objects.requireNonNull(descriptor, "descriptor");
        this.client = Objects.requireNonNull(client, "client");
        this.paths = Objects.requireNonNull(paths, "paths");
    }

    /** 返回当前实际 Etcd 客户端的通道来源。 */
    @Override
    public RegistrySecurityDescriptor securityDescriptor() {
        return descriptor;
    }

    /** 发布以应用身份和完整 SHA256 修订号定位的不可变清单。 */
    @Override
    public CompletionStage<Void> publish(ApplicationManifest manifest) {
        Objects.requireNonNull(manifest, "manifest");
        paths.requireNamespace(manifest.application());
        String path = paths.manifestKey(manifest.application(), manifest.revision());
        ByteSequence key = bytes(path);
        byte[] canonical = ApplicationManifestCodec.encode(manifest);
        Cmp absent = new Cmp(key, Cmp.Op.EQUAL, CmpTarget.version(0));
        return EtcdFailures.safe(client.getKVClient().txn()
                .If(absent)
                .Then(Op.put(key, ByteSequence.from(canonical), PutOption.DEFAULT))
                .commit()
                .thenCompose(response -> {
                    if (response.isSucceeded()) {
                        return CompletableFuture.completedFuture(null);
                    }
                    return client.getKVClient().get(key).thenApply(existing -> {
                        if (existing.getKvs().isEmpty()) {
                            throw new IllegalStateException("Manifest changed during immutable publish");
                        }
                        byte[] recorded = existing.getKvs().getFirst().getValue().getBytes();
                        if (!Arrays.equals(recorded, canonical)) {
                            throw new RegistrySecurityException(RegistrySecurityFailure.METADATA_INTEGRITY_REJECTED);
                        }
                        return null;
                    });
                }));
    }

    /** 按 Revision 校验完整 Manifest 字节，错误和未命中有严格不同的终态。 */
    @Override
    public CompletionStage<Optional<ApplicationManifest>> lookup(
            ApplicationKey application, String revision) {
        Objects.requireNonNull(application, "application");
        ApplicationManifest.requireRevision(revision);
        paths.requireNamespace(application);
        ByteSequence requested = bytes(paths.manifestKey(application, revision));
        return EtcdFailures.safe(client.getKVClient().get(requested).thenApply(response -> {
            if (response.getKvs().isEmpty()) {
                return Optional.empty();
            }
            if (response.getKvs().size() != 1 || response.getCount() != 1
                    || !response.getKvs().getFirst().getKey().equals(requested)) {
                throw new RegistrySecurityException(RegistrySecurityFailure.METADATA_REFERENCE_MISMATCH);
            }
            byte[] payload = response.getKvs().getFirst().getValue().getBytes();
            if (payload.length > ApplicationManifest.MAX_CANONICAL_BYTES) {
                throw new RegistrySecurityException(RegistrySecurityFailure.RESPONSE_LIMIT_EXCEEDED);
            }
            ApplicationManifest manifest = ApplicationManifestCodec.decode(payload);
            try {
                return Optional.of(manifest.requireReference(application, revision));
            } catch (IllegalArgumentException invalidReference) {
                throw new RegistrySecurityException(RegistrySecurityFailure.METADATA_REFERENCE_MISMATCH);
            }
        }));
    }

    private static ByteSequence bytes(String key) {
        return ByteSequence.from(key, StandardCharsets.UTF_8);
    }
}
