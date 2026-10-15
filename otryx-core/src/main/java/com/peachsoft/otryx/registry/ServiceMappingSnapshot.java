package com.peachsoft.otryx.registry;

import com.peachsoft.otryx.api.ApplicationKey;
import java.util.Objects;
import java.util.Set;

/**
 * 指向多个应用的接口映射不可变快照；空集合表示已确认当前无提供者应用。
 *
 * <p>修订号是同一 Repository 范围内的单调发布版本，不得伪称外部注册中心版本。
 *
 * @param key 带命名空间的业务接口映射键
 * @param applications 当前拥有该接口的应用集合
 * @param revision 仓库内单调修订号
 *
 * @Author OpenAI
 * @Version 1.0.0-SNAPSHOT
 * @CreateTime 2026/10/15 18:30
 */
public record ServiceMappingSnapshot(
        ServiceMappingKey key,
        Set<ApplicationKey> applications,
        long revision) {

    /** 单次响应的应用候选上限，避免元数据污染造成无限扇出。 */
    public static final int MAX_APPLICATIONS = 128;

    /** 对应用域、空值、集合与修订号进行强校验。 */
    public ServiceMappingSnapshot {
        Objects.requireNonNull(key, "key");
        applications = Set.copyOf(Objects.requireNonNull(applications, "applications"));
        if (applications.size() > MAX_APPLICATIONS || revision < 0) {
            throw new IllegalArgumentException("Invalid service mapping snapshot capacity or revision");
        }
        for (ApplicationKey application : applications) {
            if (!key.namespace().equals(application.namespace())) {
                throw new IllegalArgumentException("Service mapping crosses namespace boundary");
            }
        }
    }
}
