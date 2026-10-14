package com.peachsoft.otryx.observability.jfr;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.peachsoft.otryx.api.RpcEndpoint;
import com.peachsoft.otryx.api.RpcStatus;
import com.peachsoft.otryx.api.ServiceKey;
import com.peachsoft.otryx.observability.RpcRegistryRecoveryAction;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.time.Duration;
import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordingFile;
import org.junit.jupiter.api.Test;

/**
 * JFR Observer 基础测试。
 *
 * @Author Ryan
 * @Version 1.0.0
 * @CreateTime 2026/9/29 15:00
 */
class JfrRpcObserverTest {

    @Test
    void registryRecoveryJfrMustNotLeakDynamicRegistryNames() throws Exception {
        Path file = Files.createTempFile("otryx-registry-redaction-", ".jfr");
        try {
            try (Recording recording = new Recording()) {
                recording.enable(RpcRecoveryJfrEvent.class);
                recording.start();

                JfrRpcObserver observer = new JfrRpcObserver(Duration.ofMillis(1));
                observer.onRegistryRecoveryCompleted(
                        "custom-DO_NOT_EXPORT_SECRET",
                        RpcRegistryRecoveryAction.SUBSCRIPTION_RECOVERED,
                        100L,
                        null);
                observer.onRegistryRecoveryCompleted(
                        "nacos",
                        RpcRegistryRecoveryAction.SNAPSHOT_RESYNCED,
                        200L,
                        null);

                recording.stop();
                recording.dump(file);
            }

            List<String> domains = RecordingFile.readAllEvents(file)
                    .stream()
                    .filter(event -> event.getEventType().getName()
                            .equals("com.peachsoft.otryx.Recovery"))
                    .map(event -> event.getString("domain"))
                    .toList();
            assertTrue(domains.contains("registry:custom"));
            assertTrue(domains.contains("registry:nacos"));
            assertFalse(domains.stream()
                    .anyMatch(domain -> domain.contains("DO_NOT_EXPORT_SECRET")));
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    void shouldRemainSafeWithoutActiveRecording() {
        JfrRpcObserver observer =
                new JfrRpcObserver(Duration.ofMillis(1));

        assertDoesNotThrow(() ->
                observer.onClientAttemptCompleted(
                        new ServiceKey(
                                "demo.Service",
                                "1.0.0",
                                "default"),
                        1,
                        new RpcEndpoint(
                                "127.0.0.1",
                                19090),
                        1,
                        Duration.ofMillis(2).toNanos(),
                        RpcStatus.OK,
                        null));
        assertDoesNotThrow(() ->
                observer.onClientRetryScheduled(
                        new ServiceKey(
                                "demo.Service",
                                "1.0.0",
                                "default"),
                        1,
                        2,
                        10L,
                        new IllegalStateException(
                                "retry")));
    }
}
