package com.peachsoft.otryx.transport;

import com.peachsoft.otryx.api.RpcTarget;
import java.time.Duration;
import java.util.concurrent.CompletionStage;

/**
 * RPC 客户端传输契约。
 *
 * @Author Ryan
 * @Version 1.0.0
 * @CreateTime 2026/9/23 10:51
 */
public interface RpcTransportClient extends AutoCloseable {

    /**
     * 向指定端点发送一个完整 RPC 帧。
     *
     * <p>Transport 负责在选定连接的 Event Loop 内分配 connection-local
     * Request ID 并写入 Header，Core 不维护跨连接全局序列。
     *
     * @param target 同时包含预期应用身份的服务目标
     * @param frame 已编码的完整协议帧，Request ID 应保留为 0
     * @param timeout 单次请求超时时间
     * @return 响应协议帧及同一连接的可信身份
     */
    CompletionStage<RpcTransportResponse> request(
            RpcTarget target,
            byte[] frame,
            Duration timeout);

    /**
     * 只读返回本对象实际生效的安全能力，不激活连接或线程。
     *
     * @return 未声明的实现返回 UNKNOWN
     */
    default RpcTransportSecurityDescriptor securityDescriptor() {
        return RpcTransportSecurityDescriptor.unknown();
    }

    /** 释放连接和传输层资源。 */
    @Override
    void close();
}
