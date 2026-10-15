package com.peachsoft.otryx.quickstart.provider;

import com.peachsoft.otryx.core.RpcCallContext;
import com.peachsoft.otryx.quickstart.api.QuickstartDeadlineMetadata;
import com.peachsoft.otryx.quickstart.api.GreetingReply;
import com.peachsoft.otryx.quickstart.api.GreetingRequest;
import com.peachsoft.otryx.quickstart.api.GreetingService;
import com.peachsoft.otryx.spring.annotation.OtryxRpcService;
import java.util.concurrent.TimeUnit;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

/**
 * 示例问候服务实现。
 *
 * @Author Ryan
 * @Version 1.0.0
 * @CreateTime 2026/9/28 11:36
 */
@OtryxRpcService(
        interfaceClass = GreetingService.class,
        version = "1.0.0")
@ConditionalOnProperty(prefix = "otryx.rpc.quickstart", name = "role",
        havingValue = "leaf", matchIfMissing = true)
public class GreetingServiceImpl implements GreetingService {

    /** 创建示例服务实现。 */
    public GreetingServiceImpl() {
    }

    @Override
    public GreetingReply hello(GreetingRequest request) {
        RpcCallContext context = RpcCallContext.current();
        if (context != null && QuickstartDeadlineMetadata.E2E_VALUE.equals(
                context.metadata().get(QuickstartDeadlineMetadata.E2E_KEY))) {
            if (!QuickstartDeadlineMetadata.RELAY_VALUE.equals(
                    context.metadata().get(QuickstartDeadlineMetadata.RELAY_KEY))
                    || !QuickstartDeadlineMetadata.TENANT_VALUE.equals(
                    context.metadata().get(QuickstartDeadlineMetadata.TENANT_KEY))) {
                throw new IllegalStateException("Relay context metadata was not preserved");
            }
            long remainingMillis = TimeUnit.NANOSECONDS.toMillis(context.remainingNanos());
            if (remainingMillis <= 0L || remainingMillis >= 5_000L) {
                throw new IllegalStateException("Downstream budget was not reduced");
            }
            return new GreetingReply("CHAIN_OK remainingMillis=" + remainingMillis);
        }
        return new GreetingReply(
                "Hello, " + request.name() + "!");
    }
}
