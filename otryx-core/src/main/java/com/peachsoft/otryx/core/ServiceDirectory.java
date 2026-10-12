package com.peachsoft.otryx.core;

import com.peachsoft.otryx.api.RpcCompatibilityMetadata;
import com.peachsoft.otryx.api.ServiceInstance;
import com.peachsoft.otryx.api.ServiceKey;
import com.peachsoft.otryx.registry.ServiceDiscovery;
import com.peachsoft.otryx.registry.RegistrySnapshot;
import com.peachsoft.otryx.registry.RegistrySubscription;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Consumer 本地服务目录，热路径只读取不可变数组快照。
 *
 * @Author Ryan
 * @Version 1.0.0
 * @CreateTime 2026/9/23 10:51
 */
final class ServiceDirectory implements AutoCloseable {

    private final AtomicReference<DirectorySnapshot> snapshot =
            new AtomicReference<>(new DirectorySnapshot(
                    new ServiceInstance[0],
                    0));
    private final String expectedSchemaFingerprint;
    private final RegistrySubscription subscription;

    ServiceDirectory(
            ServiceDiscovery discovery,
            ServiceKey key) {
        this(discovery, key, null);
    }

    ServiceDirectory(
            ServiceDiscovery discovery,
            ServiceKey key,
            String expectedSchemaFingerprint) {
        this.expectedSchemaFingerprint =
                expectedSchemaFingerprint;
        subscription = discovery.subscribe(
                key,
                this::publish);
    }

    private void publish(RegistrySnapshot candidate) {
        snapshot.updateAndGet(current -> {
            if (candidate.revision() < current.revision()) {
                return current;
            }
            ServiceInstance[] compatible =
                    candidate.instances().stream()
                            .filter(this::compatible)
                            .toArray(ServiceInstance[]::new);
            return new DirectorySnapshot(
                    compatible,
                    candidate.revision());
        });
    }

    private boolean compatible(ServiceInstance instance) {
        if (expectedSchemaFingerprint == null) {
            return true;
        }
        return RpcCompatibilityMetadata.compatibility(
                instance,
                expectedSchemaFingerprint)
                == RpcCompatibilityMetadata.Compatibility.COMPATIBLE;
    }

    ServiceInstance[] snapshot() {
        return snapshot.get().instances();
    }

    @Override
    public void close() {
        subscription.close();
    }

    private record DirectorySnapshot(
            ServiceInstance[] instances,
            long revision) {
    }
}
