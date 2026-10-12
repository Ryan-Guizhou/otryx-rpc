package com.peachsoft.otryx.transport.vertx;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.peachsoft.otryx.observability.RpcObserver;
import com.peachsoft.otryx.observability.RpcSecurityMode;
import com.peachsoft.otryx.transport.RpcTransportSecurityOptions;
import io.vertx.core.net.PemKeyCertOptions;
import io.vertx.core.net.PemTrustOptions;
import io.vertx.core.net.SSLOptions;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * Vert.x TLS material 状态检测测试。
 *
 * @Author Ryan
 * @Version 1.0.0
 * @CreateTime 2026/9/29 17:34
 */
class VertxTlsSupportTest {

    @Test
    void fileStateShouldDetectContentChangeWithSameSizeAndMtime()
            throws Exception {
        Path file = Files.createTempFile(
                "otryx-tls-state",
                ".pem");
        try {
            Files.writeString(file, "AAAA");
            FileTime originalTime =
                    Files.getLastModifiedTime(file);

            RpcTransportSecurityOptions security =
                    new RpcTransportSecurityOptions(
                            RpcSecurityMode.TLS,
                            file.toString(),
                            "",
                            "",
                            true,
                            Duration.ofSeconds(1),
                            Duration.ofSeconds(1),
                            Duration.ofDays(1));

            VertxTlsSupport.FileState before =
                    VertxTlsSupport.fileState(security);

            Files.writeString(file, "BBBB");
            Files.setLastModifiedTime(
                    file,
                    originalTime);

            VertxTlsSupport.FileState after =
                    VertxTlsSupport.fileState(security);

            assertNotEquals(before, after);
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    void reloadOptionsMustSnapshotMaterialInsteadOfKeepingFilePaths()
            throws Exception {
        try (TlsTestCertificates certificates =
                     TlsTestCertificates.create()) {
            byte[] certificateBytes = Files.readAllBytes(
                    certificates.serverCertificate);
            byte[] keyBytes = Files.readAllBytes(
                    certificates.serverPrivateKey);
            byte[] caBytes = Files.readAllBytes(
                    certificates.caCertificate);

            RpcTransportSecurityOptions security =
                    new RpcTransportSecurityOptions(
                            RpcSecurityMode.MTLS,
                            certificates.serverCertificate.toString(),
                            certificates.serverPrivateKey.toString(),
                            certificates.caCertificate.toString(),
                            true,
                            Duration.ofSeconds(2),
                            Duration.ofSeconds(1),
                            Duration.ofDays(7));
            SSLOptions options = VertxTlsSupport.reloadOptions(
                    security,
                    false,
                    RpcObserver.noop());
            PemKeyCertOptions keys =
                    (PemKeyCertOptions) options.getKeyCertOptions();
            PemTrustOptions trust =
                    (PemTrustOptions) options.getTrustOptions();
            assertNotNull(keys);
            assertNotNull(trust);
            assertArrayEquals(certificateBytes, keys.getCertValue().getBytes());
            assertArrayEquals(keyBytes, keys.getKeyValue().getBytes());
            assertArrayEquals(caBytes, trust.getCertValues().get(0).getBytes());

            Files.writeString(certificates.serverCertificate, "broken material");
            // 发布阶段不再依赖磁盘路径或重新读取已经被覆盖的证书。
            assertArrayEquals(certificateBytes, keys.getCertValue().getBytes());
            assertArrayEquals(keyBytes, keys.getKeyValue().getBytes());
        }
    }
}
