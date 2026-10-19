package com.peachsoft.otryx.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.peachsoft.otryx.api.ApplicationKey;
import com.peachsoft.otryx.api.RpcAuthorizationContext;
import com.peachsoft.otryx.api.RpcAuthorizationDecision;
import com.peachsoft.otryx.api.RpcEndpoint;
import com.peachsoft.otryx.api.RpcPeerIdentity;
import com.peachsoft.otryx.api.ServiceKey;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import org.junit.jupiter.api.Test;

/**
 * 验证授权方法体、原始 Stage 与 owned 队列放弃之间的真实双终态所有权。
 *
 * @Author OpenAI
 * @Version 1.0.0-SNAPSHOT
 * @CreateTime 2026/10/19 11:18
 */
class RpcAuthorizationWorkOwnershipTest {
    @Test
    void timeoutRetainsSlotUntilBodyAndOriginalStageBothFinish() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch returned = new CountDownLatch(1);
        CompletableFuture<RpcAuthorizationDecision> original = new CompletableFuture<>();
        RpcAuthorizationGate gate = new RpcAuthorizationGate(context -> {
            entered.countDown();
            awaitIgnoringInterrupt(release);
            returned.countDown();
            return original;
        }, 1, 256L, Duration.ofMillis(80));
        try {
            var response = gate.authorize(context(), 64, Long.MAX_VALUE).toCompletableFuture();
            assertTrue(entered.await(1, TimeUnit.SECONDS));
            assertEquals(RpcAuthorizationGate.Decision.DENY, response.get(2, TimeUnit.SECONDS).decision());
            assertEquals(0L, gate.pendingFrameBytes());
            assertEquals(1, gate.activeAuthorizations());
            assertEquals(RpcAuthorizationGate.Decision.OVERLOADED,
                    gate.authorize(context(), 64, Long.MAX_VALUE).toCompletableFuture().join().decision());
            release.countDown();
            assertTrue(returned.await(1, TimeUnit.SECONDS));
            assertEquals(1, gate.activeAuthorizations());
            original.complete(allow());
            ApplicationTestControlPlane.await(() -> gate.activeAuthorizations() == 0, "Original work did not finish");
        } finally {
            release.countDown();
            original.complete(RpcAuthorizationDecision.deny());
            gate.close();
        }
    }

    @Test
    void logicalCompletionReleasesFrameBeforeDependentCallbacksRun() throws Exception {
        CompletableFuture<RpcAuthorizationDecision> original = new CompletableFuture<>();
        RpcAuthorizationGate gate = new RpcAuthorizationGate(
                context -> original, 1, 256L, Duration.ofSeconds(5));
        try {
            var response = gate.authorize(context(), 64, Long.MAX_VALUE).toCompletableFuture();
            CompletableFuture<Long> observedBytes = response.thenApply(ignored -> gate.pendingFrameBytes());
            original.complete(RpcAuthorizationDecision.deny());
            assertEquals(0L, observedBytes.get(1, TimeUnit.SECONDS));
            assertEquals(RpcAuthorizationGate.Decision.DENY, response.join().decision());
        } finally {
            original.complete(RpcAuthorizationDecision.deny());
            gate.close();
        }
    }

    @Test
    void inlineStageCompletionDoesNotReleaseBeforeWorkBodyFinally() throws Exception {
        CountDownLatch callback = new CountDownLatch(1);
        CountDownLatch returnFromRegistration = new CountDownLatch(1);
        CompletableFuture<RpcAuthorizationDecision> original = new CompletableFuture<>() {
            @Override
            public CompletableFuture<RpcAuthorizationDecision> whenComplete(
                    BiConsumer<? super RpcAuthorizationDecision, ? super Throwable> action) {
                CompletableFuture<RpcAuthorizationDecision> next = super.whenComplete(action);
                callback.countDown();
                awaitIgnoringInterrupt(returnFromRegistration);
                return next;
            }
        };
        original.complete(allow());
        RpcAuthorizationGate gate = new RpcAuthorizationGate(context -> original, 1, 256L, Duration.ofSeconds(2));
        try {
            var response = gate.authorize(context(), 64, Long.MAX_VALUE).toCompletableFuture();
            assertTrue(callback.await(1, TimeUnit.SECONDS));
            assertEquals(RpcAuthorizationGate.Decision.ALLOW, response.get(1, TimeUnit.SECONDS).decision());
            assertEquals(1, gate.activeAuthorizations());
            assertEquals(RpcAuthorizationGate.Decision.OVERLOADED,
                    gate.authorize(context(), 64, Long.MAX_VALUE).toCompletableFuture().join().decision());
            returnFromRegistration.countDown();
            ApplicationTestControlPlane.await(() -> gate.activeAuthorizations() == 0, "Body terminal did not release");
        } finally {
            returnFromRegistration.countDown();
            gate.close();
        }
    }

    @Test
    void queuedTimeoutAbandonsOnlyWorkThatNeverRan() throws Exception {
        assertOwnedQueueAbandon(false, false);
    }

    @Test
    void closeAbandonsQueueWhileIgnoringInterruptBodyKeepsItsSlot() throws Exception {
        assertOwnedQueueAbandon(true, false);
    }

    @Test
    void queuedCancellationCannotStartPolicyLaterOrReleaseTwice() throws Exception {
        assertOwnedQueueAbandon(false, true);
    }

    private static void assertOwnedQueueAbandon(boolean close, boolean cancel) throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger invocations = new AtomicInteger();
        CompletableFuture<RpcAuthorizationDecision> original = new CompletableFuture<>();
        RpcAuthorizationGate gate = new RpcAuthorizationGate(context -> {
            invocations.incrementAndGet();
            entered.countDown();
            awaitIgnoringInterrupt(release);
            return original;
        }, 2, 256L, close || cancel ? Duration.ofSeconds(5) : Duration.ofMillis(80), 1);
        try {
            gate.authorize(context(), 64, Long.MAX_VALUE);
            assertTrue(entered.await(1, TimeUnit.SECONDS));
            var queued = gate.authorize(context(), 64, Long.MAX_VALUE).toCompletableFuture();
            assertEquals(2, gate.activeAuthorizations());
            if (close) {
                gate.close();
                gate.close();
            } else if (cancel) {
                assertTrue(queued.cancel(true));
            }
            if (!cancel) {
                assertEquals(RpcAuthorizationGate.Decision.DENY, queued.get(2, TimeUnit.SECONDS).decision());
            }
            assertEquals(1, gate.activeAuthorizations());
            assertEquals(1, invocations.get());
            release.countDown();
            original.complete(allow());
            ApplicationTestControlPlane.await(() -> gate.activeAuthorizations() == 0, "Owned work did not settle");
            assertEquals(1, invocations.get());
            assertEquals(0L, gate.pendingFrameBytes());
            assertFalse(original.isCancelled());
        } finally {
            release.countDown();
            original.complete(RpcAuthorizationDecision.deny());
            gate.close();
        }
    }

    @Test
    void queuedPolicyRechecksTheLocalSafetyGuardBeforeCallingUserCode() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger invocations = new AtomicInteger();
        AtomicBoolean safe = new AtomicBoolean(true);
        RpcAuthorizationGate gate = new RpcAuthorizationGate(context -> {
            if (invocations.incrementAndGet() == 1) {
                entered.countDown();
                awaitIgnoringInterrupt(release);
            }
            return CompletableFuture.completedFuture(allow());
        }, 2, 256L, Duration.ofSeconds(2), 1);
        try {
            gate.authorize(context(), 64, Long.MAX_VALUE);
            assertTrue(entered.await(1, TimeUnit.SECONDS));
            var queued = gate.authorize(context(), 64, Long.MAX_VALUE, safe::get).toCompletableFuture();
            safe.set(false);
            release.countDown();
            assertEquals(RpcAuthorizationGate.Decision.DENY, queued.get(1, TimeUnit.SECONDS).decision());
            assertEquals(1, invocations.get());
        } finally {
            release.countDown();
            gate.close();
        }
    }

    private static RpcAuthorizationContext context() {
        Instant now = Instant.now();
        return new RpcAuthorizationContext(RpcPeerIdentity.anonymous(), new ApplicationKey("test", "provider"),
                new ServiceKey("example.Api", "1.0.0", "default"), 100, new RpcEndpoint("127.0.0.1", 19091),
                now, now.plusSeconds(30));
    }

    private static RpcAuthorizationDecision allow() {
        return RpcAuthorizationDecision.allowUntil(Instant.now().plusSeconds(30));
    }

    private static void awaitIgnoringInterrupt(CountDownLatch latch) {
        boolean interrupted = false;
        while (true) {
            try {
                latch.await();
                break;
            } catch (InterruptedException ignored) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
