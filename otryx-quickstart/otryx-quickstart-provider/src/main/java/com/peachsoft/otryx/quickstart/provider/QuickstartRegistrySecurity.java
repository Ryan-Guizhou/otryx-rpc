package com.peachsoft.otryx.quickstart.provider;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.peachsoft.otryx.api.RpcSecurityProfile;
import com.peachsoft.otryx.registry.ChannelProtection;
import com.peachsoft.otryx.registry.CredentialKind;
import com.peachsoft.otryx.registry.RegistryChannel;
import com.peachsoft.otryx.registry.RegistryClientKeyMaterial;
import com.peachsoft.otryx.registry.RegistryCredentials;
import com.peachsoft.otryx.registry.RegistryOptions;
import com.peachsoft.otryx.registry.RegistrySecurityDescriptor;
import com.peachsoft.otryx.registry.RegistrySecurityException;
import com.peachsoft.otryx.registry.RegistrySecurityFailure;
import com.peachsoft.otryx.registry.RegistrySecurityOptions;
import com.peachsoft.otryx.registry.RegistrySourceProtection;
import com.peachsoft.otryx.registry.RegistryTlsOptions;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

/**
 * 有界读取独立观察者的私有安全配置，并校验实际 Resolver 使用的每条 Registry 来源。
 *
 * <p>只接受文件引用，不加载 Spring 配置、不创建成员、不执行写入；失败不携带输入或外部异常链。
 *
 * @Author OpenAI
 * @Version 1.0.0-SNAPSHOT
 * @CreateTime 2026/10/19 11:53
 */
final class QuickstartRegistrySecurity {
    static final String SECURITY_FILE_ENVIRONMENT = "QUICKSTART_REGISTRY_SECURITY_FILE";
    private static final int MAX_JSON_BYTES = 64 * 1024;
    private static final int MAX_JSON_DEPTH = 8;
    private static final int MAX_STRING_LENGTH = 4096;
    private static final int MAX_SECRET_BYTES = 4096;
    private static final String EUREKA = "eureka";
    private static final String SECURITY = "security";
    private static final String METADATA_SECURITY = "metadataSecurity";
    private static final String TRUST_CERTIFICATES = "trustCertificateCollection";
    private static final String CREDENTIAL_KIND = "credentialKind";
    private static final String USERNAME_FILE = "usernameFile";
    private static final String PASSWORD_FILE = "passwordFile";
    private static final String TOKEN_FILE = "tokenFile";
    private static final String CLIENT_CERTIFICATES = "clientCertificateChain";
    private static final String CLIENT_PRIVATE_KEY = "clientPrivateKey";
    private static final Set<String> ROOT_FIELDS = Set.of(SECURITY, METADATA_SECURITY);
    private static final Set<String> SECURITY_FIELDS = Set.of(TRUST_CERTIFICATES, CREDENTIAL_KIND,
            USERNAME_FILE, PASSWORD_FILE, TOKEN_FILE, CLIENT_CERTIFICATES, CLIENT_PRIVATE_KEY);
    private static final Set<PosixFilePermission> PRIVATE_PERMISSIONS = Set.of(
            PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
    private static final JsonFactory JSON_FACTORY = JsonFactory.builder()
            .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(MAX_JSON_DEPTH)
                    .maxStringLength(MAX_STRING_LENGTH).build())
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();
    private static final ObjectMapper JSON = new ObjectMapper(JSON_FACTORY);

    private QuickstartRegistrySecurity() {
    }

