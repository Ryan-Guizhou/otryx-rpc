package com.peachsoft.otryx.transport.vertx;

import com.peachsoft.otryx.api.ApplicationKey;
import com.peachsoft.otryx.api.RpcCertificateBinding;
import com.peachsoft.otryx.transport.RpcTransportIdentityOptions;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.security.KeyPair;
import java.security.MessageDigest;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.security.Security;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.HexFormat;
import java.util.Set;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.ExtendedKeyUsage;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.GeneralNames;
import org.bouncycastle.asn1.x509.KeyPurposeId;
import org.bouncycastle.asn1.x509.KeyUsage;
import org.bouncycastle.cert.X509v3CertificateBuilder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509ExtensionUtils;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.openssl.jcajce.JcaPEMWriter;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;

/**
 * TLS 集成测试临时证书工厂。
 *
 * @Author Ryan
 * @Version 1.0.0
 * @CreateTime 2026/9/29 15:04
 */
final class TlsTestCertificates implements AutoCloseable {

    private static final SecureRandom RANDOM =
            new SecureRandom();

    static {
        if (Security.getProvider(
                BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(
                    new BouncyCastleProvider());
        }
    }

    final Path directory;
    final Path caCertificate;
    final Path wrongCaCertificate;
    final Path serverCertificate;
    final Path serverPrivateKey;
    final Path rotatedServerCertificate;
    final Path rotatedServerPrivateKey;
    final Path clientCertificate;
    final Path clientPrivateKey;
    final Path expiredServerCertificate;
    final Path expiredServerPrivateKey;

    private TlsTestCertificates(
            Path directory,
            Path caCertificate,
            Path wrongCaCertificate,
            Path serverCertificate,
            Path serverPrivateKey,
            Path rotatedServerCertificate,
            Path rotatedServerPrivateKey,
            Path clientCertificate,
            Path clientPrivateKey,
            Path expiredServerCertificate,
            Path expiredServerPrivateKey) {
        this.directory = directory;
        this.caCertificate = caCertificate;
        this.wrongCaCertificate = wrongCaCertificate;
        this.serverCertificate = serverCertificate;
        this.serverPrivateKey = serverPrivateKey;
        this.rotatedServerCertificate =
                rotatedServerCertificate;
        this.rotatedServerPrivateKey =
                rotatedServerPrivateKey;
        this.clientCertificate = clientCertificate;
        this.clientPrivateKey = clientPrivateKey;
        this.expiredServerCertificate =
                expiredServerCertificate;
        this.expiredServerPrivateKey =
                expiredServerPrivateKey;
    }

    static final ApplicationKey PROVIDER = new ApplicationKey("tls-tests", "provider");
    static final ApplicationKey CONSUMER = new ApplicationKey("tls-tests", "consumer");

    static X509Certificate readCertificate(Path path) throws Exception {
        try (var input = Files.newInputStream(path)) {
            return (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(input);
        }
    }

    static RpcCertificateBinding binding(Path path, ApplicationKey application, Instant validUntil) throws Exception {
        String fingerprint = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(readCertificate(path).getEncoded()));
        return new RpcCertificateBinding(fingerprint, application, validUntil);
    }

    RpcTransportIdentityOptions identities(ApplicationKey localApplication) throws Exception {
        return identities(localApplication, Instant.now().plus(1, ChronoUnit.DAYS));
    }

    RpcTransportIdentityOptions identities(ApplicationKey localApplication, Instant validUntil) throws Exception {
        return new RpcTransportIdentityOptions(localApplication, Set.of(
                binding(serverCertificate, PROVIDER, validUntil),
                binding(rotatedServerCertificate, PROVIDER, validUntil),
                binding(clientCertificate, CONSUMER, validUntil)));
    }

    static TlsTestCertificates create() throws Exception {
        Path directory =
                Files.createTempDirectory(
                        "otryx-tls-");
        KeyPair caKey = keyPair();
        X509Certificate ca = caCertificate(
                "OTRYX RPC Test CA",
                caKey);
        Path caCertificate =
                writeCertificate(
                        directory.resolve("ca.crt"),
                        ca);
        writePrivateKey(
                directory.resolve("ca.key"),
                caKey);

        KeyPair wrongCaKey = keyPair();
        X509Certificate wrongCa = caCertificate(
                "OTRYX RPC Wrong CA",
                wrongCaKey);
        Path wrongCaCertificate =
                writeCertificate(
                        directory.resolve("wrong-ca.crt"),
                        wrongCa);

        KeyPair server = keyPair();
        X509Certificate serverCertificateValue =
                leafCertificate(
                        "localhost",
                        server,
                        ca,
                        caKey,
                        false,
                        false);
        Path serverCertificate =
                writeCertificate(
                        directory.resolve("server.crt"),
                        serverCertificateValue);
        Path serverPrivateKey =
                writePrivateKey(
                        directory.resolve("server.key"),
                        server);

        KeyPair rotated = keyPair();
        X509Certificate rotatedCertificateValue =
                leafCertificate(
                        "localhost",
                        rotated,
                        ca,
                        caKey,
                        false,
                        false);
        Path rotatedServerCertificate =
                writeCertificate(
                        directory.resolve("server-rotated.crt"),
                        rotatedCertificateValue);
        Path rotatedServerPrivateKey =
                writePrivateKey(
                        directory.resolve("server-rotated.key"),
                        rotated);

        KeyPair client = keyPair();
        X509Certificate clientCertificateValue =
                leafCertificate(
                        "otryx-test-client",
                        client,
                        ca,
                        caKey,
                        true,
                        false);
        Path clientCertificate =
                writeCertificate(
                        directory.resolve("client.crt"),
                        clientCertificateValue);
        Path clientPrivateKey =
                writePrivateKey(
                        directory.resolve("client.key"),
                        client);

        KeyPair expired = keyPair();
        X509Certificate expiredValue =
                leafCertificate(
                        "localhost",
                        expired,
                        ca,
                        caKey,
                        false,
                        true);
        Path expiredServerCertificate =
                writeCertificate(
                        directory.resolve("expired-server.crt"),
                        expiredValue);
        Path expiredServerPrivateKey =
                writePrivateKey(
                        directory.resolve("expired-server.key"),
                        expired);

        return new TlsTestCertificates(
                directory,
                caCertificate,
                wrongCaCertificate,
                serverCertificate,
                serverPrivateKey,
                rotatedServerCertificate,
                rotatedServerPrivateKey,
                clientCertificate,
                clientPrivateKey,
                expiredServerCertificate,
                expiredServerPrivateKey);
    }

    void rotateServerMaterial() throws IOException {
        Files.copy(
                rotatedServerCertificate,
                serverCertificate,
                StandardCopyOption.REPLACE_EXISTING);
        Files.copy(
                rotatedServerPrivateKey,
                serverPrivateKey,
                StandardCopyOption.REPLACE_EXISTING);
        FileTime future = FileTime.from(
                Instant.now().plusSeconds(2));
        Files.setLastModifiedTime(
                serverCertificate,
                future);
        Files.setLastModifiedTime(
                serverPrivateKey,
                future);
    }

    void breakServerCertificate() throws IOException {
        Files.writeString(
                serverCertificate,
                "not-a-certificate");
        Files.setLastModifiedTime(
                serverCertificate,
                FileTime.from(
                        Instant.now().plusSeconds(3)));
    }

    private static KeyPair keyPair()
            throws Exception {
        KeyPairGenerator generator =
                KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048, RANDOM);
        return generator.generateKeyPair();
    }

