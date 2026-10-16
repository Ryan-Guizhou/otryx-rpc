package com.peachsoft.otryx.tests;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.netflix.eureka.server.EnableEurekaServer;

/**
 * 专用于 GitHub Actions 的真正 Spring Cloud Eureka Server。
 *
 * <p>该模块位于测试夹具目录，不属于 RPC Maven Reactor，也不是业务 Starter。
 *
 * @Author OpenAI
 * @Version 1.0.0-SNAPSHOT
 * @CreateTime 2026/10/16 18:09
 */
@SpringBootApplication
@EnableEurekaServer
public class EurekaServerFixture {

    /**
     * 启动真实 Eureka HTTP API，由外部脚本指定独立测试端口。
     *
     * @param args Spring Boot 启动参数
     */
    public static void main(String[] args) {
        SpringApplication.run(EurekaServerFixture.class, args);
    }
}
