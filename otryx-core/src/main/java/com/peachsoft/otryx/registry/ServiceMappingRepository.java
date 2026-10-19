package com.peachsoft.otryx.registry;

import com.peachsoft.otryx.api.ApplicationKey;
import com.peachsoft.otryx.api.ServiceKey;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletionStage;

/**
 * 应用级服务映射仓库 SPI：ServiceKey + Namespace 指向一组 ApplicationKey。
 *
 * <p>发布单位是实际 Provider 实例持有的 owner，而不是 ServiceKey 对应的虚拟
 * 注册节点。多个实例、多个 Revision 可以共享同一个应用映射；revoke 只能撤销
 * 匹配 generation 的那一份所有权，不能删除其他实例或应用的发布。Repository
 * 必须原子执行 claim/publish/revoke，并按实例 ID 保存 fencing generation。
 *
 * <p>所有方法的远端实现必须异步且容量受限；同步异常和异步异常均不能
 * 被视为合法空快照。Caller 有责任施加 Deadline、取消和生命周期治理。
 * 元数据访问凭据与命名空间的授权必须在真实存储 Adapter 中验证。
 *
 * @Author OpenAI
 * @Version 1.0.0-SNAPSHOT
 * @CreateTime 2026/10/15 18:30
 */
public interface ServiceMappingRepository extends RegistrySecuritySource {

    /**
     * 为真实应用实例原子申请所有权，废止同身份的前一个 generation。
     *
     * <p>不能复用已颁发过的 generation；同实例重启也必须递增。
     *
     * @param application Provider 应用身份
     * @param instanceId 实际实例 ID
     * @return 新一代所有权
     */
    CompletionStage<ServiceMappingOwner> claim(ApplicationKey application, String instanceId);

    /**
     * 原子替换该实例发布的完整服务键集合，重复相同写入必须幂等。
     *
     * <p>空集合意味着该实例不再提供服务；Consumer-only 无需 claim。
     * 仅当前 generation 可以发布，旧 generation 必须失败。
     *
     * @param owner 仓库颁发的当前所有权
     * @param services 已绑定导出的 ServiceKey 集合
     * @return 数据持久化完成信号
     */
    CompletionStage<Void> publish(ServiceMappingOwner owner, Set<ServiceKey> services);

    /**
     * 撤销完全匹配的实例发布；重复撤销不可误删新 generation。
     *
     * @param owner 曾经颁发的所有权
     * @return 操作完成信号
     */
    CompletionStage<Void> revoke(ServiceMappingOwner owner);

    /**
     * 按业务接口契约查询完整应用映射，而不是查询 RPC 实例。
     *
     * <p>已确认不存在时返回空集合的有效快照；连接失败、ACL 拒绝等必须
     * 异常完成，不能返回空集合掩盖真实故障。
     *
     * @param key 命名空间和业务接口键
     * @return 当前有效映射与版本
     */
    CompletionStage<ServiceMappingSnapshot> lookup(ServiceMappingKey key);

    /**
     * 校验远端存储返回的映射归属，杜绝错位缓存和跨命名空间注入。
     *
     * @param key 请求的映射键
     * @return 完整且已验证的映射快照
     */
    default CompletionStage<ServiceMappingSnapshot> lookupVerified(ServiceMappingKey key) {
        Objects.requireNonNull(key, "key");
        return Objects.requireNonNull(lookup(key), "lookup result").thenApply(snapshot -> {
            if (snapshot == null) {
                throw new RegistrySecurityException(RegistrySecurityFailure.METADATA_INTEGRITY_REJECTED);
            }
            if (!key.equals(snapshot.key())) {
                throw new RegistrySecurityException(RegistrySecurityFailure.METADATA_REFERENCE_MISMATCH);
            }
            return snapshot;
        });
    }
}
