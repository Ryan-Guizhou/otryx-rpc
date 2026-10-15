package com.peachsoft.otryx.registry;

import java.util.Optional;

/**
 * 应用级注册中心的统一生命周期和能力入口。
 *
 * <p>纯发现适配器允许不支持主动注册；Provider 与 Consumer
 * 使用支持 REGISTRATION 的适配器时可获得应用成员发布入口。
 *
 * @Author OTRYX
 * @Version 1.0.0
 * @CreateTime 2026/10/15 17:48
 */
public interface ApplicationRegistry extends ApplicationDiscovery, AutoCloseable {

    /**
     * 返回注册中心声明的能力。
     *
     * @return Adapter 支持的能力集合
     */
    RegistryCapabilities capabilities();

    /**
     * 获取可选的应用实例主动注册入口。
     *
     * @return 注册能力存在时返回 ApplicationRegistrar
     */
    default Optional<ApplicationRegistrar> registrar() {
        boolean declared = capabilities().supports(RegistryCapability.REGISTRATION);
        boolean implemented = this instanceof ApplicationRegistrar;
        if (declared != implemented) {
            throw new IllegalStateException(
                    "Registry REGISTRATION capability does not match ApplicationRegistrar implementation: "
                            + getClass().getName());
        }
        if (!implemented) {
            return Optional.empty();
        }
        return Optional.of((ApplicationRegistrar) this);
    }

    /** 释放订阅、网络客户端与后台资源。 */
    @Override
    void close();
}
