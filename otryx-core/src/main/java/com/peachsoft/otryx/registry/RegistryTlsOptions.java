package com.peachsoft.otryx.registry;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

/**
 * 明确指定验证服务端身份的信任材料和可选客户端身份，不接受 trustAll 或主机名绕过。
 *
 * @param trustCertificateCollection 本地受控 CA 集合路径
 * @param clientIdentity 可选的本地客户端证书与私钥材料
 *
 * @Author OpenAI
 * @Version 1.0.0-SNAPSHOT
 * @CreateTime 2026/10/19 11:22
 */
public record RegistryTlsOptions(
        Path trustCertificateCollection, Optional<RegistryClientKeyMaterial> clientIdentity) {

    /** 校验材料引用；适配器随后必须验证并安装同一份受限读取内容。 */
    public RegistryTlsOptions {
        Objects.requireNonNull(trustCertificateCollection, "trustCertificateCollection");
        clientIdentity = Objects.requireNonNull(clientIdentity, "clientIdentity");
    }

    /** 仅描述配置存在性，不输出路径和密码。 */
    @Override
    public String toString() {
        return "RegistryTlsOptions[trustConfigured=true, clientIdentity=" + clientIdentity.isPresent() + ']';
    }
}
