package com.peachsoft.otryx.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.peachsoft.otryx.api.OtryxRpcIdempotent;
import com.peachsoft.otryx.api.RpcSecurityProfile;
import com.peachsoft.otryx.api.RpcStatus;
import com.peachsoft.otryx.api.ServiceKey;
import com.peachsoft.otryx.codec.RpcCodecRegistry;
import com.peachsoft.otryx.interceptor.RpcInterceptor;
import com.peachsoft.otryx.interceptor.RpcInvocation;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * Consumer 的前置 Hook、一次逻辑调用、Attempt 及取消隔离的客户端真实入口验证。
 *
 * @Author OpenAI
 * @Version 1.0.0-SNAPSHOT
 * @CreateTime 2026/10/21 16:10
 */
class RpcClientInterceptorIntegrationTest {
    private static final ServiceKey SERVICE = new ServiceKey(Api.class.getName(), "1.0.0", "default");

    @Test
    void successfulCallMustHaveOneOrderedLogicalHookAndOneActualAttempt() throws Exception {
        try (var control = new RpcSecurityTestFixtures.ControlPlane()) {
            var transport = new RpcSecurityTestFixtures.ClientTransport(control.clock);
            List<String> events = Collections.synchronizedList(new ArrayList<>());
            CountDownLatch finished = new CountDownLatch(1);
            RpcInterceptor hook = new RpcInterceptor() {
                @Override
                public void before(RpcInvocation call) {
                    assertEquals(RpcInvocation.Side.CONSUMER, call.side());
                    assertEquals(SERVICE, call.service());
                    assertEquals(RpcSecurityTestFixtures.CONSUMER, call.caller().orElseThrow());
                    assertTrue(Thread.currentThread().getName().startsWith("otryx-interceptor-"));
                    events.add("before");
                }

                @Override
                public void beforeAttempt(RpcInvocation call, int attempt) {
                    events.add("attempt-" + attempt);
                }

                @Override
                public void afterAttempt(RpcInvocation call, int attempt, RpcStatus status) {
                    events.add("attempt-done-" + status);
                }

                @Override
                public void after(RpcInvocation call) {
                    events.add("after");
                    finished.countDown();
                }
            };
            try (OtryxRpcClient client = control.clientBuilder(transport,
                    RpcCodecRegistry.of(new RpcSecurityTestFixtures.TestCodec()))
                    .securityProfile(RpcSecurityProfile.TRUSTED_DEVELOPMENT)
                    .interceptors(List.of(hook)).interceptorExecutionLimits(2, 4).build()) {
                control.publish(Api.class);
                Api api = client.refer(Api.class, "1.0.0", "default");
                ApplicationTestControlPlane.awaitReady(client, SERVICE);
                assertEquals("ok", api.call());
                assertTrue(finished.await(3, TimeUnit.SECONDS));
                assertEquals(List.of("before", "attempt-1", "attempt-done-OK", "after"), events);
                assertEquals(1, transport.requests.get());
            }
        }
    }

    @Test
    void throwingBeforeMustNotSendOrExposeRawHookException() throws Exception {
        try (var control = new RpcSecurityTestFixtures.ControlPlane()) {
            var transport = new RpcSecurityTestFixtures.ClientTransport(control.clock);
            RpcInterceptor forbidden = new RpcInterceptor() {
                @Override
                public void before(RpcInvocation call) {
                    throw new IllegalStateException("sensitive-request-value");
                }
            };
            try (OtryxRpcClient client = control.clientBuilder(transport,
                    RpcCodecRegistry.of(new RpcSecurityTestFixtures.TestCodec()))
                    .securityProfile(RpcSecurityProfile.TRUSTED_DEVELOPMENT)
                    .interceptors(List.of(forbidden)).interceptorExecutionLimits(1, 1).build()) {
                control.publish(Api.class);
                Api api = client.refer(Api.class, "1.0.0", "default");
                ApplicationTestControlPlane.awaitReady(client, SERVICE);
                RuntimeException rejected = assertThrows(RuntimeException.class, api::call);
                assertTrue(!rejected.toString().contains("sensitive-request-value"));
                assertEquals(0, transport.requests.get());
            }
        }
    }

    public interface Api {
        @OtryxRpcIdempotent
        String call();
    }
}
