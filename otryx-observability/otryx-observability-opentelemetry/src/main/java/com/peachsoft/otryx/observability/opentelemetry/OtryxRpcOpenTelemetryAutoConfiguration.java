package com.peachsoft.otryx.observability.opentelemetry;

import io.opentelemetry.api.OpenTelemetry;
import com.peachsoft.otryx.observability.RpcTracingBridge;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * OTRYX RPC OpenTelemetry 自动配置。
 *
 * @Author Ryan
 * @Version 1.0.0
 * @CreateTime 2026/9/29 14:59
 */
@AutoConfiguration(
        afterName = {
                "org.springframework.boot.actuate.autoconfigure.opentelemetry.OpenTelemetryAutoConfiguration"
        })
@ConditionalOnClass(OpenTelemetry.class)
public class OtryxRpcOpenTelemetryAutoConfiguration {

    /** 创建自动配置。 */
    public OtryxRpcOpenTelemetryAutoConfiguration() {
    }

    /**
     * 创建 OpenTelemetry Trace Bridge。
     *
     * @param openTelemetry OpenTelemetry
     * @return Trace Bridge
     */
    @Bean
    @ConditionalOnBean(OpenTelemetry.class)
    @ConditionalOnMissingBean(RpcTracingBridge.class)
    public RpcTracingBridge peachRpcTracingBridge(
            OpenTelemetry openTelemetry) {
        return new OpenTelemetryRpcTracingBridge(
                openTelemetry);
    }
}
