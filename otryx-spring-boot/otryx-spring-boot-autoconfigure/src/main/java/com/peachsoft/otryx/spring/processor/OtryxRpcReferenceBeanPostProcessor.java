package com.peachsoft.otryx.spring.processor;

import com.peachsoft.otryx.core.OtryxRpcClient;
import com.peachsoft.otryx.spring.annotation.OtryxRpcReference;
import com.peachsoft.otryx.spring.runtime.OtryxRpcRuntimeCoordinator;
import java.lang.reflect.Field;
import java.time.Duration;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.InstantiationAwareBeanPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.PriorityOrdered;
import org.springframework.util.ReflectionUtils;

/**
 * 将 {@link OtryxRpcReference} 字段替换为 RPC Consumer 代理。
 *
 * @Author Ryan
 * @Version 1.0.0
 * @CreateTime 2026/9/23 10:51
 */
public final class OtryxRpcReferenceBeanPostProcessor
        implements InstantiationAwareBeanPostProcessor, PriorityOrdered {

    private final ObjectProvider<OtryxRpcRuntimeCoordinator> coordinatorProvider;

    /**
     * 创建 Consumer 引用注入处理器。
     *
     * @param coordinatorProvider 运行时协调器延迟提供器
     */
    public OtryxRpcReferenceBeanPostProcessor(
            ObjectProvider<OtryxRpcRuntimeCoordinator> coordinatorProvider) {
        this.coordinatorProvider = coordinatorProvider;
    }

    @Override
    public Object postProcessBeforeInitialization(
            Object bean,
            String beanName) throws BeansException {
        ReflectionUtils.doWithFields(
                bean.getClass(),
                field -> injectReference(bean, beanName, field));
        return bean;
    }

    private void injectReference(
            Object bean,
            String beanName,
            Field field) {
        OtryxRpcReference reference = field.getAnnotation(OtryxRpcReference.class);
        if (reference == null) {
            return;
        }
        if (!field.getType().isInterface()) {
            throw new IllegalStateException(
                    "@OtryxRpcReference requires an interface field: bean="
                            + beanName
                            + ", field="
                            + field.getName()
                            + ", type="
                            + field.getType().getName());
        }
        OtryxRpcClient client;
        try {
            client = coordinatorProvider.getObject().client();
        } catch (IllegalStateException error) {
            throw new IllegalStateException(
                    "Failed to initialize @OtryxRpcReference: bean="
                            + beanName
                            + ", field="
                            + field.getName()
                            + ", interface="
                            + field.getType().getName(),
                    error);
        }
        if (reference.timeoutMillis() < 0L
                || reference.timeoutMillis() > Duration.ofDays(1).toMillis()) {
            throw new IllegalStateException(
                    "Invalid @OtryxRpcReference.timeoutMillis: field="
                            + field.getName());
        }
        Duration referenceTimeout = reference.timeoutMillis() == 0L
                ? null
                : Duration.ofMillis(reference.timeoutMillis());
        Object proxy = client.refer(
                field.getType(),
                reference.version(),
                reference.group(),
                referenceTimeout);
        ReflectionUtils.makeAccessible(field);
        ReflectionUtils.setField(field, bean, proxy);
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 100;
    }
}
