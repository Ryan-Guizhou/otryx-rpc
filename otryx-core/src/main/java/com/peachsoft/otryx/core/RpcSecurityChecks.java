package com.peachsoft.otryx.core;

import com.peachsoft.otryx.api.ApplicationKey;
import com.peachsoft.otryx.api.RpcCertificateBinding;
import com.peachsoft.otryx.api.RpcSecurityProfile;
import com.peachsoft.otryx.api.RpcPeerIdentity;
import com.peachsoft.otryx.api.RpcAuthenticationException;
import com.peachsoft.otryx.api.RpcTarget;
import com.peachsoft.otryx.codec.RpcBoundedResultMethodCodec;
import com.peachsoft.otryx.codec.RpcCodecRegistry;
import com.peachsoft.otryx.codec.RpcCodecSecurityDescriptor;
import com.peachsoft.otryx.codec.RpcMethodCodec;
import com.peachsoft.otryx.observability.RpcSecurityMode;
import com.peachsoft.otryx.registry.ChannelProtection;
import com.peachsoft.otryx.registry.CredentialKind;
import com.peachsoft.otryx.registry.RegistryChannel;
import com.peachsoft.otryx.registry.RegistrySecurityDescriptor;
import com.peachsoft.otryx.registry.RegistrySourceProtection;
import com.peachsoft.otryx.transport.RpcTransportSecurityDescriptor;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

/**
 * 运行资源创建前的纯本地安全预检，检查实际对象而非声明配置。
 *
 * @Author OpenAI
 * @Version 1.0.0-SNAPSHOT
 * @CreateTime 2026/10/19 11:18
 */
final class RpcSecurityChecks {
    private static final Set<RpcTransportSecurityDescriptor.Capability> CLIENT_CAPABILITIES =
            Set.copyOf(EnumSet.allOf(RpcTransportSecurityDescriptor.Capability.class));
    private static final Set<RpcTransportSecurityDescriptor.Capability> SERVER_CAPABILITIES = Set.of(
            RpcTransportSecurityDescriptor.Capability.PEER_CERTIFICATE_CHAIN_VALIDATION,
            RpcTransportSecurityDescriptor.Capability.PEER_APPLICATION_BINDING,
            RpcTransportSecurityDescriptor.Capability.PEER_IDENTITY_DELIVERY,
            RpcTransportSecurityDescriptor.Capability.IDENTITY_EXPIRY);

    private RpcSecurityChecks() {
    }

    static void profile(RpcSecurityProfile profile) {
        if (Objects.requireNonNull(profile, "securityProfile") == RpcSecurityProfile.UNSPECIFIED) {
            throw new IllegalStateException("RPC securityProfile must be explicitly configured");
        }
    }

    static void transport(
            RpcSecurityProfile profile,
            Supplier<RpcTransportSecurityDescriptor> source,
            ApplicationKey localApplication,
            RpcCodecRegistry codecs,
            boolean client,
            Instant now) {
        RpcTransportSecurityDescriptor descriptor = read(source, "transport security descriptor");
        if (profile != RpcSecurityProfile.NETWORK_MTLS) {
            return;
        }
        Set<RpcTransportSecurityDescriptor.Capability> required = client ? CLIENT_CAPABILITIES : SERVER_CAPABILITIES;
        if (descriptor.state() != RpcTransportSecurityDescriptor.State.EFFECTIVE
                || descriptor.mode() != RpcSecurityMode.MTLS
                || !descriptor.capabilities().containsAll(required)) {
            throw new IllegalStateException("NETWORK_MTLS requires effective transport security capabilities");
        }
        RpcCertificateBinding binding = descriptor.localIdentity().orElseThrow(() ->
                new RpcAuthenticationException(RpcAuthenticationException.Reason.IDENTITY_UNBOUND));
        if (!now.isBefore(binding.validUntil())) {
            throw new RpcAuthenticationException(RpcAuthenticationException.Reason.IDENTITY_EXPIRED);
        }
        if (!localApplication.namespace().equals(binding.application().namespace())) {
            throw new RpcAuthenticationException(RpcAuthenticationException.Reason.NAMESPACE_MISMATCH);
        }
        if (!localApplication.equals(binding.application())) {
            throw new RpcAuthenticationException(RpcAuthenticationException.Reason.APPLICATION_MISMATCH);
        }
        if (!descriptor.enabledCodecIds().equals(codecs.supportedCodecIds())) {
            throw new IllegalStateException("Transport and Codec registry must enable the same Codec IDs");
        }
    }

    static void codecs(RpcSecurityProfile profile, RpcCodecRegistry codecs) {
        if (profile != RpcSecurityProfile.NETWORK_MTLS) {
            return;
        }
        read(codecs::securityDescriptors, "codec security descriptors").values().forEach(RpcSecurityChecks::codec);
    }

