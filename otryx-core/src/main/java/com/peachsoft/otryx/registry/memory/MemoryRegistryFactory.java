package com.peachsoft.otryx.registry.memory;

import com.peachsoft.otryx.registry.Registry;
import com.peachsoft.otryx.registry.RegistryFactory;
import com.peachsoft.otryx.registry.RegistryOptions;
import com.peachsoft.otryx.spi.Extension;

/**
 * 内存注册中心工厂，适合单 JVM 测试和本地开发。
 *
 * @Author Ryan
 * @Version 1.0.0
 * @CreateTime 2026/9/23 10:51
 */
@Extension(MemoryRegistryFactory.EXTENSION_NAME)
public final class MemoryRegistryFactory implements RegistryFactory {

    static final String EXTENSION_NAME = "memory";

    private static final Registry SHARED = new MemoryRegistry();

    /** 创建内存注册中心工厂。 */
    public MemoryRegistryFactory() {
    }

    @Override
    public Registry create(RegistryOptions options) {
        return SHARED;
    }
}
