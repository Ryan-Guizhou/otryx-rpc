package com.peachsoft.otryx.quickstart.provider;

import com.peachsoft.otryx.api.ApplicationKey;
import com.peachsoft.otryx.api.RpcAuthorizationDecision;
import com.peachsoft.otryx.api.RpcIds;
import com.peachsoft.otryx.api.ServiceKey;
import com.peachsoft.otryx.core.RpcAuthorizer;
import com.peachsoft.otryx.quickstart.api.GreetingRequest;
import com.peachsoft.otryx.quickstart.api.GreetingService;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * mTLS 示例的有限期授权配置，仅向指定消费者应用开放 Greeting 服务的 hello 方法。
 *
 * <p>Transport 将实际验证的叶证书绑定到完整应用身份；策略只比较该身份、服务和方法，
 * 不从 Metadata、Subject、指纹参数或远端地址推断权限，不执行 I/O 或创建后台任务。
 *
 * @Author OpenAI
 * @Version 1.0.0-SNAPSHOT
 * @CreateTime 2026/10/19 03:05
 */
@Configuration(proxyBeanMethods = false)
@Profile("mtls")
@ConditionalOnProperty(prefix = "otryx.rpc.quickstart.mtls", name = "authorization-enabled", matchIfMissing = true)
public class QuickstartMtlsAuthorizationConfiguration {

    private static final String ALLOWED_NAMESPACE_PROPERTY =
            "otryx.rpc.quickstart.mtls.allowed-consumer.namespace";
    private static final String ALLOWED_APPLICATION_PROPERTY =
            "otryx.rpc.quickstart.mtls.allowed-consumer.application-name";
    private static final String POLICY_VALID_UNTIL_PROPERTY = "otryx.rpc.quickstart.mtls.policy-valid-until";
    private static final String GREETING_VERSION = "1.0.0";
    private static final String GREETING_GROUP = "default";
    private static final String GREETING_METHOD = "hello";
    private static final ServiceKey GREETING_SERVICE_KEY =
            new ServiceKey(GreetingService.class.getName(), GREETING_VERSION, GREETING_GROUP);

    /** 创建 mTLS 示例授权配置，不启动网络连接或后台任务。 */
    public QuickstartMtlsAuthorizationConfiguration() {
    }

    /**
     * 在启动时校验允许的应用和有限策略期限，预计算完整服务方法标识。
     *
     * <p>不同叶证书可由部署绑定到同一应用以进行轮换；许可不超过原请求给出的上界。
     * 已过期的有限策略可用于拒绝场景，此时所有请求均由策略或 Core 的执行边界拒绝。
     *
     * @param allowedNamespace 明确允许的消费者命名空间
     * @param allowedApplication 明确允许的消费者应用名
     * @param policyValidUntil 可表示 epoch 毫秒的有限 ISO-8601 失效时刻
     * @return 只比较内存值、立即完成且具有有限期限的授权策略
     * @throws IllegalArgumentException 配置缺失或格式不合法时抛出，不回显值或原始原因
     * @throws NoSuchMethodException Greeting 契约未提供预期 hello 方法时阻止启动
     */
    @Bean
    public RpcAuthorizer quickstartMtlsAuthorizer(
            @Value("${" + ALLOWED_NAMESPACE_PROPERTY + ":}") String allowedNamespace,
            @Value("${" + ALLOWED_APPLICATION_PROPERTY + ":}") String allowedApplication,
            @Value("${" + POLICY_VALID_UNTIL_PROPERTY + ":}") String policyValidUntil)
            throws NoSuchMethodException {
        ApplicationKey allowedConsumer;
        Instant policyExpiry;
        try {
            allowedConsumer = new ApplicationKey(allowedNamespace, allowedApplication);
            policyExpiry = Instant.parse(policyValidUntil);
            policyExpiry.toEpochMilli();
        } catch (RuntimeException error) {
            throw new IllegalArgumentException("Invalid Quickstart mTLS allowed-consumer or policy-valid-until");
        }
        int helloMethodId = RpcIds.methodId(GreetingService.class.getMethod(GREETING_METHOD, GreetingRequest.class));
        return context -> {
            Instant validUntil = policyExpiry.isBefore(context.maximumValidUntil())
                    ? policyExpiry : context.maximumValidUntil();
            boolean permitted = context.peer().authenticated()
                    && context.peer().application().filter(allowedConsumer::equals).isPresent()
                    && GREETING_SERVICE_KEY.equals(context.serviceKey())
                    && helloMethodId == context.methodId()
                    && context.receivedAt().isBefore(validUntil);
            return CompletableFuture.completedFuture(permitted
                    ? RpcAuthorizationDecision.allowUntil(validUntil) : RpcAuthorizationDecision.deny());
        };
    }
}
