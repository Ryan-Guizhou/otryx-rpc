package com.peachsoft.otryx.quickstart.memory;

import com.peachsoft.otryx.quickstart.api.GreetingReply;
import com.peachsoft.otryx.quickstart.api.GreetingRequest;
import com.peachsoft.otryx.quickstart.api.GreetingService;
import com.peachsoft.otryx.spring.annotation.OtryxRpcReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * 进程内 RPC Consumer；在 Provider 生命周期启动后验证请求与响应。
 *
 * @Author OTRYX RPC
 * @Version 1.0.0
 * @CreateTime 2026/10/15 14:00
 */
@Component
public class MemoryGreetingRunner implements ApplicationRunner {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(MemoryGreetingRunner.class);

    @OtryxRpcReference(version = "1.0.0")
    private GreetingService greetingService;

    /** 创建进程内调用入口。 */
    public MemoryGreetingRunner() {
    }

    /**
     * 启动后验证一次真实的 RPC 序列化、传输和解码往返。
     *
     * @param args 应用启动参数
     */
    @Override
    public void run(ApplicationArguments args) {
        GreetingReply response = greetingService.hello(
                new GreetingRequest("Quickstart"));
        if (!"Hello, Quickstart!".equals(response.message())) {
            throw new IllegalStateException("Unexpected Memory Quickstart RPC response");
        }
        LOGGER.info("Quickstart memory RPC succeeded: {}", response.message());
    }
}
