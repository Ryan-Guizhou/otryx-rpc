package com.peachsoft.otryx.observability.opentelemetry;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.opentelemetry.api.OpenTelemetry;
import com.peachsoft.otryx.observability.RpcTracingBridge;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.autoconfigure.opentelemetry.OpenTelemetryAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * 验证 Spring Boot OpenTelemetry 与 OTRYX RPC Trace Bridge 的自动配置顺序。
 *
 * @Author Ryan
 * @Version 1.0.0
 * @CreateTime 2026/10/28 09:35
 */
class OtryxRpcOpenTelemetryAutoConfigurationTest {

    @Test
    void shouldCreateTracingBridgeAfterBootRegistersOpenTelemetry() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        OpenTelemetryAutoConfiguration.class,
                        OtryxRpcOpenTelemetryAutoConfiguration.class))
                .run(context -> {
                    assertTrue(context.containsBean("openTelemetry"));
                    assertNotNull(context.getBean(OpenTelemetry.class));
                    assertInstanceOf(
                            OpenTelemetryRpcTracingBridge.class,
                            context.getBean(RpcTracingBridge.class));
                });
    }

    @Test
    void shouldSkipTracingBridgeWhenOpenTelemetryIsUnavailable() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        OtryxRpcOpenTelemetryAutoConfiguration.class))
                .run(context -> {
                    assertFalse(context.containsBean("peachRpcTracingBridge"));
                    assertFalse(context.getBeansOfType(RpcTracingBridge.class).size() > 0);
                });
    }

    @Test
    void shouldBackOffWhenApplicationProvidesCustomTracingBridge() {
        RpcTracingBridge customBridge = new RpcTracingBridge() { };

        new ApplicationContextRunner()
                .withBean("customTracingBridge", RpcTracingBridge.class, () -> customBridge)
                .withConfiguration(AutoConfigurations.of(
                        OpenTelemetryAutoConfiguration.class,
                        OtryxRpcOpenTelemetryAutoConfiguration.class))
                .run(context -> assertSame(
                        customBridge,
                        context.getBean(RpcTracingBridge.class)));
    }
}
