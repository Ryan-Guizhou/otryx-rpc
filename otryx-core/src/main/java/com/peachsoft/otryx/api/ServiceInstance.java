package com.peachsoft.otryx.api;

import java.util.Map;
import java.util.Objects;

/**
 * 可被 Consumer 路由的服务实例快照。
 *
 * @param instanceId 实例唯一标识
 * @param application 完整应用身份
 * @param serviceKey 服务唯一键
 * @param endpoint 服务地址
 * @param weight 静态路由权重，必须大于零
 * @param metadata 扩展元数据
 *
 * @Author Ryan
 * @Version 1.0.0
 * @CreateTime 2026/9/23 10:51
 */
public record ServiceInstance(
        String instanceId,
        ApplicationKey application,
        ServiceKey serviceKey,
        RpcEndpoint endpoint,
        int weight,
        Map<String, String> metadata) {

    /** 校验并规范化服务实例。 */
    public ServiceInstance {
        Objects.requireNonNull(instanceId, "instanceId");
        Objects.requireNonNull(application, "application");
        Objects.requireNonNull(serviceKey, "serviceKey");
        Objects.requireNonNull(endpoint, "endpoint");
        if (weight <= 0) {
            throw new IllegalArgumentException("weight must be positive");
        }
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    /**
     * 返回与此快照中的应用身份成对发布的连接目标。
     *
     * @return 非 null 应用与端点组合
     */
    public RpcTarget target() {
        return new RpcTarget(application, endpoint);
    }
}
