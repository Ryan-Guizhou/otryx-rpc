package com.peachsoft.otryx.quickstart.api;

import com.peachsoft.otryx.api.OtryxRpcContract;
import com.peachsoft.otryx.api.OtryxRpcIdempotent;

/**
 * 滚动示例中可选导出的附加问候契约，用于形成真实不同的应用 Manifest。
 *
 * <p>默认 Provider 继续只导出共同的 GreetingService；启用附加服务的实例
 * 同时保留该共同契约。附加方法不写入业务状态，可用于安全的发现重试。
 *
 * @Author OpenAI
 * @Version 1.0.0-SNAPSHOT
 * @CreateTime 2026/10/19 03:04
 */
@OtryxRpcContract
public interface GreetingDetailsService {

    /**
     * 返回附加服务确已执行的固定示例结果。
     *
     * @param request 非空问候请求，不用作认证或授权身份
     * @return 消息为 DETAILS_OK 的非空响应
     */
    @OtryxRpcIdempotent
    GreetingReply details(GreetingRequest request);
}
