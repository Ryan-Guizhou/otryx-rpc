package com.peachsoft.otryx.api;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;

/**
 * 单次逻辑 RPC 调用选项，不包含业务载荷或认证凭据。
 *
 * <p>timeout 为 null 时沿用方法或 Client 默认超时；即使单次指定了更长超时，
 * 也无法扩张父调用现有的剩余单调预算。metadata 只用于用户声明的透传值，
 * 不能覆盖框架管理的 Deadline 字段或声明认证身份。
 *
 * @param timeout 本次调用超时覆盖，为 null 表示不覆盖
 * @param metadata 只读扩展 Metadata
 * @Author OpenAI
 * @Version 1.0.0-SNAPSHOT
 * @CreateTime 2026/10/13 16:10
 */
public record RpcCallOptions(Duration timeout, Map<String, String> metadata) {

    /** 无超时覆盖和额外 Metadata 的默认选项。 */
    public static final RpcCallOptions DEFAULT = new RpcCallOptions(null, Map.of());

    private static final String EPOCH_KEY = "deadlineEpochMillis";
    private static final String BUDGET_KEY = "timeoutBudgetMillis";

    /** 校验调用选项并阻止框架保留 Metadata 被用户覆盖。 */
    public RpcCallOptions {
        if (timeout != null && (timeout.isZero() || timeout.isNegative()
                || timeout.compareTo(Duration.ofDays(1)) > 0)) {
            throw new IllegalArgumentException(
                    "RPC call timeout must be positive and at most one day");
        }
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        for (Map.Entry<String, String> entry : metadata.entrySet()) {
            String key = Objects.requireNonNull(entry.getKey(), "metadata key");
            if (key.isBlank() || key.equals(EPOCH_KEY) || key.equals(BUDGET_KEY)
                    || key.contains("\n") || key.contains("=")) {
                throw new IllegalArgumentException("Invalid or reserved RPC metadata key");
            }
            if (entry.getValue().contains("\n") || entry.getValue().contains("\r")) {
                throw new IllegalArgumentException("Invalid RPC metadata value");
            }
        }
    }

    /**
     * 创建仅指定超时的选项。
     *
     * @param timeout 本次调用超时
     */
    public RpcCallOptions(Duration timeout) {
        this(timeout, Map.of());
    }
}
