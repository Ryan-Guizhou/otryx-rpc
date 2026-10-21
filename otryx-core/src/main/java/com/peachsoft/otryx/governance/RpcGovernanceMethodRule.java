package com.peachsoft.otryx.governance;

import java.time.Duration;

/**
 * 方法级治理的不可变收紧规则；零值表示未配置该项。
 *
 * <p>远程规则只能收紧已配置本地 Timeout/Circuit/Quota，不接触认证、Codec 或 Frame 上限。
 *
 * @param maxTimeout 最大方法超时，null 表示不限制
 * @param maxConcurrent 允许的同时执行上限；0 表示未配置
 * @param circuitFailures 允许的熔断连续失败阈值；0 表示不启用治理熔断
 * @param circuitOpenDuration 熔断开启时长，非零阈值时必需
 * @Author OpenAI
 * @Version 1.0.0-SNAPSHOT
 * @CreateTime 2026/10/21 17:30
 */
public record RpcGovernanceMethodRule(
        Duration maxTimeout,
        int maxConcurrent,
        int circuitFailures,
        Duration circuitOpenDuration) {
    private static final Duration MAX_TIMEOUT = Duration.ofDays(1);
    private static final int MAX_CONCURRENT = 32768;
    private static final int MAX_CIRCUIT_FAILURES = 100000;

    /** 拒绝负数、无界超时和不完整熔断规则。 */
    public RpcGovernanceMethodRule {
        if (maxTimeout != null && (maxTimeout.isZero() || maxTimeout.isNegative()
                || maxTimeout.compareTo(MAX_TIMEOUT) > 0)) {
            throw new IllegalArgumentException("Invalid governance timeout");
        }
        if (maxConcurrent < 0 || maxConcurrent > MAX_CONCURRENT
                || circuitFailures < 0 || circuitFailures > MAX_CIRCUIT_FAILURES) {
            throw new IllegalArgumentException("Invalid governance method limit");
        }
        if ((circuitFailures == 0) != (circuitOpenDuration == null)) {
            throw new IllegalArgumentException("Incomplete governance circuit rule");
        }
        if (circuitOpenDuration != null && (circuitOpenDuration.isZero()
                || circuitOpenDuration.isNegative()
                || circuitOpenDuration.compareTo(MAX_TIMEOUT) > 0)) {
            throw new IllegalArgumentException("Invalid governance circuit duration");
        }
    }
}
