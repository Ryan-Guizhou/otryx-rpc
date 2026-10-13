package com.peachsoft.otryx.api;

import java.lang.reflect.Field;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * RPC 契约方法可达 DTO 类型图。
 *
 * <p>由方法参数和返回类型开始遍历：自定义 DTO 字段、record component、
 * 继承泛型、数组及容器泛型参数；不遍历 JDK 实现类内部字段，
 * 不从任意运行时 Object 推断允许的多态子类。类型 ID 注册依旧只是
 * 框架碰撞检测，不代表 Fory Native Wire 的 class 注册。
 *
 * @Author OpenAI
 * @Version 1.0.0-SNAPSHOT
 * @CreateTime 2026/10/12 19:28
 */
public final class RpcContractTypeModel {

    private RpcContractTypeModel() {
    }

    /**
     * 返回方法签名及可达 DTO 的稳定 Java 类型列表。
     *
     * @param descriptor RPC 方法绑定描述
     * @return 按规范名称排序的完整可达类型快照
     */
    public static List<Type> reachableTypes(RpcMethodDescriptor descriptor) {
        Objects.requireNonNull(descriptor, "descriptor");
        Map<String, Type> result = new LinkedHashMap<>();
        for (Type type : descriptor.parameterTypes()) {
            visit(type, Map.of(), new HashSet<>(), result);
        }
        visit(descriptor.returnType(), Map.of(), new HashSet<>(), result);
        List<Type> types = new ArrayList<>(result.values());
        types.sort(Comparator.comparing(RpcTypeIds::canonicalName));
        return List.copyOf(types);
    }

    /**
     * 返回需要由应用安全策略显式批准的非 JDK 合约类型。
     *
     * @param descriptor 方法级绑定描述
     * @return 去重、稳定排序的应用 Class
     */
    public static List<Class<?>> applicationClasses(
            RpcMethodDescriptor descriptor) {
        List<Class<?>> types = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Type type : reachableTypes(descriptor)) {
            Class<?> clazz = rawClass(type);
            if (clazz != null && isApplicationType(clazz)
                    && seen.add(clazz.getName())) {
                types.add(clazz);
            }
        }
        types.sort(Comparator.comparing(Class::getName));
        return List.copyOf(types);
    }

    private static void visit(
            Type type,
            Map<TypeVariable<?>, Type> bindings,
            Set<String> active,
            Map<String, Type> result) {
        if (type instanceof TypeVariable<?> variable) {
            Type resolved = bindings.get(variable);
            if (resolved != null && resolved != variable) {
                visit(resolved, bindings, active, result);
            } else {
                for (Type bound : variable.getBounds()) {
                    if (bound != variable) {
                        visit(bound, bindings, active, result);
                    }
                }
            }
            return;
        }
        if (type instanceof WildcardType wildcard) {
            for (Type bound : wildcard.getUpperBounds()) {
                visit(bound, bindings, active, result);
            }
            for (Type bound : wildcard.getLowerBounds()) {
                visit(bound, bindings, active, result);
            }
            return;
        }
        if (type instanceof GenericArrayType array) {
            add(type, result);
            visit(array.getGenericComponentType(), bindings, active, result);
            return;
        }
        if (type instanceof ParameterizedType parameterized) {
            add(type, result);
            Type raw = parameterized.getRawType();
            Type[] arguments = parameterized.getActualTypeArguments();
            Map<TypeVariable<?>, Type> children = new HashMap<>(bindings);
            if (raw instanceof Class<?> clazz) {
                TypeVariable<?>[] formals = clazz.getTypeParameters();
                for (int i = 0; i < Math.min(formals.length, arguments.length); i++) {
                    children.put(formals[i],
                            bindings.getOrDefault(arguments[i], arguments[i]));
                }
            }
            for (Type argument : arguments) {
                visit(argument, bindings, active, result);
            }
            if (raw instanceof Class<?> clazz) {
                add(clazz, result);
                inspectClass(clazz, children, active, result);
            }
            return;
        }
        if (type instanceof Class<?> clazz) {
            add(clazz, result);
            if (clazz.isArray()) {
                visit(clazz.getComponentType(), bindings, active, result);
                return;
            }
            inspectClass(clazz, bindings, active, result);
            return;
        }
        add(type, result);
    }

    private static void inspectClass(
            Class<?> clazz,
            Map<TypeVariable<?>, Type> bindings,
            Set<String> active,
            Map<String, Type> result) {
        if (!isApplicationType(clazz) || !active.add(clazz.getName())) {
            return;
        }
        try {
            if (clazz.isRecord()) {
                for (RecordComponent component : clazz.getRecordComponents()) {
                    visit(component.getGenericType(), bindings, active, result);
                }
            } else {
                List<Field> fields = new ArrayList<>(
                        List.of(clazz.getDeclaredFields()));
                fields.sort(Comparator.comparing(Field::getName));
                for (Field field : fields) {
                    int modifiers = field.getModifiers();
                    if (!Modifier.isStatic(modifiers)
                            && !Modifier.isTransient(modifiers)
                            && !field.isSynthetic()) {
                        visit(field.getGenericType(), bindings, active, result);
                    }
                }
                Type parent = clazz.getGenericSuperclass();
                if (parent != null && parent != Object.class) {
                    visit(parent, bindings, active, result);
                }
            }
        } finally {
            active.remove(clazz.getName());
        }
    }

    private static boolean isApplicationType(Class<?> clazz) {
        if (clazz.isPrimitive() || clazz.isArray() || clazz.isEnum()
                || clazz.isAnnotation() || clazz == Object.class) {
            return clazz.isEnum() && !isJdkType(clazz);
        }
        return !isJdkType(clazz);
    }

    private static boolean isJdkType(Class<?> clazz) {
        String name = clazz.getName();
        return name.startsWith("java.") || name.startsWith("javax.")
                || name.startsWith("jakarta.") || name.startsWith("jdk.")
                || name.startsWith("sun.");
    }

    private static Class<?> rawClass(Type type) {
        if (type instanceof Class<?> clazz) {
            return clazz;
        }
        if (type instanceof ParameterizedType parameterized
                && parameterized.getRawType() instanceof Class<?> clazz) {
            return clazz;
        }
        return null;
    }

    private static void add(Type type, Map<String, Type> result) {
        result.putIfAbsent(RpcTypeIds.canonicalName(type), type);
    }
}
