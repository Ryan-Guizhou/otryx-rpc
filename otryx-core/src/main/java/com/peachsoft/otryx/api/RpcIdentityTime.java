package com.peachsoft.otryx.api;

import java.time.Instant;
import java.util.Objects;

/**
 * 身份值对象共用的有限时间校验，不维护时钟或全局状态。
 *
 * @Author OpenAI
 * @Version 1.0.0-SNAPSHOT
 * @CreateTime 2026/10/19 11:18
 */
final class RpcIdentityTime {
    private RpcIdentityTime() {
    }

    static Instant requireFinite(Instant value, String name) {
        Objects.requireNonNull(value, name);
        try {
            value.toEpochMilli();
        } catch (ArithmeticException failure) {
            throw new IllegalArgumentException(name + " must be a finite instant");
        }
        return value;
    }
}