    static RpcMethodCodec method(RpcSecurityProfile profile, RpcMethodCodec codec, byte expectedId) {
        Objects.requireNonNull(codec, "methodCodec");
        if (codec.codecId() != expectedId) {
            throw new IllegalStateException("Bound method Codec ID differs from its registered Codec");
        }
        if (profile == RpcSecurityProfile.NETWORK_MTLS) {
            codec(read(codec::securityDescriptor, "method codec security descriptor"));
            if (!(codec instanceof RpcBoundedResultMethodCodec)) {
                throw new IllegalStateException(
                        "NETWORK_MTLS requires streaming bounded result encoding for every method Codec");
            }
        }
        return codec;
    }

    private static void codec(RpcCodecSecurityDescriptor descriptor) {
        if ((descriptor.typePolicy() != RpcCodecSecurityDescriptor.TypePolicy.EXPLICIT_ALLOWLIST
                && descriptor.typePolicy() != RpcCodecSecurityDescriptor.TypePolicy.REGISTERED_CONTRACT_TYPES)
                || !descriptor.validatesDeclaredContractTypes() || !descriptor.validatesPayloadBeforeDecode()
                || descriptor.maxPayloadBytes() <= 0 || descriptor.maxDepth() <= 0
                || descriptor.maxEstimatedGraphMemoryBytes() <= 0) {
            throw new IllegalStateException("NETWORK_MTLS requires bounded contract-aware Codec validation");
        }
    }

    static void registry(
            RpcSecurityProfile profile, Supplier<RegistrySecurityDescriptor> source, String namespace) {
        if (profile != RpcSecurityProfile.NETWORK_MTLS) {
            return;
        }
        RegistrySecurityDescriptor descriptor = read(source, "registry security descriptor");
        for (RegistryChannel channel : RegistryChannel.values()) {
            List<ChannelProtection> protections = descriptor.channels().get(channel);
            if (protections == null || protections.isEmpty()) {
                throw new IllegalStateException("NETWORK_MTLS requires all actual Registry channels");
            }
            for (ChannelProtection protection : protections) {
                if (protection.protection() == RegistrySourceProtection.LOCAL_PROCESS) {
                    continue;
                }
                if (protection.protection() != RegistrySourceProtection.AUTHENTICATED_TLS
                        || !protection.namespace().filter(namespace::equals).isPresent()
                        || protection.credentials().isEmpty()
                        || protection.credentials().contains(CredentialKind.NONE)) {
                    throw new IllegalStateException("NETWORK_MTLS requires authenticated scoped Registry sources");
                }
            }
        }
    }

    static boolean providerPeer(
            RpcSecurityProfile profile, RpcPeerIdentity peer, ApplicationKey local, Instant now, long nowNanos) {
        if (!peer.authenticated()) {
            return profile == RpcSecurityProfile.TRUSTED_DEVELOPMENT;
        }
        return peer.validAt(now, nowNanos)
                && peer.application().filter(value -> value.namespace().equals(local.namespace())).isPresent();
    }

    static void responsePeer(
            RpcSecurityProfile profile, RpcPeerIdentity peer, RpcTarget target, Instant now, long nowNanos) {
        if (!peer.authenticated()) {
            if (profile == RpcSecurityProfile.NETWORK_MTLS) {
                throw new RpcAuthenticationException(RpcAuthenticationException.Reason.IDENTITY_UNBOUND);
            }
            return;
        }
        if (!peer.validAt(now, nowNanos)) {
            throw new RpcAuthenticationException(RpcAuthenticationException.Reason.IDENTITY_EXPIRED);
        }
        ApplicationKey actual = peer.application().orElseThrow(() ->
                new RpcAuthenticationException(RpcAuthenticationException.Reason.IDENTITY_UNBOUND));
        if (!target.application().namespace().equals(actual.namespace())) {
            throw new RpcAuthenticationException(RpcAuthenticationException.Reason.NAMESPACE_MISMATCH);
        }
        if (!target.application().equals(actual)) {
            throw new RpcAuthenticationException(RpcAuthenticationException.Reason.APPLICATION_MISMATCH);
        }
    }

    static void positiveFinite(Duration value, String name) {
        Objects.requireNonNull(value, name);
        try {
            if (value.isNegative() || value.isZero() || value.toNanos() <= 0) {
                throw new IllegalArgumentException(name + " must be positive and finite");
            }
        } catch (ArithmeticException failure) {
            throw new IllegalArgumentException(name + " must be positive and finite");
        }
    }

    private static <T> T read(Supplier<T> source, String description) {
        try {
            return Objects.requireNonNull(source.get(), description);
        } catch (RuntimeException failure) {
            throw new IllegalStateException("Invalid " + description);
        }
    }
}
