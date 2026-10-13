package com.peachsoft.otryx.api;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 在服务契约方法上声明 Consumer 预算，优先级低于单次 Scope、高于 Client 默认。
 *
 * <p>只控制 Consumer 发出的逻辑调用截止时间；同一调用的所有重试共享预算。
 *
 * @Author OpenAI
 * @Version 1.0.0-SNAPSHOT
 * @CreateTime 2026/10/13 16:10
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface OtryxRpcTimeout {

    /**
     * 方法级默认 Deadline 预算，以毫秒计。
     *
     * @return 正整数超时毫秒数
     */
    long millis();
}
