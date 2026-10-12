package com.peachsoft.otryx.api;

import java.lang.reflect.Type;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 稳定类型标识注册表。
 *
 * <p>用于在启动绑定阶段检测不同 Type 被映射到同一稳定 ID 的碰撞。
 *
 * @Author Ryan
 * @Version 1.0.0
 * @CreateTime 2026/9/30 11:16
 */
public final class RpcTypeRegistry {

    /** 创建空的稳定类型注册表。 */
    public RpcTypeRegistry() {
    }

    private final Map<Integer, String> types =
            new ConcurrentHashMap<>();

    /**
     * 注册一个契约类型。
     *
     * @param type Java Type
     * @return 稳定 Type ID
     */
    public int register(Type type) {
        String canonical = RpcTypeIds.canonicalName(type);
        int id = RpcTypeIds.typeId(type);
        String existing = types.putIfAbsent(id, canonical);
        if (existing != null && !existing.equals(canonical)) {
            throw new IllegalStateException(
                    "RPC type id collision: id="
                            + id
                            + ", existing="
                            + existing
                            + ", candidate="
                            + canonical);
        }
        return id;
    }

    /**
     * 批量注册方法参数/返回类型及可达 DTO 类型图。
     *
     * @param descriptor RPC 方法描述
     */
    public void register(RpcMethodDescriptor descriptor) {
        RpcContractTypeModel.reachableTypes(descriptor)
                .forEach(this::register);
    }

    /**
     * 返回当前注册快照。
     *
     * @return Type ID 到规范化名称的不可变映射
     */
    public Map<Integer, String> snapshot() {
        return Map.copyOf(types);
    }
}
