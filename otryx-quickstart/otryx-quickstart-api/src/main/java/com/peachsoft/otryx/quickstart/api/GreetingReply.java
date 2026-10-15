package com.peachsoft.otryx.quickstart.api;

import java.io.Serializable;

/**
 * 问候响应。
 *
 * @param message 问候结果
 *
 * @Author Ryan
 * @Version 1.0.0
 * @CreateTime 2026/9/28 11:36
 */
public record GreetingReply(String message) implements Serializable {
}
