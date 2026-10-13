package com.peachsoft.otryx.examples.provider;

import com.peachsoft.otryx.core.RpcCallContext;
import com.peachsoft.otryx.core.RpcCallOptions;
import com.peachsoft.otryx.core.RpcCallScope;
import com.peachsoft.otryx.examples.api.ExampleDeadlineMetadata;
import com.peachsoft.otryx.examples.api.GreetingReply;
import com.peachsoft.otryx.examples.api.GreetingRequest;
import com.peachsoft.otryx.examples.api.GreetingService;
import com.peachsoft.otryx.examples.api.RelayGreetingService;
import com.peachsoft.otryx.spring.annotation.OtryxRpcReference;
import com.peachsoft.otryx.spring.annotation.OtryxRpcService;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

/**
 * A→B→C 三进程链式 E2E 中的 B 节点：恢复上游预算后调用 C。
 *
 * @Author OpenAI
 * @Version 1.0.0-SNAPSHOT
 * @CreateTime 2026/10/13 17:03
 */
@OtryxRpcService(interfaceClass = RelayGreetingService.class, version = "1.0.0")
@ConditionalOnProperty(prefix = "otryx.rpc.example", name = "role", havingValue = "relay")
public class RelayGreetingServiceImpl implements RelayGreetingService {

    private static final long MAX_RELAY_DELAY_MILLIS = 1_000L;

    /** 创建用于独立 JVM 截止时间链路验证的中间节点服务。 */
    public RelayGreetingServiceImpl() {
    }

    @OtryxRpcReference(version = "1.0.0")
    private GreetingService downstream;

    @Value("${otryx.rpc.example.relay-delay-millis:0}")
    private long relayDelayMillis;

    /**
     * 原样携带受限 Metadata 与父级剩余时限，不能为下游重新分配完整超时。
     *
     * @param request 业务请求
     * @return 下游响应
     */
    @Override
    public GreetingReply hello(GreetingRequest request) {
        if (RpcCallContext.current() == null) {
            throw new IllegalStateException("Inbound RPC call context was not restored");
        }
        if (relayDelayMillis < 0L || relayDelayMillis > MAX_RELAY_DELAY_MILLIS) {
            throw new IllegalArgumentException("Relay delay is out of range");
        }
        if (relayDelayMillis > 0L) {
            try {
                Thread.sleep(relayDelayMillis);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Relay request interrupted", error);
            }
        }
        try (RpcCallScope ignored = RpcCallContext.open(
                new RpcCallOptions(null, Map.of(
                        ExampleDeadlineMetadata.RELAY_KEY,
                        ExampleDeadlineMetadata.RELAY_VALUE)))) {
            return downstream.hello(request);
        }
    }
}
