package com.peachsoft.otryx.registry;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.peachsoft.otryx.api.RpcSecurityProfile;
import com.peachsoft.otryx.registry.memory.MemoryApplicationRegistry;
import com.peachsoft.otryx.registry.memory.MemoryApplicationRegistryFactory;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * 验证安全配置的防御性复制、默认拒绝及实际来源聚合不可丢失弱来源。
 *
 * @Author OpenAI
 * @Version 1.0.0-SNAPSHOT
 * @CreateTime 2026/10/19 11:34
 */
class RegistrySecurityOptionsTest {
    @Test
    void secretsAreCopiedInBothDirectionsAndNeverRendered() {
        char[] secret = "synthetic-secret".toCharArray();
        RegistryCredentials credentials = RegistryCredentials.userPassword("private-user", secret);
        RegistryClientKeyMaterial material = new RegistryClientKeyMaterial(
                Path.of("client.pem"), Path.of("key.pem"), secret);
        secret[0] = 'x';
        credentials.password()[1] = 'x';
        material.privateKeyPassword()[1] = 'x';
        assertArrayEquals("synthetic-secret".toCharArray(), credentials.password());
        assertArrayEquals("synthetic-secret".toCharArray(), material.privateKeyPassword());
        assertFalse(credentials.toString().contains("private-user"));
        assertFalse(credentials.toString().contains("synthetic-secret"));
        assertFalse(material.toString().contains("synthetic-secret"));
        assertFalse(material.toString().contains("key.pem"));
    }

    @Test
    void oldOptionsRemainUnspecifiedAndMemoryFactoryRequiresExplicitIntent() {
        RegistryOptions options = new RegistryOptions(List.of(), "security-test", Map.of());
        assertEquals(RpcSecurityProfile.UNSPECIFIED, options.securityProfile());
        assertThrows(RegistrySecurityException.class, () -> new MemoryApplicationRegistryFactory().create(options));
        try (var registry = new MemoryApplicationRegistryFactory().create(
                RegistryOptions.trustedDevelopment(List.of(), "security-test", Map.of()))) {
            assertEquals(RegistrySourceProtection.LOCAL_PROCESS,
                    registry.securityDescriptor().channels().get(RegistryChannel.MEMBERSHIP).getFirst().protection());
        }
    }

    @Test
    void forgedLocalSourceDoesNotInheritAuditedMemoryTrust() {
        try (MemoryApplicationRegistry memory = new MemoryApplicationRegistry()) {
            RegistrySecuritySource spoof = new RegistrySecuritySource() {
                @Override
                public RegistrySecurityDescriptor securityDescriptor() {
                    return memory.securityDescriptor();
                }
            };
            assertEquals(RegistrySourceProtection.UNKNOWN,
                    RegistrySecurityDescriptor.from(spoof, RegistryChannel.MEMBERSHIP)
                            .channels().get(RegistryChannel.MEMBERSHIP).getFirst().protection());
            assertEquals(RegistrySourceProtection.LOCAL_PROCESS,
                    RegistrySecurityDescriptor.from(memory, RegistryChannel.MEMBERSHIP)
                            .channels().get(RegistryChannel.MEMBERSHIP).getFirst().protection());
        }
    }

    @Test
    void missingSourceCannotDisappearWhenAnotherSourceCoversSameChannel() {
        RegistrySecuritySource unknown = new RegistrySecuritySource() { };
        ChannelProtection tls = new ChannelProtection(RegistrySourceProtection.AUTHENTICATED_TLS,
                Set.of(CredentialKind.USER_PASSWORD), Optional.of("security-test"), Optional.empty());
        RegistrySecurityDescriptor combined = RegistrySecurityDescriptor.combine(
                RegistrySecurityDescriptor.from(unknown, RegistryChannel.MAPPING),
                new RegistrySecurityDescriptor(Map.of(RegistryChannel.MAPPING, List.of(tls))));
        assertEquals(2, combined.channels().get(RegistryChannel.MAPPING).size());
        assertEquals(RegistrySourceProtection.UNKNOWN,
                combined.channels().get(RegistryChannel.MAPPING).getFirst().protection());
        assertThrows(UnsupportedOperationException.class,
                () -> combined.channels().get(RegistryChannel.MAPPING).clear());
    }
}
