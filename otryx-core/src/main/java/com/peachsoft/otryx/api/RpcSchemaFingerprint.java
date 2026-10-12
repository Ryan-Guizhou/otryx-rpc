package com.peachsoft.otryx.api;

import java.lang.reflect.Field;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * RPC 契约结构的确定性 Schema Fingerprint 计算器。
 *
 * <p>统一使用 RpcContractMethods 解析有效方法；自定义参数化 DTO
 * 会展开字段结构并代入类型变量，枚举包含常量集合，递归图按照
 * 类名截断。类加载器本身不参加哈希，使独立 JVM 之间可比较。
 *
 * <p>Fingerprint 不是远端反序列化白名单，也不代表 Fory Type ID。
 *
 * @Author Ryan
 * @Version 1.0.0
 * @CreateTime 2026/9/30 11:16
 */
public final class RpcSchemaFingerprint {

    /** 当前 Fingerprint 规范版本，随当前 SNAPSHOT 保持不变。 */
    public static final int SCHEMA_VERSION = 1;

    private RpcSchemaFingerprint() {
    }

    /**
     * 计算服务的 SHA-256 Schema Fingerprint。
     *
     * @param key 包含服务名、业务版本和分组的 ServiceKey
     * @param api 要解析的 RPC Java 接口
     * @return 64 位十六进制 SHA-256 字符串
     */
    public static String serviceFingerprint(
            ServiceKey key,
            Class<?> api) {
        List<String> methods = new ArrayList<>();
        for (var method : RpcContractMethods.resolve(api)) {
            StringBuilder value = new StringBuilder()
                    .append(method.getName()).append('(');
            for (Type parameter : method.getGenericParameterTypes()) {
                value.append(typeSchema(parameter, Map.of(), new HashSet<>()))
                        .append(';');
            }
            value.append(')')
                    .append(typeSchema(
                            method.getGenericReturnType(),
                            Map.of(),
                            new HashSet<>()));
            methods.add(value.toString());
        }
        methods.sort(String::compareTo);

        StringBuilder schema = new StringBuilder()
                .append("schema:")
                .append(SCHEMA_VERSION)
                .append('\n')
                .append(key.canonicalName())
                .append('\n');
        methods.forEach(method -> schema.append(method).append('\n'));
        return sha256(schema.toString());
    }

    private static String typeSchema(
            Type type,
            Map<TypeVariable<?>, Type> bindings,
            Set<String> visiting) {
        if (type instanceof TypeVariable<?> variable) {
            Type actual = bindings.get(variable);
            if (actual != null && actual != variable) {
                return typeSchema(actual, bindings, visiting);
            }
            return "typevar:" + variable.getName();
        }
        if (type instanceof ParameterizedType parameterized) {
            Type raw = parameterized.getRawType();
            Type[] arguments = parameterized.getActualTypeArguments();
            StringBuilder value = new StringBuilder(
                    RpcTypeIds.canonicalName(raw)).append('<');
            Map<TypeVariable<?>, Type> bound =
                    new LinkedHashMap<>(bindings);
            if (raw instanceof Class<?> clazz) {
                TypeVariable<?>[] parameters = clazz.getTypeParameters();
                for (int index = 0;
                        index < Math.min(parameters.length, arguments.length);
                        index++) {
                    bound.put(parameters[index], arguments[index]);
                }
            }
            for (Type argument : arguments) {
                value.append(typeSchema(argument, bindings, visiting))
                        .append(';');
            }
            value.append('>');
            if (raw instanceof Class<?> clazz && !isLeaf(clazz)) {
                value.append(dtoFields(clazz, bound, visiting));
            }
            return value.toString();
        }
        if (type instanceof GenericArrayType array) {
            return typeSchema(array.getGenericComponentType(), bindings, visiting)
                    + "[]";
        }
        if (type instanceof WildcardType wildcard) {
            StringBuilder result = new StringBuilder("wildcard{");
            for (Type upper : wildcard.getUpperBounds()) {
                result.append("extends:")
                        .append(typeSchema(upper, bindings, visiting)).append(';');
            }
            for (Type lower : wildcard.getLowerBounds()) {
                result.append("super:")
                        .append(typeSchema(lower, bindings, visiting)).append(';');
            }
            return result.append('}').toString();
        }
        if (!(type instanceof Class<?> clazz)) {
            return RpcTypeIds.canonicalName(type);
        }
        if (clazz.isArray()) {
            return typeSchema(clazz.getComponentType(), bindings, visiting)
                    + "[]";
        }
        if (clazz.isEnum()) {
            StringBuilder value = new StringBuilder(clazz.getName())
                    .append("[enum:");
            for (Object constant : clazz.getEnumConstants()) {
                value.append(((Enum<?>) constant).name()).append(';');
            }
            return value.append(']').toString();
        }
        if (isLeaf(clazz)) {
            return clazz.getName();
        }
        return clazz.getName() + dtoFields(clazz, bindings, visiting);
    }

    private static String dtoFields(
            Class<?> clazz,
            Map<TypeVariable<?>, Type> bindings,
            Set<String> visiting) {
        if (!visiting.add(clazz.getName())) {
            return "{#recursive}";
        }
        try {
            StringBuilder value = new StringBuilder("{");
            if (clazz.isRecord()) {
                for (RecordComponent component : clazz.getRecordComponents()) {
                    value.append(component.getName()).append(':')
                            .append(typeSchema(
                                    component.getGenericType(),
                                    bindings,
                                    visiting))
                            .append(';');
                }
            } else {
                List<Field> fields = new ArrayList<>(
                        Arrays.asList(clazz.getDeclaredFields()));
                fields.sort(Comparator.comparing(Field::getName));
                for (Field field : fields) {
                    int modifiers = field.getModifiers();
                    if (Modifier.isStatic(modifiers)
                            || Modifier.isTransient(modifiers)
                            || field.isSynthetic()) {
                        continue;
                    }
                    value.append(field.getName()).append(':')
                            .append(typeSchema(
                                    field.getGenericType(),
                                    bindings,
                                    visiting))
                            .append(';');
                }
                Type parent = clazz.getGenericSuperclass();
                if (parent != null && parent != Object.class) {
                    value.append("#super:")
                            .append(typeSchema(parent, bindings, visiting))
                            .append(';');
                }
            }
            return value.append('}').toString();
        } finally {
            visiting.remove(clazz.getName());
        }
    }

    private static boolean isLeaf(Class<?> clazz) {
        return clazz.isPrimitive()
                || clazz == String.class
                || clazz == Void.class
                || clazz.getName().startsWith("java.")
                || clazz.getName().startsWith("javax.")
                || clazz.getName().startsWith("jakarta.");
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(
                    digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException(
                    "SHA-256 is unavailable",
                    error);
        }
    }
}
