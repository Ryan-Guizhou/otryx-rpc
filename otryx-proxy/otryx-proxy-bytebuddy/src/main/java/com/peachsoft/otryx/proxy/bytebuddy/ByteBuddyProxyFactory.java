package com.peachsoft.otryx.proxy.bytebuddy;

import com.peachsoft.otryx.proxy.ProxyFactory;
import com.peachsoft.otryx.proxy.RpcInvocation;
import com.peachsoft.otryx.spi.Extension;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicLong;
import net.bytebuddy.ByteBuddy;
import net.bytebuddy.dynamic.loading.ClassLoadingStrategy;
import net.bytebuddy.implementation.InvocationHandlerAdapter;
import net.bytebuddy.matcher.ElementMatchers;

/**
 * Byte Buddy Consumer 代理 fallback。
 *
 * <p>Generated Stub 仍然是高性能默认路径；该实现用于没有编译期产物时
 * 的运行时代码生成 fallback。当前只支持 RPC 服务接口。
 *
 * @Author Ryan
 * @Version 1.0.0
 * @CreateTime 2026/9/23 15:46
 */
@Extension(ByteBuddyProxyFactory.EXTENSION_NAME)
public final class ByteBuddyProxyFactory implements ProxyFactory {

    static final String EXTENSION_NAME = "bytebuddy";

    private static final AtomicLong PROXY_SEQUENCE = new AtomicLong();

    /** 创建 Byte Buddy 代理工厂。 */
    public ByteBuddyProxyFactory() {
    }

    @Override
    public <T> T create(
            Class<T> serviceType,
            RpcInvocation invocation) {
        if (!serviceType.isInterface()) {
            throw new IllegalArgumentException(
                    "Byte Buddy RPC fallback requires an interface: "
                            + serviceType.getName());
        }

        InvocationHandler handler = (proxy, method, arguments) ->
                invoke(
                        invocation,
                        method,
                        arguments);
        try {
            String proxyName = proxyName(serviceType);
            Class<? extends T> proxyType = new ByteBuddy()
                    .subclass(Object.class)
                    .name(proxyName)
                    .implement(serviceType)
                    .method(ElementMatchers.isAbstract())
                    .intercept(InvocationHandlerAdapter.of(handler))
                    .make()
                    .load(
                            serviceType.getClassLoader(),
                            ClassLoadingStrategy.Default.INJECTION)
                    .getLoaded()
                    .asSubclass(serviceType);
            return proxyType.getDeclaredConstructor().newInstance();
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException(
                    "Failed to create Byte Buddy RPC proxy for "
                            + serviceType.getName(),
                    error);
        }
    }

    private static String proxyName(Class<?> serviceType) {
        String packageName = serviceType.getPackageName();
        String simpleName = serviceType.getSimpleName()
                + "$OtryxRpcByteBuddy$"
                + PROXY_SEQUENCE.incrementAndGet();
        return packageName.isEmpty()
                ? simpleName
                : packageName + '.' + simpleName;
    }

    private static Object invoke(
            RpcInvocation invocation,
            Method method,
            Object[] arguments) {
        Object[] actualArguments =
                arguments == null ? new Object[0] : arguments;
        CompletionStage<Object> stage =
                invocation.invoke(method, actualArguments);
        if (CompletionStage.class.isAssignableFrom(
                method.getReturnType())) {
            return stage;
        }
        return stage.toCompletableFuture().join();
    }
}