    private static X509Certificate caCertificate(
            String commonName,
            KeyPair keyPair) throws Exception {
        Instant now = Instant.now();
        X500Name subject =
                new X500Name("CN=" + commonName);
        X509v3CertificateBuilder builder =
                new JcaX509v3CertificateBuilder(
                        subject,
                        serial(),
                        Date.from(
                                now.minus(
                                        1,
                                        ChronoUnit.HOURS)),
                        Date.from(
                                now.plus(
                                        3650,
                                        ChronoUnit.DAYS)),
                        subject,
                        keyPair.getPublic());
        JcaX509ExtensionUtils extensions =
                new JcaX509ExtensionUtils();
        builder.addExtension(
                Extension.basicConstraints,
                true,
                new BasicConstraints(true));
        builder.addExtension(
                Extension.keyUsage,
                true,
                new KeyUsage(
                        KeyUsage.keyCertSign
                                | KeyUsage.cRLSign));
        builder.addExtension(
                Extension.subjectKeyIdentifier,
                false,
                extensions.createSubjectKeyIdentifier(
                        keyPair.getPublic()));
        return sign(
                builder,
                keyPair);
    }

    private static X509Certificate leafCertificate(
            String commonName,
            KeyPair keyPair,
            X509Certificate issuer,
            KeyPair issuerKey,
            boolean client,
            boolean expired) throws Exception {
        Instant now = Instant.now();
        Instant notBefore = expired
                ? now.minus(3, ChronoUnit.DAYS)
                : now.minus(1, ChronoUnit.HOURS);
        Instant notAfter = expired
                ? now.minus(2, ChronoUnit.DAYS)
                : now.plus(365, ChronoUnit.DAYS);
        X500Name issuerName =
                new X500Name(
                        issuer.getSubjectX500Principal()
                                .getName());
        X500Name subject =
                new X500Name("CN=" + commonName);
        X509v3CertificateBuilder builder =
                new JcaX509v3CertificateBuilder(
                        issuerName,
                        serial(),
                        Date.from(notBefore),
                        Date.from(notAfter),
                        subject,
                        keyPair.getPublic());
        builder.addExtension(
                Extension.basicConstraints,
                true,
                new BasicConstraints(false));
        builder.addExtension(
                Extension.keyUsage,
                true,
                new KeyUsage(
                        KeyUsage.digitalSignature
                                | KeyUsage.keyEncipherment));
        builder.addExtension(
                Extension.extendedKeyUsage,
                false,
                new ExtendedKeyUsage(
                        client
                                ? KeyPurposeId.id_kp_clientAuth
                                : KeyPurposeId.id_kp_serverAuth));
        if (!client) {
            builder.addExtension(
                    Extension.subjectAlternativeName,
                    false,
                    new GeneralNames(
                            new GeneralName(
                                    GeneralName.dNSName,
                                    "localhost")));
        }
        ContentSigner signer =
                new JcaContentSignerBuilder(
                        "SHA256withRSA")
                        .setProvider(
                                BouncyCastleProvider.PROVIDER_NAME)
                        .build(issuerKey.getPrivate());
        return new JcaX509CertificateConverter()
                .setProvider(
                        BouncyCastleProvider.PROVIDER_NAME)
                .getCertificate(builder.build(signer));
    }

