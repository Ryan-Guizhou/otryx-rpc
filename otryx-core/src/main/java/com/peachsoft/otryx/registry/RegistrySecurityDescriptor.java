package com.peachsoft.otryx.registry;

import com.peachsoft.otryx.registry.memory.MemoryApplicationManifestRepository;
import com.peachsoft.otryx.registry.memory.MemoryApplicationRegistry;
import com.peachsoft.otryx.registry.memory.MemoryServiceMappingRepository;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 保留每个逻辑通道的全部实际来源，禁止以最强来源覆盖其余参与者。
 *
 * @param channels 每个通道的不可变来源列表；缺少通道或空列表表示未验证
 *
 * @Author OpenAI
 * @Version 1.0.0-SNAPSHOT
 * @CreateTime 2026/10/19 11:22
 */
public record RegistrySecurityDescriptor(Map<RegistryChannel, List<ChannelProtection>> channels) {

    /** 复制映射、列表并拒绝 null；不将空覆盖解释为安全。 */
    public RegistrySecurityDescriptor {
        Objects.requireNonNull(channels, "channels");
        EnumMap<RegistryChannel, List<ChannelProtection>> copy = new EnumMap<>(RegistryChannel.class);
        channels.forEach((channel, sources) ->
                copy.put(Objects.requireNonNull(channel, "channel"), List.copyOf(sources)));
        channels = Map.copyOf(copy);
    }

    /**
     * 创建没有已验证通道的描述。
     *
     * @return 未知描述
     */
    public static RegistrySecurityDescriptor unknown() {
        return new RegistrySecurityDescriptor(Map.of());
    }

    /**
     * 从实际 SPI 获取指定通道，进程内信任仅授予三个审核过的 final Memory 实现。
     *
     * <p>不得用配置 DTO 调用本方法来替真正被调用的组件提供保护。
     *
     * @param source 实际调用的组件
     * @param channel 所需通道
     * @return 该通道的全部来源；未知或未覆盖时不产生可信覆盖
     * @throws RegistrySecurityException 来源返回无效描述或拒绝报告保护时抛出
     */
    public static RegistrySecurityDescriptor from(RegistrySecuritySource source, RegistryChannel channel) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(channel, "channel");
        RegistrySecurityDescriptor descriptor;
        try {
            descriptor = Objects.requireNonNull(source.securityDescriptor(), "descriptor");
        } catch (RuntimeException failure) {
            throw new RegistrySecurityException(RegistrySecurityFailure.UNSUPPORTED_PROTECTION);
        }
        List<ChannelProtection> sources = descriptor.channels().get(channel);
        if (sources == null || sources.isEmpty()) {
            ChannelProtection unknown = new ChannelProtection(RegistrySourceProtection.UNKNOWN,
                    java.util.Set.of(), java.util.Optional.empty(), java.util.Optional.empty());
            return new RegistrySecurityDescriptor(Map.of(channel, List.of(unknown)));
        }
        boolean local = source instanceof MemoryApplicationRegistry
                || source instanceof MemoryApplicationManifestRepository
                || source instanceof MemoryServiceMappingRepository;
        List<ChannelProtection> checked = sources.stream().map(protection -> {
            if (protection.protection() == RegistrySourceProtection.LOCAL_PROCESS && !local) {
                return new ChannelProtection(RegistrySourceProtection.UNKNOWN, protection.credentials(),
                        protection.namespace(), protection.group());
            }
            return protection;
        }).toList();
        return new RegistrySecurityDescriptor(Map.of(channel, checked));
    }

    /**
     * 追加聚合所有来源，同通道内容不覆盖、不去重为最强一项。
     *
     * @param descriptors 来自实际调用组件的描述
     * @return 不可变的完整来源图
     */
    public static RegistrySecurityDescriptor combine(RegistrySecurityDescriptor... descriptors) {
        Objects.requireNonNull(descriptors, "descriptors");
        EnumMap<RegistryChannel, List<ChannelProtection>> combined = new EnumMap<>(RegistryChannel.class);
        for (RegistrySecurityDescriptor descriptor : descriptors) {
            Objects.requireNonNull(descriptor, "descriptor").channels().forEach((channel, sources) ->
                    combined.computeIfAbsent(channel, ignored -> new ArrayList<>()).addAll(sources));
        }
        return new RegistrySecurityDescriptor(combined);
    }
}
