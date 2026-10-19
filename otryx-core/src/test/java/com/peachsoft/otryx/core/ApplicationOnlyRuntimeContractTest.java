package com.peachsoft.otryx.core;

import com.peachsoft.otryx.registry.ApplicationServiceResolver;
import com.peachsoft.otryx.registry.ManifestSnapshotCache;
import com.peachsoft.otryx.registry.ServiceMappingCache;
import com.peachsoft.otryx.registry.memory.MemoryApplicationRegistry;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 验证唯一应用控制面公开入口与目录失联预算的强制边界。
 *
 * @Author OpenAI
 * @Version 1.0.0-SNAPSHOT
 * @CreateTime 2026/10/19 15:03
 */
class ApplicationOnlyRuntimeContractTest {

    @Test
    void consumerBuilderMustNotExposeInterfaceDiscovery() {
        assertFalse(Arrays.stream(OtryxRpcClient.Builder.class.getMethods())
                .map(Method::getName).anyMatch("serviceDiscovery"::equals));
    }

    @Test
    void providerBuilderMustNotExposeInterfaceRegistrar() {
        assertFalse(Arrays.stream(OtryxRpcServer.Builder.class.getMethods())
                .map(Method::getName).anyMatch("serviceRegistrar"::equals));
    }

    @Test
    void interfaceRegistryTypesMustNotRemainLoadable() {
        for (String name : new String[] {"Registry", "ServiceRegistrar", "ServiceDiscovery",
                "RegistryFactory", "RegistryListener", "RegistrySnapshot"}) {
            assertThrows(ClassNotFoundException.class,
                    () -> Class.forName("com.peachsoft.otryx.registry." + name), name);
        }
    }

    @Test
    void interfaceFactoryDescriptorMustNotRemainDiscoverable() throws Exception {
        assertFalse(getClass().getClassLoader().getResources(
                "META-INF/services/com.peachsoft.otryx.registry.RegistryFactory").hasMoreElements());
    }

    @Test
    void freshnessMustRejectZeroAndOverflow() {
        assertThrows(IllegalArgumentException.class,
                () -> new RpcDirectoryFreshnessOptions(Duration.ZERO));
        assertThrows(IllegalArgumentException.class,
                () -> new RpcDirectoryFreshnessOptions(Duration.ofSeconds(Long.MAX_VALUE)));
    }

    @Test
    void resolverMustRejectZeroBudget() {
        try (MemoryApplicationRegistry registry = new MemoryApplicationRegistry();
                ServiceMappingCache mapping = new ServiceMappingCache(
                        registry.serviceMappingRepository(), Duration.ofSeconds(30), Duration.ZERO);
                ManifestSnapshotCache manifests = new ManifestSnapshotCache(registry.manifestRepository())) {
            assertThrows(IllegalArgumentException.class,
                    () -> new ApplicationServiceResolver(mapping, registry, manifests, Duration.ZERO));
        }
    }
}
