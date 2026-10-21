package com.peachsoft.otryx.interceptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.peachsoft.otryx.api.ApplicationKey;
import com.peachsoft.otryx.api.RpcOverloadedException;
import com.peachsoft.otryx.api.RpcStatus;
import com.peachsoft.otryx.api.ServiceKey;
import com.peachsoft.otryx.core.RpcCallContext;
import com.peachsoft.otryx.core.RpcCallOptions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

/**
 * 有界 Hook 运行时的严格顺序、上下文恢复、异常拒绝与真实资源所有权测试。
 *
 * @Author OpenAI
 * @Version 1.0.0-SNAPSHOT
 * @CreateTime 2026/10/21 16:10
 */
class RpcInterceptorRuntimeTest {
    private static final RpcInvocation INVOCATION = new RpcInvocation(RpcInvocation.Side.CONSUMER,
            new ServiceKey("example.Greeting", "1.0.0", "default"), 1,
            Optional.of(new ApplicationKey("tenant", "consumer")));

    @Test
    void hooksMustBeSortedAndRunInReverseOnCompletionWithoutLeakingThreadContext() throws Exception {
        List<String> events = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch done = new CountDownLatch(1);
        RpcInterceptor first = observer(10, "A", events, done);
        RpcInterceptor second = observer(20, "B", events, done);
        try (RpcInterceptorRuntime runtime = new RpcInterceptorRuntime(List.of(second, first), 1, 2, "test")) {
            RpcCallContext captured;
            try (var scope = RpcCallContext.open(new RpcCallOptions(
                    Duration.ofSeconds(3), Map.of("x-test-context", "captured")))) {
                captured = RpcCallContext.current();
                var session = runtime.open(INVOCATION, captured);
                session.before(() -> session.beforeAttempt(1, () -> {
                    session.afterAttempt(1, RpcStatus.OK);
                    session.finish(RpcStatus.OK, false);
                }, failure -> session.finish(RpcStatus.INTERNAL_ERROR, false)),
                        failure -> session.finish(RpcStatus.INTERNAL_ERROR, false));
            }
            assertTrue(done.await(3, TimeUnit.SECONDS));
            awaitRelease(runtime);
            assertEquals(List.of("A.before", "B.before", "A.attempt", "B.attempt",
                    "B.done", "A.done", "B.after", "A.after"), events);
            assertEquals(null, RpcCallContext.current());
            CountDownLatch secondFinished = new CountDownLatch(1);
            var session = runtime.open(INVOCATION, null);
            session.before(() -> session.finish(RpcStatus.OK, false),
                    failure -> session.finish(RpcStatus.INTERNAL_ERROR, false));
            // 第二次任务使用相同的 Worker，不得继承上一请求的 ThreadLocal。
            var marker = runtime.open(INVOCATION, null);
            marker.before(() -> {
                assertEquals(null, RpcCallContext.current());
                marker.finish(RpcStatus.OK, false);
                secondFinished.countDown();
            }, failure -> marker.finish(RpcStatus.INTERNAL_ERROR, false));
            assertTrue(secondFinished.await(3, TimeUnit.SECONDS));
        }
    }

    @Test
    void blockingBeforeMustHoldAdmissionAfterCancellationUntilWorkerActuallyExits() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch terminal = new CountDownLatch(1);
        AtomicBoolean cancelled = new AtomicBoolean();
        RpcInterceptor hanging = new RpcInterceptor() {
            @Override
            public void before(RpcInvocation call) {
                entered.countDown();
                try {
                    assertTrue(release.await(3, TimeUnit.SECONDS));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("interrupted");
                }
            }

            @Override
            public void onCancel(RpcInvocation call) {
                terminal.countDown();
            }
        };
        try (RpcInterceptorRuntime runtime = new RpcInterceptorRuntime(List.of(hanging), 1, 1, "cancel")) {
            var session = runtime.open(INVOCATION, null);
            session.before(() -> {
                if (!cancelled.get()) {
                    throw new IllegalStateException("Cancelled call was incorrectly dispatched");
                }
            }, failure -> { });
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            cancelled.set(true);
            session.finish(RpcStatus.UNAVAILABLE, true);
            assertEquals(1, runtime.activeSessions());
            assertThrows(RpcOverloadedException.class, () -> runtime.open(INVOCATION, null));
            release.countDown();
            assertTrue(terminal.await(2, TimeUnit.SECONDS));
            awaitRelease(runtime);
            assertFalse(RpcCallContext.current() != null);
        } finally {
            release.countDown();
        }
    }

    @Test
    void throwingBeforeMustRejectWithoutInvokingBusinessAndStillRunTerminalOnce() throws Exception {
        CountDownLatch finished = new CountDownLatch(1);
        AtomicBoolean dispatched = new AtomicBoolean();
        List<String> events = Collections.synchronizedList(new ArrayList<>());
        RpcInterceptor rejected = new RpcInterceptor() {
            @Override
            public void before(RpcInvocation call) {
                throw new IllegalStateException("secret-user-payload");
            }

            @Override
            public void onFailure(RpcInvocation call, RpcStatus status) {
                events.add(status.name());
                finished.countDown();
            }
        };
        try (RpcInterceptorRuntime runtime = new RpcInterceptorRuntime(List.of(rejected), 1, 1, "failure")) {
            var session = runtime.open(INVOCATION, null);
            session.before(() -> dispatched.set(true), error -> {
                assertEquals("RPC interceptor before hook rejected call", error.getMessage());
                session.finish(RpcStatus.UNAVAILABLE, false);
            });
            assertTrue(finished.await(2, TimeUnit.SECONDS));
            awaitRelease(runtime);
            assertEquals(List.of("UNAVAILABLE"), events);
            assertFalse(dispatched.get());
        }
    }

    private static RpcInterceptor observer(int order, String name, List<String> events, CountDownLatch done) {
        return new RpcInterceptor() {
            @Override
            public int order() {
                return order;
            }

            @Override
            public void before(RpcInvocation call) {
                assertEquals("captured", RpcCallContext.current().metadata().get("x-test-context"));
                events.add(name + ".before");
            }

            @Override
            public void beforeAttempt(RpcInvocation call, int attempt) {
                events.add(name + ".attempt");
            }

            @Override
            public void afterAttempt(RpcInvocation call, int attempt, RpcStatus status) {
                events.add(name + ".done");
            }

            @Override
            public void after(RpcInvocation call) {
                events.add(name + ".after");
                if ("A".equals(name)) {
                    done.countDown();
                }
            }
        };
    }

    private static void awaitRelease(RpcInterceptorRuntime runtime) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (runtime.activeSessions() > 0 && System.nanoTime() < deadline) {
            Thread.sleep(1L);
        }
        assertEquals(0, runtime.activeSessions());
    }
}
