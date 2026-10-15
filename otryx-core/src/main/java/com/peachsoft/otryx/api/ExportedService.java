package com.peachsoft.otryx.api;

import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 应用 Manifest 中的一条接口契约，包含已绑定的服务和方法标识。
 *
 * <p>实例注册与接口描述分离：该对象不含 Endpoint、租约或实例 ID。
 * 方法 ID 来自当前 Wire v1 的稳定计算规则，不能作为新协议 ID 使用。
 *
 * @param serviceKey 业务接口、版本及分组
 * @param serviceId 与当前 ServiceKey 对应的稳定服务 ID
 * @param methodIds 有效 RPC 方法标识的不可变集合
 * @param schemaFingerprint 当前 RPC 结构兼容指纹
 *
 * @Author OpenAI
 * @Version 1.0.0-SNAPSHOT
 * @CreateTime 2026/10/15 17:48
 */
public record ExportedService(
        ServiceKey serviceKey,
        int serviceId,
        Set<Integer> methodIds,
        String schemaFingerprint) {

    /** 一个业务接口允许导出的方法数上限。 */
    public static final int MAX_METHODS = 1024;

    /** 接口二进制名称长度上限。 */
    public static final int MAX_SERVICE_NAME_LENGTH = 256;

    /** 分组和版本长度上限。 */
    public static final int MAX_QUALIFIER_LENGTH = 128;

    private static final Pattern INTERFACE_NAME =
            Pattern.compile("[A-Za-z_$][A-Za-z0-9_$]*(\\.[A-Za-z_$][A-Za-z0-9_$]*)*");
    private static final Pattern QUALIFIER =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]*");
    private static final Pattern FINGERPRINT =
            Pattern.compile("[0-9a-f]{64}");

    /** 拒绝伪造的服务 ID、指纹及无法安全持久化的接口键。 */
    public ExportedService {
        Objects.requireNonNull(serviceKey, "serviceKey");
        requireServiceKey(serviceKey);
        if (serviceId != RpcIds.serviceId(serviceKey)) {
            throw new IllegalArgumentException("Manifest service ID does not match ServiceKey");
        }
        Objects.requireNonNull(methodIds, "methodIds");
        if (methodIds.isEmpty() || methodIds.size() > MAX_METHODS) {
            throw new IllegalArgumentException("Invalid manifest method count");
        }
        methodIds = Set.copyOf(methodIds);
        Objects.requireNonNull(schemaFingerprint, "schemaFingerprint");
        if (!FINGERPRINT.matcher(schemaFingerprint).matches()) {
            throw new IllegalArgumentException("Invalid RPC Schema Fingerprint");
        }
    }

    /**
     * 由 Provider 已确认的 Java 接口契约创建清单项。
     *
     * <p>应在 Provider 完成真实 Service Binding 后调用。方法解析与
     * Consumer、Provider 的运行时使用同一 RpcContractMethods 规则。
     *
     * @param key 实际导出接口、版本和分组
     * @param api 已验证并成功绑定的 Java RPC 接口
     * @return 包含全部有效方法 ID 和 Schema Fingerprint 的清单项
     * @throws IllegalArgumentException 接口不匹配、无方法或 ID 冲突时抛出
     */
    public static ExportedService fromContract(ServiceKey key, Class<?> api) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(api, "api");
        if (!api.isInterface() || !api.getName().equals(key.serviceName())) {
            throw new IllegalArgumentException("ServiceKey does not identify the bound RPC interface");
        }
        Set<Integer> ids = new HashSet<>();
        for (Method method : RpcContractMethods.resolve(api)) {
            int methodId = RpcIds.methodId(method);
            if (!ids.add(methodId)) {
                throw new IllegalArgumentException("Method ID collision in " + api.getName());
            }
        }
        return new ExportedService(
                key,
                RpcIds.serviceId(key),
                ids,
                RpcSchemaFingerprint.serviceFingerprint(key, api));
    }

    private static void requireServiceKey(ServiceKey key) {
        String name = key.serviceName();
        if (name.length() > MAX_SERVICE_NAME_LENGTH || !INTERFACE_NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("Invalid manifest interface name");
        }
        requireQualifier(key.version(), "version");
        requireQualifier(key.group(), "group");
    }

    private static void requireQualifier(String value, String name) {
        if (value == null || value.length() > MAX_QUALIFIER_LENGTH
                || !QUALIFIER.matcher(value).matches()) {
            throw new IllegalArgumentException("Invalid manifest " + name);
        }
    }
}
