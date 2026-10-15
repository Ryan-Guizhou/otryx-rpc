package com.peachsoft.otryx.registry;

import com.peachsoft.otryx.api.ApplicationInstance;
import java.util.concurrent.CompletionStage;

/**
 * 应用实例的注册与注销 SPI，Provider 和 Consumer 使用同一入口。
 *
 * <p>相同 ApplicationKey 与 instanceId 的重复注册必须是幂等的更新操作，
 * 注销只能删除自己的实例，不能删除该应用的其他实例或全局服务映射。
 *
 * @Author OTRYX
 * @Version 1.0.0
 * @CreateTime 2026/10/15 17:48
 */
public interface ApplicationRegistrar {

    /**
     * 发布或更新一个应用成员。
     *
     * @param instance 已完成角色/端点验证的应用实例
     * @return 后端确认注册完成的信号
     */
    CompletionStage<Void> register(ApplicationInstance instance);

    /**
     * 注销一个应用成员。
     *
     * @param instance 待注销实例
     * @return 后端确认注销完成的信号
     */
    CompletionStage<Void> unregister(ApplicationInstance instance);
}
