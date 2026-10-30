package com.peachsoft.otryx.observability.micrometer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;

import com.peachsoft.otryx.api.RpcEndpoint;
import com.peachsoft.otryx.api.RpcStatus;
import com.peachsoft.otryx.api.ServiceKey;
import com.peachsoft.otryx.observability.RpcObserver;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.autoconfigure.metrics.MetricsAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.metrics.export.simple.SimpleMetricsExportAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.metrics.export.prometheus.
        PrometheusMetricsExportAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * 验证 Spring Boot 指标注册器与 OTRYX RPC Micrometer Observer 的实际自动配置顺序。
 *
 * @Author Ryan-Guizhou
 * @Version 1.0.0
 * @CreateTime 2026/10/30 10:00
 */
class OtryxRpcMicrometerAutoConfigurationTest {

    @Test
    void createsObserverAfterSimpleRegistry() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        OtryxRpcMicrometerAutoConfiguration.class,
                        SimpleMetricsExportAutoConfiguration.class,
                        MetricsAutoConfiguration.class))
                .run(context -> {
                    assertInstanceOf(SimpleMeterRegistry.class, context.getBean(MeterRegistry.class));
                    assertInstanceOf(MicrometerRpcObserver.class,
                            context.getBean("peachRpcMicrometerObserver", RpcObserver.class));
                });
    }

    @Test
    void recordsCallAndAttemptAfterPrometheusRegistry() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        OtryxRpcMicrometerAutoConfiguration.class,
                        PrometheusMetricsExportAutoConfiguration.class,
                        MetricsAutoConfiguration.class))
                .run(context -> {
                    MeterRegistry registry = context.getBean(MeterRegistry.class);
                    RpcObserver observer = context.getBean("peachRpcMicrometerObserver", RpcObserver.class);
                    assertInstanceOf(MicrometerRpcObserver.class, observer);
                    ServiceKey service = new ServiceKey("demo.Service", "1.0.0", "default");
                    observer.onClientAttemptCompleted(service, 1, new RpcEndpoint("127.0.0.1", 19090),
                            1, 1_000L, RpcStatus.OK, null);
                    observer.onClientCallCompleted(service, 1, 2_000L, RpcStatus.OK, null);
                    assertEquals(1L, registry.get("otryx.rpc.client.calls").timer().count());
                    assertEquals(1L, registry.get("otryx.rpc.client.attempts").timer().count());
                });
    }

    @Test
    void skipsObserverIfNoRegistry() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(OtryxRpcMicrometerAutoConfiguration.class))
                .run(context -> assertFalse(context.containsBean("peachRpcMicrometerObserver")));
    }

    @Test
    void applicationOverrideIsPreserved() {
        RpcObserver custom = new RpcObserver() { };
        new ApplicationContextRunner()
                .withBean(MeterRegistry.class, SimpleMeterRegistry::new)
                .withBean("peachRpcMicrometerObserver", RpcObserver.class, () -> custom)
                .withConfiguration(AutoConfigurations.of(OtryxRpcMicrometerAutoConfiguration.class))
                .run(context -> {
                    assertSame(custom, context.getBean("peachRpcMicrometerObserver", RpcObserver.class));
                    assertEquals(1, context.getBeansOfType(RpcObserver.class).size());
                });
    }
}