    private static X509Certificate sign(
            X509v3CertificateBuilder builder,
            KeyPair keyPair) throws Exception {
        ContentSigner signer =
                new JcaContentSignerBuilder(
                        "SHA256withRSA")
                        .setProvider(
                                BouncyCastleProvider.PROVIDER_NAME)
                        .build(keyPair.getPrivate());
        return new JcaX509CertificateConverter()
                .setProvider(
                        BouncyCastleProvider.PROVIDER_NAME)
                .getCertificate(builder.build(signer));
    }

    private static BigInteger serial() {
        return new BigInteger(
                160,
                RANDOM).abs().add(BigInteger.ONE);
    }

    private static Path writeCertificate(
            Path path,
            X509Certificate certificate)
            throws IOException {
        try (JcaPEMWriter writer =
                     new JcaPEMWriter(
                             Files.newBufferedWriter(path))) {
            writer.writeObject(certificate);
        }
        return path;
    }

    private static Path writePrivateKey(
            Path path,
            KeyPair keyPair)
            throws IOException {
        try (JcaPEMWriter writer =
                     new JcaPEMWriter(
                             Files.newBufferedWriter(path))) {
            writer.writeObject(keyPair.getPrivate());
        }
        return path;
    }

    @Override
    public void close() throws IOException {
        try (var paths = Files.walk(directory)) {
            paths.sorted(
                            java.util.Comparator
                                    .reverseOrder())
                    .forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (IOException ignored) {
                        }
                    });
        }
    }
}
