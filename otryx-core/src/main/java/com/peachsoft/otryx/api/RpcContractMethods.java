package com.peachsoft.otryx.api;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Consumer/Provider/Schema 共享的有效 RPC 接口方法定义。
 *
 * <p>仅将接口中 public abstract 实例方法视作远程 RPC 方法；
 * Object 方法、default/static、bridge/synthetic 方法不是远程
 * RPC 调用契约。泛型方法声明在尚未统一编译期类型变量绑定时
 * 明确拒绝，不允许 Generated 与反射 fallback 静默产生不同语义。
 *
 * @Author OpenAI
 * @Version 1.0.0-SNAPSHOT
 * @CreateTime 2026/10/12 19:07
 */
public final class RpcContractMethods {

    private RpcContractMethods() {
    }

    /**
     * 返回稳定排序、去重后的有效远程调用方法集合。
     *
     * @param api 必须是 Java 接口
     * @return 有效 RPC 方法，不含 default/static/Object
     */
    public static List<Method> resolve(Class<?> api) {
        Objects.requireNonNull(api, "api");
        if (!api.isInterface()) {
            throw new IllegalArgumentException(
                    "RPC service contract must be an interface: " + api.getName());
        }
        Map<String, Method> methods = new LinkedHashMap<>();
        Method[] discovered = api.getMethods();
        Arrays.sort(discovered, Comparator.comparing(RpcContractMethods::signature));
        for (Method method : discovered) {
            int modifiers = method.getModifiers();
            if (!Modifier.isPublic(modifiers)
                    || !Modifier.isAbstract(modifiers)
                    || Modifier.isStatic(modifiers)
                    || method.isBridge()
                    || method.isSynthetic()
                    || method.isDefault()
                    || isObjectMethod(method)) {
                continue;
            }
            if (method.getTypeParameters().length != 0) {
                throw new IllegalArgumentException(
                        "Generic RPC methods are not supported: " + method);
            }
            methods.putIfAbsent(signature(method), method);
        }
        return List.copyOf(methods.values());
    }

    private static boolean isObjectMethod(Method method) {
        try {
            Object.class.getMethod(
                    method.getName(),
                    method.getParameterTypes());
            return true;
        } catch (NoSuchMethodException ignored) {
            return false;
        }
    }

    private static String signature(Method method) {
        StringBuilder signature = new StringBuilder(method.getName()).append('(');
        for (Class<?> parameter : method.getParameterTypes()) {
            signature.append(parameter.getName()).append(';');
        }
        return signature.append(')').append(method.getReturnType().getName()).toString();
    }
}
