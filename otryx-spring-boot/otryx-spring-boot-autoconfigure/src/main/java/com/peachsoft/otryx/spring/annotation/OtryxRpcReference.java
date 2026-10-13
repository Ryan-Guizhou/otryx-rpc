package com.peachsoft.otryx.spring.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标记需要注入 OTRYX RPC Consumer 代理的字段。
 *
 * @Author Ryan
 * @Version 1.0.0
 * @CreateTime 2026/9/23 10:51
 */
@Documented
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
public @interface OtryxRpcReference {

    /**
     * 服务版本。
     *
     * @return 服务版本
     */
    String version() default "";

    /**
     * 服务分组。
     *
     * @return 服务分组
     */
    String group() default "default";

    /**
     * 该 Consumer 引用默认调用超时；0 表示沿用全局 Client 默认值。
     *
     * <p>单次 RpcCallOptions 和契约方法 OtryxRpcTimeout 优先于本设置。
     *
     * @return 正数超时毫秒数或 0
     */
    long timeoutMillis() default 0L;
}
