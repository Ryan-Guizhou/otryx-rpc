package com.peachsoft.otryx.transport;

import com.peachsoft.otryx.api.RpcEndpoint;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * RPC 服务端传输契约。
 *
 * @Author Ryan
 * @Version 1.0.0
 * @CreateTime 2026/9/23 10:51
 */
public interface RpcTransportServer extends AutoCloseable {

    /**
     * 启动传输服务端。
     *
     * @param bind 监听地址
     * @param handler 完整 RPC 帧处理器
     * @return 实际监听地址
     */
    CompletionStage<RpcEndpoint> start(RpcEndpoint bind, RpcRequestHandler handler);

    /**
     * 停止接收新请求并等待已接收请求完成。
     *
     * <p>默认实现用于兼容旧 Transport：直接关闭并立即完成。
     * 支持优雅排空的 Transport 应覆盖此方法。
     *
     * @param timeout 最大排空时间
     * @return 排空完成信号
     */
    default CompletionStage<Void> drain(Duration timeout) {
        close();
        return CompletableFuture.completedFuture(null);
    }

    /**
     * 只读返回本对象实际生效的安全能力，不激活监听或线程。
     *
     * @return 未声明的实现返回 UNKNOWN
     */
    default RpcTransportSecurityDescriptor securityDescriptor() {
        return RpcTransportSecurityDescriptor.unknown();
    }

    /** 关闭监听和传输层资源。 */
    @Override
    void close();
}
