package com.peachsoft.otryx.quickstart.memory;

import com.peachsoft.otryx.quickstart.api.GreetingReply;
import com.peachsoft.otryx.quickstart.api.GreetingRequest;
import com.peachsoft.otryx.quickstart.api.GreetingService;
import com.peachsoft.otryx.spring.annotation.OtryxRpcService;

/**
 * 进程内 RPC Provider；演示按接口暴露真实 RPC 服务。
 *
 * @Author Ryan
 * @Version 1.0.0-SNAPSHOT
 * @CreateTime 2026/10/15 14:41
 */
@OtryxRpcService(interfaceClass = GreetingService.class, version = "1.0.0")
public class MemoryGreetingServiceImpl implements GreetingService {

    /** 创建进程内服务。 */
    public MemoryGreetingServiceImpl() {
    }

    /**
     * 返回问候响应。
     *
     * @param request 问候请求
     * @return RPC 传输后的问候内容
     */
    @Override
    public GreetingReply hello(GreetingRequest request) {
        return new GreetingReply("Hello, " + request.name() + "!");
    }
}
