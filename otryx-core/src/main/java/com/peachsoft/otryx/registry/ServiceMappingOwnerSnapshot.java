package com.peachsoft.otryx.registry;

import com.peachsoft.otryx.api.ApplicationKey;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 外置元数据仓库所存服务映射 Owner 的未验证候选快照。
 *
 * <p>必须保留真实发布 Owner 的 instanceId，禁止仅返回 ApplicationKey 后
 * 使用其他健康实例替无服务发布的 Owner 进入路由池。消费方必须依据权威
 * Member Registry 的 Provider/BOTH 身份与本 Owner 实例逐一交叉校验。
 * 数据只表示候选，不是已验明的可调用端点。
 *
 * @param key 所查询的命名空间和服务
 * @param ownerInstances 声明该接口的应用与具体实例集合
 * @param revision 元数据后端的版本，非 Eureka 全局目录版本
 *
 * @Author OpenAI
 * @Version 1.0.0-SNAPSHOT
 * @CreateTime 2026/10/16 18:30
 */
public record ServiceMappingOwnerSnapshot(
        ServiceMappingKey key,
        Map<ApplicationKey, Set<String>> ownerInstances,
        long revision) {

    /** 延用已有 Mapping 应用扇出上限，避免跨后端扫描放大。 */
    public static final int MAX_APPLICATIONS = ServiceMappingSnapshot.MAX_APPLICATIONS;

    /** 一个候选应用的最大可声明 Owner 数量。 */
    public static final int MAX_INSTANCES_PER_APPLICATION = 512;

    /** 深度不可变复制并验证命名空间、Owner 身份和容量。 */
    public ServiceMappingOwnerSnapshot {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(ownerInstances, "ownerInstances");
        if (revision < 0 || ownerInstances.size() > MAX_APPLICATIONS) {
            throw new IllegalArgumentException("Invalid Mapping candidates version or fanout");
        }
        Map<ApplicationKey, Set<String>> safe = new HashMap<>();
        for (Map.Entry<ApplicationKey, Set<String>> entry : ownerInstances.entrySet()) {
            ApplicationKey application = Objects.requireNonNull(entry.getKey(), "application");
            if (!key.namespace().equals(application.namespace())) {
                throw new IllegalArgumentException("Mapping candidate crosses namespace");
            }
            Set<String> instances = Objects.requireNonNull(entry.getValue(), "owner instances");
            if (instances.isEmpty() || instances.size() > MAX_INSTANCES_PER_APPLICATION) {
                throw new IllegalArgumentException("Invalid Mapping candidate owner count");
            }
            Set<String> copied = new HashSet<>();
            for (String instance : instances) {
                // Delegate the canonical per-instance validation to the Owner contract.
                ServiceMappingOwner validated = new ServiceMappingOwner(application, instance, 1L);
                copied.add(validated.instanceId());
            }
            if (copied.size() != instances.size()) {
                throw new IllegalArgumentException("Duplicate Mapping owner instance identity");
            }
            safe.put(application, Set.copyOf(copied));
        }
        ownerInstances = Map.copyOf(safe);
    }
}
