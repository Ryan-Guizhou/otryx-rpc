package com.peachsoft.otryx.quickstart.provider;

import com.peachsoft.otryx.api.RpcIds;
import com.peachsoft.otryx.api.ServiceKey;
import com.peachsoft.otryx.core.RpcAuthorizer;
import com.peachsoft.otryx.quickstart.api.GreetingRequest;
import com.peachsoft.otryx.quickstart.api.GreetingService;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * mTLS 示例的最小权限授权配置，仅向指定叶子证书开放 Greeting 服务的 hello 方法。
 *
 * <p>Transport 负责验证证书链并提供可信指纹；示例策略不从 Metadata、Subject
 * 或远端地址推断授权身份。策略只比较内存中的固定值并返回已完成的异步结果。
 *
 * @Author OpenAI
 * @Version 1.0.0-SNAPSHOT
 * @CreateTime 2026/10/19 03:05
 */
@Configuration(proxyBeanMethods = false)
@Profile("mtls")
public class QuickstartMtlsAuthorizationConfiguration {

    private static final String ALLOWED_CLIENT_SHA256_PROPERTY =
            "otryx.rpc.quickstart.mtls.allowed-client-sha256";
    private static final String GREETING_VERSION = "1.0.0";
    private static final String GREETING_GROUP = "default";
    private static final String GREETING_METHOD = "hello";
    private static final Pattern CERTIFICATE_SHA256_PATTERN = Pattern.compile("[0-9a-f]{64}");
    private static final ServiceKey GREETING_SERVICE_KEY =
            new ServiceKey(GreetingService.class.getName(), GREETING_VERSION, GREETING_GROUP);

    /** 创建 mTLS 示例授权配置，不启动网络连接或后台任务。 */
    public QuickstartMtlsAuthorizationConfiguration() {
    }

    /**
     * 在启动时验证授权指纹并预计算服务方法标识。
     *
     * @param allowedClientSha256 非空且恰为 64 位小写十六进制的叶子证书 DER SHA-256
     * @return 不执行 I/O、不记录日志且立即完成判定的授权策略
     * @throws IllegalArgumentException 指纹缺失或格式不合法时抛出，不回显配置值
     * @throws NoSuchMethodException Greeting 契约未提供预期 hello 方法时阻止启动
     */
    @Bean
    public RpcAuthorizer quickstartMtlsAuthorizer(
            @Value("${" + ALLOWED_CLIENT_SHA256_PROPERTY + ":}") String allowedClientSha256)
            throws NoSuchMethodException {
        if (allowedClientSha256 == null
                || !CERTIFICATE_SHA256_PATTERN.matcher(allowedClientSha256).matches()) {
            throw new IllegalArgumentException(ALLOWED_CLIENT_SHA256_PROPERTY
                    + " must contain exactly 64 lowercase hexadecimal characters");
        }
        int helloMethodId = RpcIds.methodId(GreetingService.class.getMethod(GREETING_METHOD, GreetingRequest.class));
        return context -> CompletableFuture.completedFuture(
                context.peer().authenticated()
                        && allowedClientSha256.equals(context.peer().certificateSha256())
                        && GREETING_SERVICE_KEY.equals(context.serviceKey())
                        && helloMethodId == context.methodId());
    }
}
