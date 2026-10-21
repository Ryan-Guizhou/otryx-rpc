package com.peachsoft.otryx.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.peachsoft.otryx.api.RpcAuthorizationContext;
import com.peachsoft.otryx.api.ApplicationKey;
import com.peachsoft.otryx.api.RpcAuthorizationDecision;
import com.peachsoft.otryx.api.RpcEndpoint;
import com.peachsoft.otryx.api.RpcPeerIdentity;
import com.peachsoft.otryx.api.ServiceKey;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * 验证关闭期间的授权 Future 与字节租约不因取消 Timer 而泄漏。
 *
 * @Author OpenAI
 * @Version 1.0.0-SNAPSHOT
 * @CreateTime 2026/10/13 15:55
 */
class RpcAuthorizationGateTest {

    @Test
    void closeMustCompletePendingAuthorizationAndReleaseFrameLease()
            throws Exception {
        CompletableFuture<RpcAuthorizationDecision> neverCompletes = new CompletableFuture<>();
        RpcAuthorizationGate gate = new RpcAuthorizationGate(
                context -> neverCompletes, 1, 256L, Duration.ofHours(1));
        RpcAuthorizationContext context = new RpcAuthorizationContext(
                RpcPeerIdentity.anonymous(),
                new ApplicationKey("test", "provider"),
                new ServiceKey("example.Api", "1.0.0", "default"),
                100,
                new RpcEndpoint("127.0.0.1", 29090), Instant.now(), Instant.now().plusSeconds(30));
        CompletableFuture<RpcAuthorizationGate.Result> result = gate
                .authorize(context, 64, Long.MAX_VALUE)
                .toCompletableFuture();

        assertFalse(result.isDone());
        assertEquals(64L, gate.pendingFrameBytes());

        gate.close();
        gate.close();
        assertEquals(RpcAuthorizationGate.Decision.DENY,
                result.get(1, TimeUnit.SECONDS).decision());
        assertEquals(0L, gate.pendingFrameBytes());

        // 关闭之后真实策略仍可能完成，不得重复释放请求 Frame。
        neverCompletes.complete(RpcAuthorizationDecision.allowUntil(Instant.now().plusSeconds(30)));
        assertEquals(0L, gate.pendingFrameBytes());
    }

    @Test
    void closingDuringAuthorizationSetupMustFailClosed() throws Exception {
        RpcAuthorizationGate gate = new RpcAuthorizationGate(
                context -> CompletableFuture.completedFuture(
                        RpcAuthorizationDecision.allowUntil(context.maximumValidUntil())),
                1, 128L, Duration.ofSeconds(1));
        gate.close();
        RpcAuthorizationContext context = new RpcAuthorizationContext(
                RpcPeerIdentity.anonymous(),
                new ApplicationKey("test", "provider"),
                new ServiceKey("example.Api", "1.0.0", "default"),
                100, new RpcEndpoint("127.0.0.1", 29090), Instant.now(), Instant.now().plusSeconds(30));
        assertEquals(RpcAuthorizationGate.Decision.OVERLOADED,
                gate.authorize(context, 16, Long.MAX_VALUE)
                        .toCompletableFuture().get(1, TimeUnit.SECONDS).decision());
        assertEquals(0L, gate.pendingFrameBytes());
    }
}
