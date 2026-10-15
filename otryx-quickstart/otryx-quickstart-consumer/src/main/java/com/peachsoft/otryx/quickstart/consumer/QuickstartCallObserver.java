package com.peachsoft.otryx.quickstart.consumer;

import com.peachsoft.otryx.api.RpcStatus;
import com.peachsoft.otryx.api.ServiceKey;
import com.peachsoft.otryx.observability.RpcObserver;
import java.util.concurrent.atomic.LongAdder;
import org.springframework.stereotype.Component;

/**
 * 示例自定义 Observer，通过 Spring Bean 注入并独立累计 RPC 逻辑调用结果。
 *
 * <p>只执行有界的内存计数，不进行 IO 或记录原始异常内容。
 * 生产环境若使用慢速出口，应使用显式有界异步 Observer。
 *
 * @Author OpenAI
 * @Version 1.0.0-SNAPSHOT
 * @CreateTime 2026/10/14 16:00
 */
@Component
public final class QuickstartCallObserver implements RpcObserver {

    private final LongAdder successfulCalls = new LongAdder();
    private final LongAdder failedCalls = new LongAdder();

    /** 创建轻量自定义遥测示例。 */
    public QuickstartCallObserver() {
    }

    /**
     * 仅累计逻辑请求终态，不将 ServiceKey、请求或异常导出。
     *
     * @param serviceKey 服务标识
     * @param methodId 方法标识
     * @param durationNanos 总时长
     * @param status 最终状态
     * @param error 失败原因
     */
    @Override
    public void onClientCallCompleted(
            ServiceKey serviceKey,
            int methodId,
            long durationNanos,
            RpcStatus status,
            Throwable error) {
        if (status == RpcStatus.OK) {
            successfulCalls.increment();
        } else {
            failedCalls.increment();
        }
    }

    /**
     * 返回成功完成的逻辑调用数。
     *
     * @return 当前成功逻辑请求数
     */
    public long successfulCalls() {
        return successfulCalls.sum();
    }

    /**
     * 返回失败完成的逻辑调用数。
     *
     * @return 当前失败逻辑请求数
     */
    public long failedCalls() {
        return failedCalls.sum();
    }
}
