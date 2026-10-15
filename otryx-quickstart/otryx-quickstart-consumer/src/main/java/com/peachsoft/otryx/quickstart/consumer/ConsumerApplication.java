package com.peachsoft.otryx.quickstart.consumer;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * OTRYX RPC Consumer 示例应用。
 *
 * @Author Ryan
 * @Version 1.0.0
 * @CreateTime 2026/9/28 11:36
 */
@SpringBootApplication
public class ConsumerApplication {

    /** 创建 Consumer 示例。 */
    public ConsumerApplication() {
    }

    /**
     * 启动 Consumer。
     *
     * @param args 命令行参数
     */
    public static void main(String[] args) {
        SpringApplication.run(ConsumerApplication.class, args);
    }
}
