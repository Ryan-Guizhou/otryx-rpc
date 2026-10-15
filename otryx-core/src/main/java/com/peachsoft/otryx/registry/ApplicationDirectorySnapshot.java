package com.peachsoft.otryx.registry;

import com.peachsoft.otryx.api.ApplicationInstance;
import com.peachsoft.otryx.api.ApplicationKey;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 按应用身份组织的不可变实例目录，包含 Provider 与 Consumer 应用成员。
 *
 * <p>目录是应用级控制面视图，不等于可调用端点列表；只有完成服务清单与
 * Schema 校验的 Provider 才能进入 Consumer 的实际负载均衡池。
 *
 * @param application 应用身份
 * @param instances 应用实例成员列表
 * @param revision 同一订阅内的单调版本
 *
 * @Author OTRYX
 * @Version 1.0.0
 * @CreateTime 2026/10/15 17:48
 */
public record ApplicationDirectorySnapshot(
        ApplicationKey application,
        List<ApplicationInstance> instances,
        long revision) {

    /** 固化目录并拒绝跨应用或实例 ID 冲突的快照。 */
    public ApplicationDirectorySnapshot {
        Objects.requireNonNull(application, "application");
        instances = List.copyOf(Objects.requireNonNull(instances, "instances"));
        if (revision < 0) {
            throw new IllegalArgumentException("Revision must not be negative");
        }
        Set<String> ids = new HashSet<>();
        for (ApplicationInstance instance : instances) {
            if (!application.equals(instance.application())) {
                throw new IllegalArgumentException("Application directory contains a foreign instance");
            }
            if (!ids.add(instance.instanceId())) {
                throw new IllegalArgumentException("Application directory contains duplicate instance ID");
            }
        }
    }
}
