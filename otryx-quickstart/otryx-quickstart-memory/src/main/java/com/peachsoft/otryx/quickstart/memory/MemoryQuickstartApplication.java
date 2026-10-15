package com.peachsoft.otryx.quickstart.memory;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 无外部注册中心的进程内 RPC 快速入门应用。
 *
 * @Author Ryan
 * @Version 1.0.0-SNAPSHOT
 * @CreateTime 2026/10/15 14:41
 */
@SpringBootApplication
public class MemoryQuickstartApplication {

    /** 创建快速入门应用。 */
    public MemoryQuickstartApplication() {
    }

    /**
     * 启动真实 RPC Provider 和 Consumer。
     *
     * @param args 启动参数
     */
    public static void main(String[] args) {
        SpringApplication.run(MemoryQuickstartApplication.class, args);
    }
}
