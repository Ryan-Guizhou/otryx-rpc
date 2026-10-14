package com.peachsoft.otryx.core;

import java.time.Duration;
import java.util.Objects;

/**
 * Consumer 服务目录的 Last-Known-Good 最大陈旧时长配置。
 *
 * <p>零时长表示保留原有无限 LKG 可用行为；配置正值后，
 * 仅在 Registry 显式报告同步不健康后开始计时。单纯没有 Watch 事件
 * 不属于失败。不能将该时间理解为外部 Registry 的全局 revision。
 *
 * @param maxStaleness 最大同步失效后的可容忍陈旧时间，零表示不限制
 *
 * @Author OpenAI
 * @Version 1.0.0-SNAPSHOT
 * @CreateTime 2026/10/13 17:03
 */
public record RpcDirectoryFreshnessOptions(Duration maxStaleness) {

    /** 保持原有 Last-Known-Good 不按本地时钟自动失效的默认配置。 */
    public static final RpcDirectoryFreshnessOptions UNBOUNDED =
            new RpcDirectoryFreshnessOptions(Duration.ZERO);

    /** 验证配置时长不会超出本地单调时钟可比较的范围。 */
    public RpcDirectoryFreshnessOptions {
        Objects.requireNonNull(maxStaleness, "maxStaleness");
        if (maxStaleness.isNegative()) {
            throw new IllegalArgumentException("maxStaleness must not be negative");
        }
        try {
            maxStaleness.toNanos();
        } catch (ArithmeticException overflow) {
            throw new IllegalArgumentException(
                    "maxStaleness must fit a nanosecond duration", overflow);
        }
    }

    /**
     * 是否明确限制同步失效后的陈旧时长。
     *
     * @return true 表示过期后拒绝使用 LKG
     */
    public boolean limited() {
        return !maxStaleness.isZero();
    }
}
