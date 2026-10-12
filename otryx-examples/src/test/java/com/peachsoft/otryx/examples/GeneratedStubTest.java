package com.peachsoft.otryx.examples;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.peachsoft.otryx.api.RpcIds;
import com.peachsoft.otryx.generated.RpcGeneratedClients;
import com.peachsoft.otryx.generated.RpcGeneratedInvocation;
import com.peachsoft.otryx.generated.RpcGeneratedServers;
import com.peachsoft.otryx.proxy.jdk.JdkProxyFactory;
import java.lang.reflect.Method;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * 验证注解处理器生成的客户端和服务端 Stub 正确调用。
 *
 * @Author Ryan
 * @Version 1.0.0
 * @CreateTime 2026/9/23 11:46
 */
class GeneratedStubTest {

    @Test
    void shouldGenerateClientFactoryForRpcContract() {
        var generated = RpcGeneratedClients.find(GreetingService.class);

        assertTrue(generated.isPresent());
        assertEquals(
                GreetingService.class,
                generated.orElseThrow().serviceType());
    }

    @Test
    void generatedClientShouldUseAritySpecificRuntimeCompatibleMethodId()
            throws Exception {
        var factory = RpcGeneratedClients.find(GreetingService.class)
                .orElseThrow();
        AtomicInteger actualMethodId = new AtomicInteger();
        AtomicInteger invoke1Count = new AtomicInteger();
        GreetingReply expected = new GreetingReply("generated");

        GreetingService client = factory.create(new RpcGeneratedInvocation() {
            @Override
            public CompletionStage<Object> invoke0(int methodId) {
                throw new AssertionError("Unexpected arity");
            }

            @Override
            public CompletionStage<Object> invoke1(
                    int methodId,
                    Object argument0) {
                actualMethodId.set(methodId);
                invoke1Count.incrementAndGet();
                assertEquals(
                        new GreetingRequest("OTRYX RPC"),
                        argument0);
                return CompletableFuture.completedFuture(expected);
            }

            @Override
            public CompletionStage<Object> invoke2(
                    int methodId,
                    Object argument0,
                    Object argument1) {
                throw new AssertionError("Unexpected arity");
            }

            @Override
            public CompletionStage<Object> invoke3(
                    int methodId,
                    Object argument0,
                    Object argument1,
                    Object argument2) {
                throw new AssertionError("Unexpected arity");
            }

            @Override
            public CompletionStage<Object> invoke4(
                    int methodId,
                    Object argument0,
                    Object argument1,
                    Object argument2,
                    Object argument3) {
                throw new AssertionError("Unexpected arity");
            }

            @Override
            public CompletionStage<Object> invokeN(
                    int methodId,
                    Object[] arguments) {
                throw new AssertionError("Unexpected array fallback");
            }
        });

        GreetingReply actual =
                client.hello(new GreetingRequest("OTRYX RPC"));
        Method method = GreetingService.class.getMethod(
                "hello",
                GreetingRequest.class);

        assertEquals(expected, actual);
        assertEquals(1, invoke1Count.get());
        assertEquals(
                RpcIds.methodId(method),
                actualMethodId.get());
    }

    @Test
    void generatedAndFallbackMustMatchNullAndExceptionalResults() {
        var factory = RpcGeneratedClients.find(GreetingService.class)
                .orElseThrow();
        RpcGeneratedInvocation invocation = (RpcGeneratedInvocation)
                java.lang.reflect.Proxy.newProxyInstance(
                        RpcGeneratedInvocation.class.getClassLoader(),
                        new Class<?>[] {RpcGeneratedInvocation.class},
                        (proxy, method, args) -> {
                            if (!"invoke1".equals(method.getName())) {
                                throw new AssertionError(
                                        "Unexpected generated invocation arity");
                            }
                            return resultFor(args[1]);
                        });
        GreetingService generated = factory.create(invocation);
        GreetingService fallback = new JdkProxyFactory().create(
                GreetingService.class,
                (method, args) -> resultFor(args[0]));

        assertNull(generated.hello(null));
        assertNull(fallback.hello(null));

        GreetingRequest failing = new GreetingRequest("fail");
        assertThrows(CompletionException.class,
                () -> generated.hello(failing));
        assertThrows(CompletionException.class,
                () -> fallback.hello(failing));
    }

    private static CompletionStage<Object> resultFor(Object request) {
        if (request == null) {
            return CompletableFuture.completedFuture(null);
        }
        return CompletableFuture.failedFuture(
                new IllegalArgumentException("Synthetic failure"));
    }

    @Test
    void generatedServerShouldDispatchDirectlyByMethodId()
            throws Throwable {
        GreetingService target = new GreetingServiceImpl();
        var dispatcher = RpcGeneratedServers
                .create(GreetingService.class, target)
                .orElseThrow();
        Method method = GreetingService.class.getMethod(
                "hello",
                GreetingRequest.class);

        Object result = dispatcher.invoke(
                RpcIds.methodId(method),
                new Object[] {new GreetingRequest("OTRYX RPC")});

        assertEquals(
                new GreetingReply("Hello, OTRYX RPC!"),
                result);
    }
}
