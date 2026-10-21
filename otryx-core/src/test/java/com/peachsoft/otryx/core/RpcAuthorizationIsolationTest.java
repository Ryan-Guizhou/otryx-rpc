package com.peachsoft.otryx.core;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.peachsoft.otryx.api.ApplicationKey;
import com.peachsoft.otryx.api.RpcAuthorizationContext;
import com.peachsoft.otryx.api.RpcEndpoint;
import com.peachsoft.otryx.api.RpcPeerIdentity;
import com.peachsoft.otryx.api.ServiceKey;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * 同步阻塞的第三方授权体不能阻塞请求线程或推迟授权超时的建立。
 *
 * @Author OpenAI
 * @Version 1.0.0-SNAPSHOT
 * @CreateTime 2026/10/19 11:18
 */
class RpcAuthorizationIsolationTest {
    @Test
    void blockingPolicyBodyMustNotBlockRequestLoop() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch marker = new CountDownLatch(1);
        RpcAuthorizationGate gate = new RpcAuthorizationGate(context -> {
            entered.countDown();
            boolean interrupted = false;
            while (true) {
                try {
                    release.await();
                    break;
                } catch (InterruptedException ignored) {
                    interrupted = true;
                }
            }
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
            return null;
        }, 1, 256L, Duration.ofMillis(50));
        ExecutorService requestLoop = Executors.newSingleThreadExecutor();
        try {
            RpcAuthorizationContext context = context();
            requestLoop.execute(() -> gate.authorize(context, 64, Long.MAX_VALUE));
            assertTrue(entered.await(1, TimeUnit.SECONDS));
            requestLoop.execute(marker::countDown);
            assertTrue(marker.await(1, TimeUnit.SECONDS),
                    "Request loop must advance while the policy body is still blocked");
        } finally {
            release.countDown();
            gate.close();
            requestLoop.shutdownNow();
            assertTrue(requestLoop.awaitTermination(2, TimeUnit.SECONDS));
        }
    }

    private static RpcAuthorizationContext context() throws Exception {
        ServiceKey service = new ServiceKey("example.Api", "1.0.0", "default");
        RpcEndpoint remote = new RpcEndpoint("127.0.0.1", 29090);
        // 同一行为回归在安全 API 迁移前后的精确基线上运行。
        var constructor = RpcAuthorizationContext.class.getDeclaredConstructors()[0];
        return (RpcAuthorizationContext) constructor.newInstance(constructor.getParameterCount() == 4
                ? new Object[] {RpcPeerIdentity.anonymous(), service, 100, remote}
                : new Object[] {RpcPeerIdentity.anonymous(), new ApplicationKey("test", "provider"),
                        service, 100, remote, Instant.now(), Instant.now().plusSeconds(30)});
    }
}
