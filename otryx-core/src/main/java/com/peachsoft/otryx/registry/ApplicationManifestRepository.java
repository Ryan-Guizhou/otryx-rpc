package com.peachsoft.otryx.registry;

import com.peachsoft.otryx.api.ApplicationKey;
import com.peachsoft.otryx.api.ApplicationManifest;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletionStage;

/**
 * 按应用身份及内容修订号保存不可变 Manifest 的元数据仓库 SPI。
 *
 * <p>不同 Registry 可复用各自 KV/Config 存储，但不能假设 Eureka 自带通用 KV。
 * publish 必须按 (ApplicationKey,revision) 幂等创建且不允许覆盖不同内容；
 * lookup 缺失时返回 Optional.empty，不能回退旧接口级 Registry。实际远端适配
 * 应异步执行，不得阻塞事件循环，调用方对网络调用承担超时和取消预算。
 *
 * <p>当前 SPI 故意不提供 delete：在运行实例、灰度回滚及映射引用未同步前，
 * 自动删除旧 revision 存在并发数据丢失风险。具体存储端在满足权威引用、
 * 隔离期及具 fencing 的原子复核前必须保留旧 Revision。
 *
 * @Author OpenAI
 * @Version 1.0.0-SNAPSHOT
 * @CreateTime 2026/10/15 17:48
 */
public interface ApplicationManifestRepository extends RegistrySecuritySource {

    /**
     * 幂等地发布 Manifest。相同应用与修订版必须对应完全相同内容。
     *
     * @param manifest 已完成本地校验的不可变应用服务清单
     * @return 后端确认内容持久化的异步信号
     */
    CompletionStage<Void> publish(ApplicationManifest manifest);

    /**
     * 根据应用身份与修订号读取不可变 Manifest。
     *
     * <p>任何网络或数据错误必须异常完成，不能伪装成 Optional.empty。
     *
     * @param application 需要查询的应用
     * @param revision 完整内容寻址修订号
     * @return 内容不存在时为 Optional.empty，否则为 Manifest
     */
    CompletionStage<Optional<ApplicationManifest>> lookup(ApplicationKey application, String revision);

    /**
     * 验证仓库返回的实例是否和指定 Application 及 Revision 严格一致。
     *
     * <p>Adapter 实现的 lookup 可能返回错误归属数据，调用方必须使用本入口
     * 或执行等价验证。即使读取到数据，也不得将其直接当作可信授权信息。
     *
     * @param application 目标应用
     * @param revision 目标 revision
     * @return 已验证的结果；不存在时仍返回 Optional.empty
     */
    default CompletionStage<Optional<ApplicationManifest>> lookupVerified(
            ApplicationKey application, String revision) {
        Objects.requireNonNull(application, "application");
        ApplicationManifest.requireRevision(revision);
        CompletionStage<Optional<ApplicationManifest>> stage =
                Objects.requireNonNull(lookup(application, revision), "lookup result");
        return stage.thenApply(result -> {
            if (result == null) {
                throw new RegistrySecurityException(RegistrySecurityFailure.METADATA_INTEGRITY_REJECTED);
            }
            result.ifPresent(manifest -> {
                try {
                    manifest.requireReference(application, revision);
                } catch (IllegalArgumentException rejected) {
                    throw new RegistrySecurityException(RegistrySecurityFailure.METADATA_REFERENCE_MISMATCH);
                }
            });
            return result;
        });
    }
}
