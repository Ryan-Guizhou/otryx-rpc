package com.peachsoft.otryx.registry;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import javax.crypto.Cipher;
import javax.crypto.EncryptedPrivateKeyInfo;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

/**
 * 有界读取并验证同一份 Registry TLS 材料，随后交给 JDK 或 Netty 客户端直接安装。
 *
 * <p>只接受明确 CA 与 PKCS#8 私钥，不读取系统默认信任库，不提供主机名或信任绕过。
 * 外部文件、证书和密码解析异常均转换为不带原始 cause 的稳定安全拒绝。
 *
 * @Author OpenAI
 * @Version 1.0.0-SNAPSHOT
 * @CreateTime 2026/10/19 11:40
 */
public final class RegistryTlsMaterial {
    private static final int MAX_FILE_BYTES = 1024 * 1024;
    private static final int MAX_CERTIFICATES = 16;
    private static final int KEY_PROOF_BYTES = 32;
    private static final String CLIENT_AUTH_USAGE = "1.3.6.1.5.5.7.3.2";
    private static final String ANY_USAGE = "2.5.29.37.0";
    private static final String PRIVATE_BEGIN = "-----BEGIN PRIVATE KEY-----";
    private static final String PRIVATE_END = "-----END PRIVATE KEY-----";
    private static final String ENCRYPTED_BEGIN = "-----BEGIN ENCRYPTED PRIVATE KEY-----";
    private static final String ENCRYPTED_END = "-----END ENCRYPTED PRIVATE KEY-----";
    private final X509Certificate[] authorities;
    private final X509Certificate[] certificateChain;
    private final PrivateKey privateKey;

    private RegistryTlsMaterial(
            X509Certificate[] authorities, X509Certificate[] certificateChain, PrivateKey privateKey) {
        this.authorities = authorities.clone();
        this.certificateChain = certificateChain.clone();
        this.privateKey = privateKey;
    }

    /**
     * 从受限本地文件一次读取、验证材料；后续构建客户端不重新打开这些路径。
     *
     * @param options 明确的信任集合与可选客户端身份
     * @return 实际已验证的证书与私钥
     */
    public static RegistryTlsMaterial load(RegistryTlsOptions options) {
        Objects.requireNonNull(options, "options");
        try {
            X509Certificate[] authorities = certificates(read(options.trustCertificateCollection()));
            for (X509Certificate authority : authorities) {
                authority.checkValidity();
                if (authority.getBasicConstraints() < 0) {
                    throw new IllegalArgumentException("Registry trust material must contain CA certificates");
                }
            }
            if (options.clientIdentity().isEmpty()) {
                return new RegistryTlsMaterial(authorities, new X509Certificate[0], null);
            }
            RegistryClientKeyMaterial identity = options.clientIdentity().orElseThrow();
            X509Certificate[] chain = certificates(read(identity.certificateChain()));
            char[] password = identity.privateKeyPassword();
            byte[] encodedKey = read(identity.privateKey());
            PrivateKey key;
            try {
                key = decodeKey(encodedKey, password);
            } finally {
                Arrays.fill(password, '\0');
                Arrays.fill(encodedKey, (byte) 0);
            }
            requireClientIdentity(chain, key);
            return new RegistryTlsMaterial(authorities, chain, key);
        } catch (RegistrySecurityException rejected) {
            throw rejected;
        } catch (Exception invalid) {
            throw new RegistrySecurityException(RegistrySecurityFailure.TLS_REJECTED);
        }
    }

    /**
     * 返回同一已验证信任集合的防御性数组副本。
     *
     * @return CA 证书数组
     */
    public X509Certificate[] authorities() {
        return authorities.clone();
    }

    /**
     * 返回已验证客户端证书链的防御性数组副本。
     *
     * @return 没有客户端身份时为空数组
     */
    public X509Certificate[] certificateChain() {
        return certificateChain.clone();
    }

    /**
     * 返回已经与客户端 leaf 匹配的私钥对象，供实际 TLS 引擎安装。
     *
     * @return 可选已解析私钥
     */
    public Optional<PrivateKey> privateKey() {
        return Optional.ofNullable(privateKey);
    }

    /**
     * 建立只信任指定 CA 集合的工厂，Netty 和 JDK 均可直接使用。
     *
     * @return 已初始化的 TrustManagerFactory
     */
    public TrustManagerFactory trustManagerFactory() {
        try {
            KeyStore trust = KeyStore.getInstance(KeyStore.getDefaultType());
            trust.load(null, null);
            for (int i = 0; i < authorities.length; i++) {
                trust.setCertificateEntry("registry-ca-" + i, authorities[i]);
            }
            TrustManagerFactory factory = TrustManagerFactory.getInstance(
                    TrustManagerFactory.getDefaultAlgorithm());
            factory.init(trust);
            return factory;
        } catch (Exception invalid) {
            throw new RegistrySecurityException(RegistrySecurityFailure.TLS_REJECTED);
        }
    }

    /**
     * 建立采用指定信任及可选客户端证书的独立 JDK TLS Context。
     *
     * @return 不修改进程全局默认值的 Context
     */
    public SSLContext sslContext() {
        char[] password = new char[0];
        try {
            KeyManager[] managers = null;
            if (privateKey != null) {
                KeyStore keys = KeyStore.getInstance(KeyStore.getDefaultType());
                keys.load(null, null);
                keys.setKeyEntry("registry-client", privateKey, password, certificateChain);
                KeyManagerFactory factory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
                factory.init(keys, password);
                managers = factory.getKeyManagers();
            }
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(managers, trustManagerFactory().getTrustManagers(), null);
            return context;
        } catch (Exception invalid) {
            throw new RegistrySecurityException(RegistrySecurityFailure.TLS_REJECTED);
        } finally {
            Arrays.fill(password, '\0');
        }
    }

