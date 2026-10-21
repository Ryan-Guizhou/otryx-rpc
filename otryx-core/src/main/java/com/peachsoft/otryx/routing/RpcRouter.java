package com.peachsoft.otryx.routing;

import com.peachsoft.otryx.api.ApplicationKey;
import com.peachsoft.otryx.api.ServiceInstance;
import com.peachsoft.otryx.api.ServiceKey;
import com.peachsoft.otryx.spi.SPI;
import java.util.Map;

/**
 * Consumer 在已验证 Provider 候选集与负载均衡之间运行的路由扩展点。
 *
 * <p>不得创建、替换或修改候选实例，不得进行远端配置查询或阻塞 EventLoop。
 * 调用端复核输出中的每个实例必须与原始可信数组中的对象完全相同；空数组表示路由无可用目标。
 * Metadata 是非可信的业务上下文，不能替代 Peer 身份或权限验证。
 *
 * @Author OpenAI
 * @Version 1.0.0-SNAPSHOT
 * @CreateTime 2026/10/21 13:38
 */
@SPI("rules")
@FunctionalInterface
public interface RpcRouter {

    /**
     * 根据当前本地规则选择可进入负载均衡的可信实例子集。
     *
     * @param service 已通过 Schema 检查的业务服务
     * @param caller 实际注册的本地调用方应用身份
     * @param metadata 非可信调用 Metadata，不能作为认证来源
     * @param candidates 只包含已验证 Provider/BOTH 的候选数组；不允许修改
     * @return 原候选对象组成的非 null 数组，可为空，不得增加或替换实例
     */
    ServiceInstance[] route(ServiceKey service, ApplicationKey caller,
                            Map<String, String> metadata, ServiceInstance[] candidates);

    /**
     * 表示当前服务是否需要执行路由器；默认保守执行校验。
     *
     * @param service 业务服务
     * @return true 表示需要进行子集筛选，false 表示直接使用原始可信数组
     */
    default boolean hasRules(ServiceKey service) {
        return true;
    }
}
