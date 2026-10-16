package com.peachsoft.otryx.registry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.peachsoft.otryx.api.ApplicationKey;
import com.peachsoft.otryx.api.ServiceKey;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * 外置元数据 Owner 候选快照必须保留实例身份、深度只读及 Namespace 隔离。
 *
 * @Author OpenAI
 * @Version 1.0.0-SNAPSHOT
 * @CreateTime 2026/10/16 18:30
 */
class ServiceMappingOwnerSnapshotTest {

    private static final ApplicationKey APPLICATION = new ApplicationKey("production", "orders");
    private static final ServiceMappingKey KEY = new ServiceMappingKey("production",
            new ServiceKey("demo.Payments", "1.0.0", "default"));

    @Test
    void copiesNestedOwnerSetsAndRequiresExactInstanceIdentity() {
        Set<String> members = new HashSet<>(Set.of("owner-1", "owner-2"));
        Map<ApplicationKey, Set<String>> claims = new HashMap<>();
        claims.put(APPLICATION, members);
        ServiceMappingOwnerSnapshot snapshot = new ServiceMappingOwnerSnapshot(KEY, claims, 12);
        members.clear();
        claims.clear();
        assertEquals(Set.of("owner-1", "owner-2"), snapshot.ownerInstances().get(APPLICATION));
        assertThrows(UnsupportedOperationException.class,
                () -> snapshot.ownerInstances().put(APPLICATION, Set.of("owner-3")));
        assertThrows(UnsupportedOperationException.class,
                () -> snapshot.ownerInstances().get(APPLICATION).add("owner-3"));
    }

    @Test
    void rejectsForeignNamespaceAndEmptyOwner() {
        assertThrows(IllegalArgumentException.class, () -> new ServiceMappingOwnerSnapshot(KEY,
                Map.of(new ApplicationKey("foreign", "orders"), Set.of("owner-1")), 1));
        assertThrows(IllegalArgumentException.class, () -> new ServiceMappingOwnerSnapshot(KEY,
                Map.of(APPLICATION, Set.of()), 1));
        assertThrows(IllegalArgumentException.class, () -> new ServiceMappingOwnerSnapshot(KEY,
                Map.of(APPLICATION, Set.of("owner-1")), -1));
    }
}