    /** null 明确选择开发配置；存在但无效的安全文件绝不回退。 */
    static RegistryOptions options(String backend, RegistryOptions target, String securityFile) {
        if (securityFile == null) {
            return RegistryOptions.trustedDevelopment(target.endpoints(), target.namespace(),
                    target.providerOptions(), target.observer());
        }
        try {
            if (securityFile.isBlank()) {
                throw rejected();
            }
            Path file = Path.of(securityFile).toAbsolutePath().normalize();
            JsonNode root = readJson(file);
            requireObject(root, ROOT_FIELDS);
            RegistrySecurityOptions security = readSecurity(file, required(root, SECURITY));
            boolean separateMetadata = root.has(METADATA_SECURITY);
            if (EUREKA.equals(backend) != separateMetadata) {
                throw rejected();
            }
            Optional<RegistrySecurityOptions> metadata = separateMetadata
                    ? Optional.of(readSecurity(file, root.get(METADATA_SECURITY))) : Optional.empty();
            return new RegistryOptions(target.endpoints(), target.namespace(), target.providerOptions(),
                    target.observer(), RpcSecurityProfile.NETWORK_MTLS, security, metadata);
        } catch (IOException | RuntimeException failure) {
            throw rejected();
        }
    }

    /** 只读实际组件的聚合描述；来源边界已剔除第三方伪称的 LOCAL_PROCESS。 */
    static void verify(RpcSecurityProfile profile, Supplier<RegistrySecurityDescriptor> source, String namespace) {
        if (profile == RpcSecurityProfile.TRUSTED_DEVELOPMENT) {
            return;
        }
        if (profile != RpcSecurityProfile.NETWORK_MTLS) {
            throw rejected();
        }
        try {
            RegistrySecurityDescriptor descriptor = Objects.requireNonNull(source.get(), "descriptor");
            for (RegistryChannel channel : RegistryChannel.values()) {
                List<ChannelProtection> protections = descriptor.channels().get(channel);
                if (protections == null || protections.isEmpty()) {
                    throw rejected();
                }
                for (ChannelProtection protection : protections) {
                    if (protection.protection() == RegistrySourceProtection.LOCAL_PROCESS) {
                        continue;
                    }
                    if (protection.protection() != RegistrySourceProtection.AUTHENTICATED_TLS
                            || protection.namespace().filter(namespace::equals).isEmpty()
                            || protection.credentials().isEmpty()
                            || protection.credentials().contains(CredentialKind.NONE)) {
                        throw rejected();
                    }
                }
            }
        } catch (RuntimeException failure) {
            throw rejected();
        }
    }

    private static JsonNode readJson(Path file) throws IOException {
        byte[] content = readPrivateFile(file, MAX_JSON_BYTES);
        try (JsonParser parser = JSON_FACTORY.createParser(content)) {
            JsonNode root = JSON.readTree(parser);
            if (root == null || parser.nextToken() != null) {
                throw rejected();
            }
            return root;
        } finally {
            Arrays.fill(content, (byte) 0);
        }
    }

    private static RegistrySecurityOptions readSecurity(Path file, JsonNode node) throws IOException {
        requireObject(node, SECURITY_FIELDS);
        node.elements().forEachRemaining(QuickstartRegistrySecurity::requireText);
        Path trust = referencedPath(file, required(node, TRUST_CERTIFICATES));
        CredentialKind kind = CredentialKind.valueOf(requireText(required(node, CREDENTIAL_KIND)));
        boolean hasChain = node.has(CLIENT_CERTIFICATES);
        boolean hasKey = node.has(CLIENT_PRIVATE_KEY);
        if (hasChain != hasKey) {
            throw rejected();
        }
        Optional<RegistryClientKeyMaterial> identity = hasChain
                ? Optional.of(new RegistryClientKeyMaterial(referencedPath(file, node.get(CLIENT_CERTIFICATES)),
                        referencedPath(file, node.get(CLIENT_PRIVATE_KEY)), null)) : Optional.empty();
        RegistryCredentials credentials = switch (kind) {
            case USER_PASSWORD -> {
                forbid(node, TOKEN_FILE);
                char[] username = readSecret(referencedPath(file, required(node, USERNAME_FILE)));
                try {
                    char[] password = readSecret(referencedPath(file, required(node, PASSWORD_FILE)));
                    try {
                        yield RegistryCredentials.userPassword(new String(username), password);
                    } finally {
                        Arrays.fill(password, '\0');
                    }
                } finally {
                    Arrays.fill(username, '\0');
                }
            }
            case ACL_TOKEN -> {
                forbid(node, USERNAME_FILE, PASSWORD_FILE);
                char[] token = readSecret(referencedPath(file, required(node, TOKEN_FILE)));
                try {
                    yield RegistryCredentials.aclToken(token);
                } finally {
                    Arrays.fill(token, '\0');
                }
            }
            case CLIENT_CERTIFICATE -> {
                forbid(node, USERNAME_FILE, PASSWORD_FILE, TOKEN_FILE);
                if (identity.isEmpty()) {
                    throw rejected();
                }
                yield RegistryCredentials.clientCertificate();
            }
            case NONE -> throw rejected();
        };
        return new RegistrySecurityOptions(Optional.of(new RegistryTlsOptions(trust, identity)), credentials);
    }

