package com.peachsoft.otryx.proxy.jdk;

import com.peachsoft.otryx.proxy.ProxyFactory;
import com.peachsoft.otryx.proxy.RpcInvocation;
import com.peachsoft.otryx.spi.Extension;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.concurrent.CompletionStage;

/**
 * JDK 动态代理实现，作为接口类型的默认代理策略。
 *
 * @Author Ryan
 * @Version 1.0.0
 * @CreateTime 2026/9/23 10:51
 */
@Extension(JdkProxyFactory.EXTENSION_NAME)
public final class JdkProxyFactory implements ProxyFactory {

    static final String EXTENSION_NAME = "jdk";
    private static final String METHOD_TO_STRING = "toString";
    private static final String METHOD_HASH_CODE = "hashCode";
    private static final String METHOD_EQUALS = "equals";

    /**
     * 创建 JDK 动态代理工厂。
     */
    public JdkProxyFactory() {
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T create(Class<T> type, RpcInvocation invocation) {
        if (!type.isInterface()) {
            throw new IllegalArgumentException(
                    "JDK proxy requires interface: " + type.getName());
        }

        return (T) Proxy.newProxyInstance(
                type.getClassLoader(),
                new Class<?>[] {type},
                (proxy, method, arguments) -> invoke(proxy, type, invocation, method, arguments));
    }

    private static Object invoke(
            Object proxy,
            Class<?> serviceType,
            RpcInvocation invocation,
            Method method,
            Object[] arguments) {
        if (method.getDeclaringClass() == Object.class) {
            return invokeObjectMethod(proxy, serviceType, method, arguments);
        }

        Object[] actualArguments = arguments == null ? new Object[0] : arguments;
        CompletionStage<Object> stage = invocation.invoke(method, actualArguments);
        if (CompletionStage.class.isAssignableFrom(method.getReturnType())) {
            return stage;
        }
        return stage.toCompletableFuture().join();
    }

    private static Object invokeObjectMethod(
            Object proxy, Class<?> serviceType, Method method, Object[] arguments) {
        return switch (method.getName()) {
            case METHOD_TO_STRING -> "OtryxRpcProxy(" + serviceType.getName() + ")";
            case METHOD_HASH_CODE -> System.identityHashCode(proxy);
            case METHOD_EQUALS -> proxy == arguments[0];
            default -> throw new UnsupportedOperationException(method.getName());
        };
    }
}