    /** 隐藏证书标识、路径和私钥细节。 */
    @Override
    public String toString() {
        return "RegistryTlsMaterial[authorityCount=" + authorities.length
                + ", clientIdentity=" + (privateKey != null) + ']';
    }

    private static byte[] read(Path path) throws Exception {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new RegistrySecurityException(RegistrySecurityFailure.TLS_REJECTED);
        }
        try (InputStream input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
            byte[] bytes = input.readNBytes(MAX_FILE_BYTES + 1);
            if (bytes.length == 0 || bytes.length > MAX_FILE_BYTES) {
                throw new RegistrySecurityException(RegistrySecurityFailure.RESPONSE_LIMIT_EXCEEDED);
            }
            return bytes;
        }
    }

    private static X509Certificate[] certificates(byte[] bytes) throws Exception {
        CertificateFactory parser = CertificateFactory.getInstance("X.509");
        Collection<? extends java.security.cert.Certificate> decoded =
                parser.generateCertificates(new ByteArrayInputStream(bytes));
        if (decoded.isEmpty() || decoded.size() > MAX_CERTIFICATES) {
            throw new RegistrySecurityException(RegistrySecurityFailure.TLS_REJECTED);
        }
        X509Certificate[] result = new X509Certificate[decoded.size()];
        int index = 0;
        for (java.security.cert.Certificate certificate : decoded) {
            if (!(certificate instanceof X509Certificate x509)) {
                throw new RegistrySecurityException(RegistrySecurityFailure.TLS_REJECTED);
            }
            result[index++] = x509;
        }
        return result;
    }

    private static PrivateKey decodeKey(byte[] pem, char[] password) throws Exception {
        String text = new String(pem, StandardCharsets.US_ASCII).trim();
        boolean encrypted = text.startsWith(ENCRYPTED_BEGIN);
        String begin = encrypted ? ENCRYPTED_BEGIN : PRIVATE_BEGIN;
        String end = encrypted ? ENCRYPTED_END : PRIVATE_END;
        if (!text.startsWith(begin) || !text.endsWith(end)) {
            throw new RegistrySecurityException(RegistrySecurityFailure.TLS_REJECTED);
        }
        String body = text.substring(begin.length(), text.length() - end.length()).replaceAll("\\s", "");
        byte[] encoded = Base64.getDecoder().decode(body);
        try {
            PKCS8EncodedKeySpec keySpec;
            if (encrypted) {
                if (password.length == 0) {
                    throw new RegistrySecurityException(RegistrySecurityFailure.TLS_REJECTED);
                }
                EncryptedPrivateKeyInfo info = new EncryptedPrivateKeyInfo(encoded);
                PBEKeySpec passwordSpec = new PBEKeySpec(password);
                try {
                    var factory = SecretKeyFactory.getInstance(info.getAlgName());
                    Cipher cipher = Cipher.getInstance(info.getAlgName());
                    cipher.init(Cipher.DECRYPT_MODE, factory.generateSecret(passwordSpec), info.getAlgParameters());
                    keySpec = info.getKeySpec(cipher);
                } finally {
                    passwordSpec.clearPassword();
                }
            } else {
                if (password.length != 0) {
                    throw new RegistrySecurityException(RegistrySecurityFailure.TLS_REJECTED);
                }
                keySpec = new PKCS8EncodedKeySpec(encoded);
            }
            for (String algorithm : List.of("RSA", "EC", "Ed25519", "Ed448")) {
                try {
                    return KeyFactory.getInstance(algorithm).generatePrivate(keySpec);
                } catch (java.security.spec.InvalidKeySpecException mismatch) {
                    // 本地有界候选算法依次尝试；不输出密钥解析异常或内容。
                }
            }
            throw new RegistrySecurityException(RegistrySecurityFailure.TLS_REJECTED);
        } finally {
            Arrays.fill(encoded, (byte) 0);
        }
    }

    private static void requireClientIdentity(X509Certificate[] chain, PrivateKey key) throws Exception {
        for (int i = 0; i < chain.length; i++) {
            chain[i].checkValidity();
            if (i + 1 < chain.length) {
                chain[i].verify(chain[i + 1].getPublicKey());
            }
        }
        List<String> usage = chain[0].getExtendedKeyUsage();
        boolean[] keyUsage = chain[0].getKeyUsage();
        if (chain[0].getBasicConstraints() >= 0
                || usage != null && !usage.contains(CLIENT_AUTH_USAGE) && !usage.contains(ANY_USAGE)
                || keyUsage != null && !keyUsage[0]) {
            throw new RegistrySecurityException(RegistrySecurityFailure.TLS_REJECTED);
        }
        String algorithm = switch (key.getAlgorithm()) {
            case "RSA" -> "SHA256withRSA";
            case "EC" -> "SHA256withECDSA";
            case "Ed25519", "EdDSA" -> "Ed25519";
            case "Ed448" -> "Ed448";
            default -> throw new RegistrySecurityException(RegistrySecurityFailure.TLS_REJECTED);
        };
        byte[] proof = new byte[KEY_PROOF_BYTES];
        new SecureRandom().nextBytes(proof);
        Signature signature = Signature.getInstance(algorithm);
        signature.initSign(key);
        signature.update(proof);
        byte[] signed = signature.sign();
        signature.initVerify(chain[0].getPublicKey());
        signature.update(proof);
        if (!signature.verify(signed)) {
            throw new RegistrySecurityException(RegistrySecurityFailure.TLS_REJECTED);
        }
    }
}
