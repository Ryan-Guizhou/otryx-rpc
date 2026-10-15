package com.peachsoft.otryx.registry;

import com.peachsoft.otryx.api.ApplicationKey;
import com.peachsoft.otryx.api.ServiceKey;
import com.peachsoft.otryx.registry.memory.MemoryServiceMappingRepository;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 应用级映射所有权、隔离、缓存和一致性契约测试。
 *
 * @Author OpenAI
 * @Version 1.0.0-SNAPSHOT
 * @CreateTime 2026/10/15 18:30
 */
class ServiceMappingV2Test {

    private static final ServiceKey GREETING = new ServiceKey("example.Greeting", "1", "default");
    private static final ServiceKey PAYMENT = new ServiceKey("example.Payment", "1", "default");

    @Test
    void sharedApplicationsAndOwnersMustNotLoseEachOther() {
        MemoryServiceMappingRepository repository = new MemoryServiceMappingRepository();
        ApplicationKey alpha = new ApplicationKey("production", "alpha");
        ApplicationKey beta = new ApplicationKey("production", "beta");
        ServiceMappingOwner first = repository.claim(alpha, "first").toCompletableFuture().join();
        ServiceMappingOwner second = repository.claim(alpha, "second").toCompletableFuture().join();
        ServiceMappingOwner third = repository.claim(beta, "third").toCompletableFuture().join();
        repository.publish(first, Set.of(GREETING, PAYMENT)).toCompletableFuture().join();
        repository.publish(second, Set.of(GREETING)).toCompletableFuture().join();
        repository.publish(third, Set.of(GREETING)).toCompletableFuture().join();
        ServiceMappingKey key = new ServiceMappingKey("production", GREETING);
        assertEquals(Set.of(alpha, beta), repository.lookup(key).toCompletableFuture().join().applications());
        repository.revoke(first).toCompletableFuture().join();
        assertEquals(Set.of(alpha, beta), repository.lookup(key).toCompletableFuture().join().applications());
        repository.revoke(second).toCompletableFuture().join();
        assertEquals(Set.of(beta), repository.lookup(key).toCompletableFuture().join().applications());
        assertTrue(repository.lookup(new ServiceMappingKey("test", GREETING))
                .toCompletableFuture().join().applications().isEmpty());
    }

    @Test
    void oldOwnerMustNeverOverwriteNewGeneration() {
        MemoryServiceMappingRepository repository = new MemoryServiceMappingRepository();
        ApplicationKey application = new ApplicationKey("test", "provider");
        ServiceMappingOwner old = repository.claim(application, "instance").toCompletableFuture().join();
        repository.publish(old, Set.of(GREETING)).toCompletableFuture().join();
        ServiceMappingOwner current = repository.claim(application, "instance").toCompletableFuture().join();
        assertThrows(Exception.class, () -> repository.publish(old, Set.of(PAYMENT)).toCompletableFuture().join());
        repository.publish(current, Set.of(PAYMENT)).toCompletableFuture().join();
        repository.revoke(old).toCompletableFuture().join();
        assertEquals(Set.of(application), repository.lookup(new ServiceMappingKey("test", PAYMENT))
                .toCompletableFuture().join().applications());
        assertTrue(repository.lookup(new ServiceMappingKey("test", GREETING))
                .toCompletableFuture().join().applications().isEmpty());
    }

}
