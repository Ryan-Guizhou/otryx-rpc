package com.peachsoft.otryx.registry;

import java.nio.file.Path;
import java.util.Objects;

/**
 * 引用本地客户端证书与私钥材料；真实内容由适配器有界读取、验证后安装。
 *
 * @Author OpenAI
 * @Version 1.0.0-SNAPSHOT
 * @CreateTime 2026/10/19 11:22
 */
public final class RegistryClientKeyMaterial {
    private static final int MAX_PASSWORD_CHARACTERS = 4096;
    private final Path certificateChain;
    private final Path privateKey;
    private final char[] privateKeyPassword;

    /**
     * 固化本地材料路径并复制可选私钥密码，不在构造时执行 IO。
     *
     * @param certificateChain 非 null 客户端证书链文件
     * @param privateKey 非 null 私钥文件
     * @param privateKeyPassword 可为空数组；null 表示没有密码
     */
    public RegistryClientKeyMaterial(Path certificateChain, Path privateKey, char[] privateKeyPassword) {
        this.certificateChain = Objects.requireNonNull(certificateChain, "certificateChain");
        this.privateKey = Objects.requireNonNull(privateKey, "privateKey");
        this.privateKeyPassword = privateKeyPassword == null ? new char[0] : privateKeyPassword.clone();
        if (this.privateKeyPassword.length > MAX_PASSWORD_CHARACTERS) {
            throw new IllegalArgumentException("Invalid Registry private key password length");
        }
    }

    /**
     * 返回客户端证书链路径。
     *
     * @return 本地证书路径
     */
    public Path certificateChain() {
        return certificateChain;
    }

    /**
     * 返回本地私钥路径，不读取其内容。
     *
     * @return 私钥路径
     */
    public Path privateKey() {
        return privateKey;
    }

    /**
     * 返回调用方自有密码副本，应在使用后尽力擦除。
     *
     * @return 密码副本或空数组
     */
    public char[] privateKeyPassword() {
        return privateKeyPassword.clone();
    }

    /** 隐藏材料路径与私钥密码值。 */
    @Override
    public String toString() {
        return "RegistryClientKeyMaterial[configured=true]";
    }
}
