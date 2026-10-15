package com.peachsoft.otryx.quickstart.api;

import java.io.Serializable;

/**
 * 问候请求。
 *
 * @param name 问候对象名称
 *
 * @Author Ryan
 * @Version 1.0.0
 * @CreateTime 2026/9/28 11:36
 */
public record GreetingRequest(String name) implements Serializable {
}
