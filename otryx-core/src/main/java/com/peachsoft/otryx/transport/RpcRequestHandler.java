package com.peachsoft.otryx.transport;

import com.peachsoft.otryx.api.RpcEndpoint;
import com.peachsoft.otryx.api.RpcPeerIdentity;
import java.util.concurrent.CompletionStage;

/**
 * Provider 收到完整协议帧后的异步处理器。
 *
 * @Author Ryan
 * @Version 1.0.0
 * @CreateTime 2026/9/23 10:51
 */
@FunctionalInterface
public interface RpcRequestHandler {

    /**
     * 处理来自远端连接的完整 RPC 帧。
     *
     * @param remote 远端地址
     * @param frame 完整协议帧字节
     * @return 响应协议帧
     */
    CompletionStage<byte[]> handle(RpcEndpoint remote, byte[] frame);

    /**
     * 接收经过 Transport 验证的对端身份。
     *
     * <p>原有自定义 Transport Handler 可保持二参数协议；当 Provider
     * 需要认证授权时，Transport 必须覆盖本调用并传递真实握手身份。
     *
     * @param remote 对端地址
     * @param peer 可信握手绑定的身份，明文和单向 TLS 为匿名
     * @param frame 完整协议帧
     * @return 响应协议帧
     */
    default CompletionStage<byte[]> handle(
            RpcEndpoint remote, RpcPeerIdentity peer, byte[] frame) {
        return handle(remote, frame);
    }
}
