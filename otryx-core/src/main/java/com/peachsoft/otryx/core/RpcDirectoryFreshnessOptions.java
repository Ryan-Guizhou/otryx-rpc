package com.peachsoft.otryx.core;

import java.time.Duration;
import java.util.Objects;

/**
 * Consumer 服务目录的 Last-Known-Good 最大陈旧时长配置。
 *
 * <p>时长必须严格为正，默认 30 秒。仅在应用控制面明确报告同步失败后
 * 开始计时。单纯没有 Watch 事件
 * 不属于失败。不能将该时间理解为外部 Registry 的全局 revision。
 *
 * @param maxStaleness 最大同步失效后的可容忍陈旧时间，必须为正且可转换为纳秒
 *
 * @Author OpenAI
 * @Version 1.0.0-SNAPSHOT
 * @CreateTime 2026/10/13 17:03
 */
public record RpcDirectoryFreshnessOptions(Duration maxStaleness) {

    /** 应用目录同步失败后最多保留 30 秒已验证的端点。 */
    public static final RpcDirectoryFreshnessOptions DEFAULT =
            new RpcDirectoryFreshnessOptions(Duration.ofSeconds(30));

    /** 验证配置时长不会超出本地单调时钟可比较的范围。 */
    public RpcDirectoryFreshnessOptions {
        Objects.requireNonNull(maxStaleness, "maxStaleness");
        if (maxStaleness.isZero() || maxStaleness.isNegative()) {
            throw new IllegalArgumentException("maxStaleness must be positive");
        }
        try {
            maxStaleness.toNanos();
        } catch (ArithmeticException overflow) {
            throw new IllegalArgumentException(
                    "maxStaleness must fit a nanosecond duration", overflow);
        }
    }

}
