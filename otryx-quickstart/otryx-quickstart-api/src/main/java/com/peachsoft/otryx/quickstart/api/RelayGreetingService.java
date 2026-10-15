package com.peachsoft.otryx.quickstart.api;

import com.peachsoft.otryx.api.OtryxRpcContract;
import com.peachsoft.otryx.api.OtryxRpcIdempotent;

/**
 * 三独立 JVM 链式调用中间节点对外暴露的 RPC 契约。
 *
 * @Author OpenAI
 * @Version 1.0.0-SNAPSHOT
 * @CreateTime 2026/10/13 17:03
 */
@OtryxRpcContract
public interface RelayGreetingService {

    /**
     * 经中间 Provider 转发问候请求。
     *
     * @param request 原始业务请求
     * @return 来自最下游 Provider 的结果
     */
    @OtryxRpcIdempotent
    GreetingReply hello(GreetingRequest request);
}
