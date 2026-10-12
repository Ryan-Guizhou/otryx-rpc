package com.peachsoft.otryx.proxy.jdk;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * 未生成 Stub 时 JDK Proxy 的默认接口方法本地执行验证。
 *
 * @Author OpenAI
 * @Version 1.0.0-SNAPSHOT
 * @CreateTime 2026/10/12 19:07
 */
class JdkProxyDefaultMethodTest {

    @Test
    void interfaceDefaultMethodMustNotMakeRemoteCall() {
        AtomicInteger remoteCalls = new AtomicInteger();
        LocalContract client = new JdkProxyFactory().create(
                LocalContract.class,
                (method, args) -> {
                    remoteCalls.incrementAndGet();
                    return CompletableFuture.completedFuture("remote");
                });

        assertEquals("local", client.local());
        assertEquals(0, remoteCalls.get());
        assertEquals("remote", client.remote());
        assertEquals(1, remoteCalls.get());
    }

    interface LocalContract {
        String remote();

        default String local() {
            return "local";
        }
    }
}