    private static byte[] readPrivateFile(Path file, int limit) throws IOException {
        PosixFileAttributes before = Files.readAttributes(file, PosixFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!before.isRegularFile() || !before.permissions().equals(PRIVATE_PERMISSIONS)
                || before.size() <= 0 || before.size() > limit) {
            throw rejected();
        }
        ByteBuffer content = ByteBuffer.allocate(limit + 1);
        try (SeekableByteChannel channel = Files.newByteChannel(file,
                Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS))) {
            while (content.hasRemaining()) {
                int read = channel.read(content);
                if (read < 0) {
                    break;
                }
                if (read == 0) {
                    throw rejected();
                }
            }
            PosixFileAttributes after =
                    Files.readAttributes(file, PosixFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (content.position() > limit || content.position() != before.size() || !after.isRegularFile()
                    || !after.permissions().equals(PRIVATE_PERMISSIONS) || before.size() != after.size()
                    || !Objects.equals(before.fileKey(), after.fileKey())
                    || !before.lastModifiedTime().equals(after.lastModifiedTime())) {
                throw rejected();
            }
            return Arrays.copyOf(content.array(), content.position());
        } finally {
            Arrays.fill(content.array(), (byte) 0);
        }
    }

    private static char[] readSecret(Path file) throws IOException {
        byte[] bytes = readPrivateFile(file, MAX_SECRET_BYTES);
        CharBuffer decoded = null;
        try {
            decoded = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes));
            int length = decoded.remaining();
            if (length > 0 && decoded.get(length - 1) == '\n') {
                length--;
                if (length > 0 && decoded.get(length - 1) == '\r') {
                    length--;
                }
            }
            if (length == 0) {
                throw rejected();
            }
            for (int i = 0; i < length; i++) {
                if (Character.isISOControl(decoded.get(i))) {
                    throw rejected();
                }
            }
            char[] secret = new char[length];
            decoded.get(secret);
            return secret;
        } finally {
            Arrays.fill(bytes, (byte) 0);
            if (decoded != null && decoded.hasArray()) {
                Arrays.fill(decoded.array(), '\0');
            }
        }
    }

    private static Path referencedPath(Path file, JsonNode value) {
        Path reference = Path.of(requireText(value));
        return reference.isAbsolute() ? reference.normalize() : file.getParent().resolve(reference).normalize();
    }

    private static void requireObject(JsonNode node, Set<String> allowed) {
        if (node == null || !node.isObject() || node.size() == 0 || node.size() > allowed.size()) {
            throw rejected();
        }
        node.fieldNames().forEachRemaining(name -> {
            if (!allowed.contains(name)) {
                throw rejected();
            }
        });
    }

    private static JsonNode required(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            throw rejected();
        }
        return value;
    }

    private static String requireText(JsonNode value) {
        if (!value.isTextual() || value.textValue().isBlank() || value.textValue().length() > MAX_STRING_LENGTH) {
            throw rejected();
        }
        return value.textValue();
    }

    private static void forbid(JsonNode node, String... fields) {
        for (String field : fields) {
            if (node.has(field)) {
                throw rejected();
            }
        }
    }

    private static RegistrySecurityException rejected() {
        return new RegistrySecurityException(RegistrySecurityFailure.UNSUPPORTED_PROTECTION);
    }
}
