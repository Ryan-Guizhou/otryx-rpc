package com.peachsoft.otryx.registry;

import com.peachsoft.otryx.api.ApplicationKey;
import java.util.Objects;

/**
 * 服务映射的实例级所有权与防旧写入 fencing token。
 *
 * <p>generation 必须由 Repository 的原子 claim 颁发；不能由本地时钟或
 * 进程自行生成。新一代 claim 必须废止旧代发布和删除权限，并保留必要的
 * 已撤销代数状态，以拒绝延迟到达的旧请求。
 *
 * @param application 真实应用归属
 * @param instanceId 注册进程实例 ID
 * @param generation 仓库分配的正数单调 fencing generation
 *
 * @Author OpenAI
 * @Version 1.0.0-SNAPSHOT
 * @CreateTime 2026/10/15 18:30
 */
public record ServiceMappingOwner(
        ApplicationKey application,
        String instanceId,
        long generation) {

    private static final int MAX_INSTANCE_ID_LENGTH = 256;

    /** 防止非法 owner 与伪造的零代数操作进入后端。 */
    public ServiceMappingOwner {
        Objects.requireNonNull(application, "application");
        Objects.requireNonNull(instanceId, "instanceId");
        instanceId = instanceId.trim();
        if (instanceId.isEmpty() || instanceId.length() > MAX_INSTANCE_ID_LENGTH || generation <= 0) {
            throw new IllegalArgumentException("Invalid mapping owner identity or generation");
        }
        for (int index = 0; index < instanceId.length(); index++) {
            if (Character.isISOControl(instanceId.charAt(index))) {
                throw new IllegalArgumentException("Invalid mapping owner instanceId");
            }
        }
    }
}
