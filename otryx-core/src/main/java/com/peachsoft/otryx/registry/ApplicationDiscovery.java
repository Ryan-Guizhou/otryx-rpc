package com.peachsoft.otryx.registry;

import com.peachsoft.otryx.api.ApplicationKey;
import java.util.concurrent.CompletionStage;

/**
 * 按应用身份查询和订阅实例成员的注册中心 SPI。
 *
 * <p>应用成员可以是 Provider、Consumer 或 BOTH；调用方必须额外校验
 * Provider 角色、实际 RPC Endpoint 及服务 Manifest，不能把目录直接作为可调用端点池。
 *
 * @Author OTRYX
 * @Version 1.0.0
 * @CreateTime 2026/10/15 17:48
 */
public interface ApplicationDiscovery extends RegistrySecuritySource {

    /**
     * 查询指定应用的成员快照。
     *
     * @param application 应用身份
     * @return 带单调修订号的完整应用目录
     */
    CompletionStage<ApplicationDirectorySnapshot> lookup(ApplicationKey application);

    /**
     * 订阅指定应用的成员变动。
     *
     * @param application 应用身份
     * @param listener 目录和健康事件监听器
     * @return 必须由调用者关闭的订阅句柄
     */
    RegistrySubscription subscribe(ApplicationKey application, ApplicationRegistryListener listener);
}
