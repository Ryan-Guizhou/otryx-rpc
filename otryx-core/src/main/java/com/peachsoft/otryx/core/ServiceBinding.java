package com.peachsoft.otryx.core;

import com.peachsoft.otryx.api.OtryxRpcExecution;
import com.peachsoft.otryx.api.RpcExecutionMode;
import com.peachsoft.otryx.api.RpcIds;
import com.peachsoft.otryx.api.RpcContractMethods;
import com.peachsoft.otryx.api.RpcMethodDescriptor;
import com.peachsoft.otryx.api.ServiceKey;
import com.peachsoft.otryx.codec.RpcCodecRegistry;
import com.peachsoft.otryx.codec.RpcMethodCodec;
import com.peachsoft.otryx.generated.RpcGeneratedServerDispatcher;
import com.peachsoft.otryx.generated.RpcGeneratedServers;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Provider 启动阶段预解析的服务分派表。
 *
 * @Author Ryan
 * @Version 1.0.0
 * @CreateTime 2026/9/23 10:51
 */
final class ServiceBinding {
    private final Map<Integer, Invoker> methods;
    private final RpcGeneratedServerDispatcher generatedDispatcher;

    ServiceBinding(
            ServiceKey serviceKey,
            Class<?> api,
            Object target,
            RpcCodecRegistry codecs) {
        this.generatedDispatcher = RpcGeneratedServers
                .create(api, target)
                .orElse(null);

        Map<Integer, Invoker> resolved = new HashMap<>();
        for (Method method : RpcContractMethods.resolve(api)) {
            try {
                int methodId = RpcIds.methodId(method);
                MethodHandle handle = generatedDispatcher == null
                        ? resolveHandle(target, method)
                        : null;
                RpcMethodDescriptor descriptor =
                        RpcMethodDescriptor.from(serviceKey, method);
                Map<Byte, RpcMethodCodec> methodCodecs = new HashMap<>();
                for (byte codecId : codecs.supportedCodecIds()) {
                    methodCodecs.put(
                            codecId,
                            codecs.bind(descriptor, codecId));
                }
                Invoker previous = resolved.putIfAbsent(
                        methodId,
                        new Invoker(
                                handle,
                                Map.copyOf(methodCodecs),
                                executionMode(method)));
                if (previous != null) {
                    throw new IllegalStateException(
                            "Method id collision in "
                                    + api.getName()
                                    + ": "
                                    + methodId);
                }
            } catch (ReflectiveOperationException error) {
                throw new IllegalArgumentException(
                        "Service implementation does not implement "
                                + method,
                        error);
            }
        }
        this.methods = Map.copyOf(resolved);
    }

    /** 返回启动阶段完成解析的服务方法 ID 集合。 */
    Set<Integer> methodIds() {
        return methods.keySet();
    }

    RpcMethodCodec codec(
            int methodId,
            byte codecId) throws NoSuchMethodException {
        Invoker invoker = require(methodId);
        RpcMethodCodec codec = invoker.codecs().get(codecId);
        if (codec == null) {
            throw new IllegalArgumentException(
                    "Unsupported codec "
                            + Byte.toUnsignedInt(codecId)
                            + " for method "
                            + methodId);
        }
        return codec;
    }

    RpcExecutionMode executionMode(
            int methodId) throws NoSuchMethodException {
        return require(methodId).executionMode();
    }

    boolean usesDirectExecution() {
        return methods.values().stream()
                .anyMatch(invoker ->
                        invoker.executionMode() == RpcExecutionMode.DIRECT);
    }

    Object invoke(
            int methodId,
            Object[] arguments) throws Throwable {
        Invoker invoker = require(methodId);
        if (generatedDispatcher != null) {
            return generatedDispatcher.invoke(methodId, arguments);
        }
        return invoker.handle().invokeWithArguments(arguments);
    }

    private static RpcExecutionMode executionMode(Method method) {
        OtryxRpcExecution annotation =
                method.getAnnotation(OtryxRpcExecution.class);
        return annotation == null
                ? RpcExecutionMode.BLOCKING_VIRTUAL
                : annotation.value();
    }

    private static MethodHandle resolveHandle(
            Object target,
            Method method) throws ReflectiveOperationException {
        Method implementation = target.getClass()
                .getMethod(
                        method.getName(),
                        method.getParameterTypes());
        return MethodHandles.publicLookup()
                .unreflect(implementation)
                .bindTo(target);
    }

    private Invoker require(
            int methodId) throws NoSuchMethodException {
        Invoker invoker = methods.get(methodId);
        if (invoker == null) {
            throw new NoSuchMethodException(
                    "Unknown method id: " + methodId);
        }
        return invoker;
    }

    private record Invoker(
            MethodHandle handle,
            Map<Byte, RpcMethodCodec> codecs,
            RpcExecutionMode executionMode) {
    }
}
