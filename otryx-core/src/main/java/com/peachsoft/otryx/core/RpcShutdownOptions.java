package com.peachsoft.otryx.core;

import java.time.Duration;
import java.util.Objects;

/**
 * RPC Provider 关闭的单一总耗时预算。
 *
 * <p>预算覆盖控制面注销、Transport 排空和 Executor 等待；不同阶段共享
 * 同一个单调时钟截止点，不会按服务数叠加独立超时。
 *
 * @param totalTimeout 从首次调用 shutdown 开始计算的最大等待时间
 * @Author OpenAI
 * @Version 1.0.0-SNAPSHOT
 * @CreateTime 2026/10/12 15:32
 */
public record RpcShutdownOptions(Duration totalTimeout) {

    /** 未显式配置关闭策略时使用的总预算。 */
    public static final RpcShutdownOptions DEFAULT =
            new RpcShutdownOptions(Duration.ofSeconds(35));

    /** 验证有限且非零的总预算。 */
    public RpcShutdownOptions {
        Objects.requireNonNull(totalTimeout, "totalTimeout");
        if (totalTimeout.isZero()
                || totalTimeout.isNegative()
                || totalTimeout.compareTo(Duration.ofDays(1)) > 0) {
            throw new IllegalArgumentException(
                    "totalTimeout must be positive and no more than one day");
        }
    }
}
