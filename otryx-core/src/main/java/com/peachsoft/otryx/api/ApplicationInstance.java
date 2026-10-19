package com.peachsoft.otryx.api;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 应用级注册实例快照，同一 RPC 监听端点的多个业务接口共享一份成员记录。
 *
 * <p>纯 Consumer 不允许发布 RPC Endpoint 或 Manifest Revision，路由代码只能将
 * {@link #routableProvider()} 为 true 的实例作为实际 RPC 连接目标。实例属性是路由标签，
 * 不能作为未经认证的调用身份或授权声明。
 *
 * @param instanceId 应用进程实例身份
 * @param application 应用身份
 * @param role 进程服务角色
 * @param rpcEndpoint Provider 的实际 RPC 连接端点，纯 Consumer 必须为 null
 * @param weight Provider 静态权重，纯 Consumer 固定为零
 * @param manifestRevision Provider 所发布的应用服务清单修订版
 * @param attributes 非可信扩展路由标签
 *
 * @Author OTRYX
 * @Version 1.0.0
 * @CreateTime 2026/10/15 17:48
 */
public record ApplicationInstance(
        String instanceId,
        ApplicationKey application,
        ApplicationRole role,
        RpcEndpoint rpcEndpoint,
        int weight,
        String manifestRevision,
        Map<String, String> attributes) {

    private static final int MAX_INSTANCE_ID_LENGTH = 256;
    private static final int MAX_REVISION_LENGTH = 128;
    private static final int MAX_ATTRIBUTES = 64;
    private static final int MAX_ATTRIBUTE_KEY_LENGTH = 128;
    private static final int MAX_ATTRIBUTE_VALUE_LENGTH = 1024;

    /** 校验应用实例角色、端点与元数据的一致性。 */
    public ApplicationInstance {
        instanceId = requirePrintable(instanceId, "instanceId", MAX_INSTANCE_ID_LENGTH);
        Objects.requireNonNull(application, "application");
        Objects.requireNonNull(role, "role");
        if (role.providesRpc()) {
            Objects.requireNonNull(rpcEndpoint, "Provider rpcEndpoint");
            if (weight <= 0) {
                throw new IllegalArgumentException("Provider weight must be positive");
            }
            manifestRevision = requirePrintable(
                    manifestRevision, "manifestRevision", MAX_REVISION_LENGTH);
        } else {
            if (rpcEndpoint != null || weight != 0 || manifestRevision != null) {
                throw new IllegalArgumentException(
                        "Consumer-only application must not advertise an RPC endpoint or manifest");
            }
        }
        attributes = copyAttributes(attributes);
    }

    /**
     * 返回本实例是否具备可连接的 Provider 角色和 RPC 端点。
     *
     * @return 只有 Provider/BOTH 返回 true
     */
    public boolean routableProvider() {
        return role.providesRpc();
    }

    /** 返回角色与属性数量，不输出非可信属性内容或外部身份字符串。 */
    @Override
    public String toString() {
        return "ApplicationInstance[role=" + role + ", routableProvider=" + routableProvider()
                + ", attributeCount=" + attributes.size() + "]";
    }

    private static Map<String, String> copyAttributes(Map<String, String> source) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        if (source.size() > MAX_ATTRIBUTES) {
            throw new IllegalArgumentException("Too many application attributes");
        }
        Map<String, String> copy = new LinkedHashMap<>();
        source.forEach((key, value) -> {
            String safeKey = requirePrintable(key, "attribute key", MAX_ATTRIBUTE_KEY_LENGTH);
            String safeValue = Objects.requireNonNull(value, "attribute value");
            if (safeValue.length() > MAX_ATTRIBUTE_VALUE_LENGTH || containsControl(safeValue)) {
                throw new IllegalArgumentException("Invalid application attribute value");
            }
            copy.put(safeKey, safeValue);
        });
        return Map.copyOf(copy);
    }

    private static String requirePrintable(String value, String field, int maximumLength) {
        Objects.requireNonNull(value, field);
        String normalized = value.trim();
        if (normalized.isEmpty() || normalized.length() > maximumLength || containsControl(normalized)) {
            throw new IllegalArgumentException("Invalid " + field);
        }
        return normalized;
    }

    private static boolean containsControl(String value) {
        for (int index = 0; index < value.length(); index++) {
            if (Character.isISOControl(value.charAt(index))) {
                return true;
            }
        }
        return false;
    }
}
