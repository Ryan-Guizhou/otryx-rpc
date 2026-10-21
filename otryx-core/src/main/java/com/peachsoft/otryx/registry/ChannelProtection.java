package com.peachsoft.otryx.registry;

import com.peachsoft.otryx.api.ApplicationKey;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * 保存一条实际 Registry 来源的保护和隔离域，不保存凭据值。
 *
 * @param protection 实际已安装的通道保护
 * @param credentials 实际认证机制集合，不包含凭据值
 * @param namespace 实际逻辑命名空间；审核过的进程内实现可为空
 * @param group 后端实际使用的可选存储组
 *
 * @Author OpenAI
 * @Version 1.0.0-SNAPSHOT
 * @CreateTime 2026/10/19 11:22
 */
public record ChannelProtection(
        RegistrySourceProtection protection,
        Set<CredentialKind> credentials,
        Optional<String> namespace,
        Optional<String> group) {

    /** 防御性复制，拒绝 null 与非法的逻辑命名空间。 */
    public ChannelProtection {
        Objects.requireNonNull(protection, "protection");
        credentials = Set.copyOf(Objects.requireNonNull(credentials, "credentials"));
        namespace = Objects.requireNonNull(namespace, "namespace")
                .map(value -> new ApplicationKey(value, "registry").namespace());
        group = Objects.requireNonNull(group, "group");
    }
}
