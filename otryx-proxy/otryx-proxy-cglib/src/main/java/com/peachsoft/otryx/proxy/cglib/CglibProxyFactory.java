package com.peachsoft.otryx.proxy.cglib;

import com.peachsoft.otryx.proxy.ProxyFactory;
import com.peachsoft.otryx.proxy.RpcInvocation;
import com.peachsoft.otryx.spi.Extension;
import java.lang.reflect.Method;
import java.util.concurrent.CompletionStage;
import net.sf.cglib.proxy.Enhancer;
import net.sf.cglib.proxy.MethodInterceptor;

/**
 * CGLIB 代理兼容实现。
 *
 * <p>该实现用于兼容需要类代理的调用场景，不作为长期默认高性能路径。
 *
 * @Author Ryan
 * @Version 1.0.0
 * @CreateTime 2026/9/23 10:51
 */
@Extension(CglibProxyFactory.EXTENSION_NAME)
public final class CglibProxyFactory implements ProxyFactory {

    static final String EXTENSION_NAME = "cglib";

    /**
     * 创建 CGLIB 代理工厂。
     */
    public CglibProxyFactory() {
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T create(Class<T> type, RpcInvocation invocation) {
        Enhancer enhancer = new Enhancer();
        if (type.isInterface()) {
            enhancer.setInterfaces(new Class<?>[] {type});
        } else {
            enhancer.setSuperclass(type);
        }
        enhancer.setCallback((MethodInterceptor) (object, method, arguments, proxy) ->
                invoke(invocation, method, arguments));
        return (T) enhancer.create();
    }

    private static Object invoke(
            RpcInvocation invocation, Method method, Object[] arguments) {
        Object[] actualArguments = arguments == null ? new Object[0] : arguments;
        CompletionStage<Object> stage = invocation.invoke(method, actualArguments);
        if (CompletionStage.class.isAssignableFrom(method.getReturnType())) {
            return stage;
        }
        return stage.toCompletableFuture().join();
    }
}
