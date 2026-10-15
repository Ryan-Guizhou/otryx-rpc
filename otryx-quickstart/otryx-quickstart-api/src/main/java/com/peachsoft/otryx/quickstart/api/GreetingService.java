package com.peachsoft.otryx.quickstart.api;

import com.peachsoft.otryx.api.OtryxRpcContract;
import com.peachsoft.otryx.api.OtryxRpcIdempotent;

/**
 * 示例问候 RPC 契约。
 *
 * @Author Ryan
 * @Version 1.0.0
 * @CreateTime 2026/9/28 11:36
 */
@OtryxRpcContract
public interface GreetingService {

    /**
     * 返回问候结果。
     *
     * @param request 问候请求
     * @return 问候响应
     */
    @OtryxRpcIdempotent
    GreetingReply hello(GreetingRequest request);
}
