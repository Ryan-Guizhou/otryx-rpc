package com.peachsoft.otryx.examples.provider;

import com.peachsoft.otryx.core.RpcCallContext;
import com.peachsoft.otryx.examples.api.ExampleDeadlineMetadata;
import com.peachsoft.otryx.examples.api.GreetingReply;
import com.peachsoft.otryx.examples.api.GreetingRequest;
import com.peachsoft.otryx.examples.api.GreetingService;
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
@ConditionalOnProperty(prefix = "otryx.rpc.example", name = "role",
        havingValue = "leaf", matchIfMissing = true)
public class GreetingServiceImpl implements GreetingService {

    /** 创建示例服务实现。 */
    public GreetingServiceImpl() {
    }

    @Override
    public GreetingReply hello(GreetingRequest request) {
        RpcCallContext context = RpcCallContext.current();
        if (context != null && ExampleDeadlineMetadata.E2E_VALUE.equals(
                context.metadata().get(ExampleDeadlineMetadata.E2E_KEY))) {
            if (!ExampleDeadlineMetadata.RELAY_VALUE.equals(
                    context.metadata().get(ExampleDeadlineMetadata.RELAY_KEY))
                    || !ExampleDeadlineMetadata.TENANT_VALUE.equals(
                    context.metadata().get(ExampleDeadlineMetadata.TENANT_KEY))) {
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
