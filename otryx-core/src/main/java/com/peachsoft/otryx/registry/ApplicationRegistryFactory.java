package com.peachsoft.otryx.registry;

import com.peachsoft.otryx.spi.SPI;

/**
 * 应用级 Registry 的可替换创建入口，不得退回旧的 ServiceKey 注册 SPI。
 *
 * <p>每次 create 返回由调用方拥有和关闭的 Registry 生命周期实例。
 * 同一 Application Runtime 中 Provider 与 Consumer 必须共享一个已创建实例。
 *
 * @Author OpenAI
 * @Version 1.0.0-SNAPSHOT
 * @CreateTime 2026/10/15 18:30
 */
@SPI("memory")
public interface ApplicationRegistryFactory {

    /**
     * 根据控制面配置创建应用注册中心。
     *
     * @param options 公共 Registry 配置
     * @return 生命周期由调用方拥有的 ApplicationRegistry
     */
    ApplicationRegistry create(RegistryOptions options);
}
