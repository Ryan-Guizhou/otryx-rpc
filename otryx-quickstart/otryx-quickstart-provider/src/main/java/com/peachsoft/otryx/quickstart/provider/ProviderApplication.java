package com.peachsoft.otryx.quickstart.provider;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * OTRYX RPC Provider 示例应用。
 *
 * @Author Ryan
 * @Version 1.0.0
 * @CreateTime 2026/9/28 11:36
 */
@SpringBootApplication
public class ProviderApplication {

    /** 创建 Provider 示例。 */
    public ProviderApplication() {
    }

    /**
     * 启动 Provider。
     *
     * @param args 命令行参数
     */
    public static void main(String[] args) {
        SpringApplication.run(ProviderApplication.class, args);
    }
}
