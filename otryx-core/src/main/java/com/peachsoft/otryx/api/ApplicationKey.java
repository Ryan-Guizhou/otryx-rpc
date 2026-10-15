package com.peachsoft.otryx.api;

import java.util.Objects;

/**
 * 应用级注册身份：命名空间与应用名共同构成服务发现的隔离边界。
 *
 * @param namespace 命名空间，不允许为空
 * @param applicationName 应用名称，不允许为空
 *
 * @Author OTRYX
 * @Version 1.0.0
 * @CreateTime 2026/10/15 17:48
 */
public record ApplicationKey(String namespace, String applicationName) {

    private static final int MAX_NAME_LENGTH = 128;

    /** 规范化应用名称并拒绝无法安全编码的身份。 */
    public ApplicationKey {
        namespace = normalize(namespace, "namespace");
        applicationName = normalize(applicationName, "applicationName");
    }

    private static String normalize(String value, String field) {
        Objects.requireNonNull(value, field);
        String normalized = value.trim();
        if (normalized.isEmpty() || normalized.length() > MAX_NAME_LENGTH) {
            throw new IllegalArgumentException(field + " length is invalid");
        }
        for (int index = 0; index < normalized.length(); index++) {
            char character = normalized.charAt(index);
            boolean allowed = character >= 'a' && character <= 'z'
                    || character >= 'A' && character <= 'Z'
                    || character >= '0' && character <= '9'
                    || character == '-' || character == '_' || character == '.';
            if (!allowed) {
                throw new IllegalArgumentException(field + " contains invalid characters");
            }
        }
        return normalized;
    }
}
