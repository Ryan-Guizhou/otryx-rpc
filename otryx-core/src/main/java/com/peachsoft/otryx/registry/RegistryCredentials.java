package com.peachsoft.otryx.registry;

import java.util.Objects;

/**
 * 保存单一种类的后端凭据；字符数组在输入和读取时均复制，描述不输出任何值。
 *
 * <p>适配器应在完成 SDK/Header 配置后尽力擦除自有临时副本；本对象不承诺擦除
 * 第三方 SDK 可能保留的 String，也不允许调用方修改共享配置的内部数组。
 *
 * @Author OpenAI
 * @Version 1.0.0-SNAPSHOT
 * @CreateTime 2026/10/19 11:22
 */
public final class RegistryCredentials {
    private static final int MAX_SECRET_CHARACTERS = 4096;
    private static final int MAX_USERNAME_CHARACTERS = 256;
    private final CredentialKind kind;
    private final String username;
    private final char[] password;
    private final char[] token;

    private RegistryCredentials(CredentialKind kind, String username, char[] password, char[] token) {
        this.kind = kind;
        this.username = username;
        this.password = password.clone();
        this.token = token.clone();
    }

    /**
     * 创建没有后端凭据的明确配置。
     *
     * @return 不含凭据的配置
     */
    public static RegistryCredentials none() {
        return new RegistryCredentials(CredentialKind.NONE, "", new char[0], new char[0]);
    }

    /**
     * 创建用户密码凭据，密码数组在返回前复制。
     *
     * @param username 非空用户名
     * @param password 非空、有限长度密码
     * @return 用户密码配置
     */
    public static RegistryCredentials userPassword(String username, char[] password) {
        Objects.requireNonNull(username, "username");
        if (username.isBlank() || username.length() > MAX_USERNAME_CHARACTERS) {
            throw new IllegalArgumentException("Invalid Registry credential username");
        }
        validateSecret(password);
        return new RegistryCredentials(CredentialKind.USER_PASSWORD, username, password, new char[0]);
    }

    /**
     * 创建 ACL Token 凭据，数组在返回前复制。
     *
     * @param token 非空、有限长度 ACL Token
     * @return ACL Token 配置
     */
    public static RegistryCredentials aclToken(char[] token) {
        validateSecret(token);
        return new RegistryCredentials(CredentialKind.ACL_TOKEN, "", new char[0], token);
    }

    /**
     * 明确选择客户端证书身份；Factory 仍须验证并安装实际密钥材料。
     *
     * @return 客户端证书认证意图
     */
    public static RegistryCredentials clientCertificate() {
        return new RegistryCredentials(CredentialKind.CLIENT_CERTIFICATE, "", new char[0], new char[0]);
    }

    /**
     * 返回凭据机制，不包含秘密。
     *
     * @return 唯一认证机制
     */
    public CredentialKind kind() {
        return kind;
    }

    /**
     * 返回用户密码机制的用户名，其他机制返回空字符串。
     *
     * @return 不可变用户名
     */
    public String username() {
        return username;
    }

    /**
     * 返回调用方自有的密码副本，其他机制返回空数组。
     *
     * @return 应在使用后尽力擦除的副本
     */
    public char[] password() {
        return password.clone();
    }

    /**
     * 返回调用方自有的 ACL Token 副本，其他机制返回空数组。
     *
     * @return 应在使用后尽力擦除的副本
     */
    public char[] token() {
        return token.clone();
    }

    /** 返回固定机制名称，隐藏用户名及所有凭据值。 */
    @Override
    public String toString() {
        return "RegistryCredentials[kind=" + kind + ']';
    }

    private static void validateSecret(char[] value) {
        Objects.requireNonNull(value, "secret");
        if (value.length == 0 || value.length > MAX_SECRET_CHARACTERS) {
            throw new IllegalArgumentException("Invalid Registry credential length");
        }
    }
}
