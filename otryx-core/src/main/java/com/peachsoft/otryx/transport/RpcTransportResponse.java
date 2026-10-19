package com.peachsoft.otryx.transport;

import com.peachsoft.otryx.api.RpcPeerIdentity;
import java.util.Objects;

/**
 * 将响应帧与产生该响应的同一条连接身份一起交付给 Core。
 *
 * <p>数组沿用独立帧的所有权约定，不声明共享缓冲区租约或零复制能力。
 *
 * @param frame 独立的完整响应帧
 * @param peer 同一实际连接的身份；开发 Transport 可以交付 anonymous
 * @Author OpenAI
 * @Version 1.0.0-SNAPSHOT
 * @CreateTime 2026/10/19 11:18
 */
public record RpcTransportResponse(byte[] frame, RpcPeerIdentity peer) {

    /** 不允许响应遗漏连接身份。 */
    public RpcTransportResponse {
        Objects.requireNonNull(frame, "frame");
        Objects.requireNonNull(peer, "peer");
    }
}
