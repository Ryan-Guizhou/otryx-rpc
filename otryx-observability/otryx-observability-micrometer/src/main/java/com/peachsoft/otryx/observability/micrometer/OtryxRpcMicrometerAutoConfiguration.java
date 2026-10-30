package com.peachsoft.otryx.observability.micrometer;

import io.micrometer.core.instrument.MeterRegistry;
import com.peachsoft.otryx.observability.RpcObserver;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * OTRYX RPC Micrometer 自动配置；必须在 Spring Boot MeterRegistry exporter 装配后评估条件。
 *
 * @Author Ryan
 * @Version 1.0.0
 * @CreateTime 2026/9/29 14:51
 */
@AutoConfiguration(afterName = {
        "org.springframework.boot.actuate.autoconfigure.metrics.MetricsAutoConfiguration",
        "org.springframework.boot.actuate.autoconfigure.metrics.export.simple.SimpleMetricsExportAutoConfiguration",
        "org.springframework.boot.actuate.autoconfigure.metrics.export.prometheus."
                + "PrometheusMetricsExportAutoConfiguration"
})
@ConditionalOnClass(MeterRegistry.class)
public class OtryxRpcMicrometerAutoConfiguration {

    /** 创建自动配置。 */
    public OtryxRpcMicrometerAutoConfiguration() {
    }

    /**
     * 创建 Micrometer Observer。
     *
     * @param registry MeterRegistry
     * @return RPC Observer
     */
    @Bean
    @ConditionalOnBean(MeterRegistry.class)
    @ConditionalOnMissingBean(
            name = "peachRpcMicrometerObserver")
    public RpcObserver peachRpcMicrometerObserver(
            MeterRegistry registry) {
        return new MicrometerRpcObserver(registry);
    }
}
