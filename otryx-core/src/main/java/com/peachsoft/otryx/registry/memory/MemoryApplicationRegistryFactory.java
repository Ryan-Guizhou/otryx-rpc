package com.peachsoft.otryx.registry.memory;

import com.peachsoft.otryx.registry.ApplicationRegistry;
import com.peachsoft.otryx.registry.ApplicationRegistryFactory;
import com.peachsoft.otryx.registry.RegistryOptions;
import com.peachsoft.otryx.spi.Extension;
import java.util.Objects;

/**
 * 新应用级 Registry SPI 的内存实现工厂；不返回旧接口级 MemoryRegistry。
 *
 * <p>调用方应在同一 Application Runtime 内创建一次并共享实例，避免多次
 * create 得到独立的内存目录。Starter 的实例协调归 #137 实现。
 *
 * @Author OpenAI
 * @Version 1.0.0-SNAPSHOT
 * @CreateTime 2026/10/15 18:30
 */
@Extension(MemoryApplicationRegistryFactory.EXTENSION_NAME)
public final class MemoryApplicationRegistryFactory implements ApplicationRegistryFactory {

    static final String EXTENSION_NAME = "memory";

    /** 提供 SPI 加载器所需的无参构造。 */
    public MemoryApplicationRegistryFactory() {
    }

    /**
     * 创建新的独立应用目录。
     *
     * @param options 注册中心配置
     * @return 新的内存应用注册中心
     */
    @Override
    public ApplicationRegistry create(RegistryOptions options) {
        Objects.requireNonNull(options, "options");
        return new MemoryApplicationRegistry();
    }
}
