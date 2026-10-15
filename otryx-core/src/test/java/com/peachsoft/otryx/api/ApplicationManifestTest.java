package com.peachsoft.otryx.api;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/**
 * 验证 Provider 绑定后的 Manifest 一致性、身份隔离及容量边界。
 *
 * @Author OpenAI
 * @Version 1.0.0-SNAPSHOT
 * @CreateTime 2026/10/15 17:48
 */
class ApplicationManifestTest {

    private static final ApplicationKey APPLICATION =
            new ApplicationKey("default", "order-service");

    @Test
    void shouldResolveOnlyActualRpcMethodsFromContract() throws Exception {
        ServiceKey key = key(GreetingService.class, "1.0.0", "default");
        ExportedService service = ExportedService.fromContract(key, GreetingService.class);
        assertEquals(RpcIds.serviceId(key), service.serviceId());
        assertEquals(Set.of(
                RpcIds.methodId(GreetingService.class.getMethod("greet", String.class)),
                RpcIds.methodId(GreetingService.class.getMethod("overloaded", int.class))),
                service.methodIds());
        assertEquals(RpcSchemaFingerprint.serviceFingerprint(key, GreetingService.class),
                service.schemaFingerprint());
        assertThrows(UnsupportedOperationException.class, () -> service.methodIds().clear());
        assertThrows(IllegalArgumentException.class,
                () -> ExportedService.fromContract(key, PaymentService.class));
    }

    @Test
    void manifestRevisionMustIgnoreInputOrderAndSetIteration() {
        ExportedService first = export(GreetingService.class, "1.0.0", "blue");
        ExportedService second = export(GreetingService.class, "2.0.0", "blue");
        ExportedService third = export(GreetingService.class, "1.0.0", "green");
        ExportedService fourth = export(PaymentService.class, "1.0.0", "default");

        ApplicationManifest a = ApplicationManifest.create(
                APPLICATION, List.of(first, second, third, fourth));
        ApplicationManifest b = ApplicationManifest.create(
                APPLICATION, List.of(fourth, third, first, second));

        assertEquals(a.revision(), b.revision());
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        assertArrayEquals(a.canonicalBytes(), b.canonicalBytes());
        assertEquals(4, a.services().size());
        assertEquals(ApplicationManifestCodec.FORMAT_VERSION, a.formatVersion());
        assertEquals(ApplicationManifestCodec.WIRE_PROTOCOL_VERSION, a.wireProtocolVersion());
        assertEquals(RpcSchemaFingerprint.SCHEMA_VERSION, a.rpcSchemaVersion());
        assertTrue(a.revision().startsWith(ApplicationManifest.REVISION_PREFIX));
        assertEquals(ApplicationManifest.REVISION_PREFIX.length() + 64, a.revision().length());
        assertThrows(UnsupportedOperationException.class, () -> a.services().clear());
        assertEquals(a, a.requireReference(APPLICATION, a.revision()));
    }

    @Test
    void changingApplicationOrContractsMustChangeRevision() {
        ExportedService first = export(GreetingService.class, "1.0.0", "default");
        ExportedService second = export(PaymentService.class, "1.0.0", "default");
        ApplicationManifest v1 = ApplicationManifest.create(APPLICATION, List.of(first));
        ApplicationManifest v2 = ApplicationManifest.create(APPLICATION, List.of(first, second));
        ApplicationManifest differentApplication = ApplicationManifest.create(
                new ApplicationKey("other", "order-service"), List.of(first));
        ApplicationManifest changedVersion = ApplicationManifest.create(
                APPLICATION, List.of(export(GreetingService.class, "2.0.0", "default")));
        assertNotEquals(v1.revision(), v2.revision());
        assertNotEquals(v1.revision(), differentApplication.revision());
        assertNotEquals(v1.revision(), changedVersion.revision());
        assertThrows(IllegalArgumentException.class,
                () -> v1.requireReference(APPLICATION, v2.revision()));
        assertThrows(IllegalArgumentException.class,
                () -> v1.requireReference(differentApplication.application(), v1.revision()));
    }

    @Test
    void manifestMustCopyInputAndEncodedBytesDefensively() {
        List<ExportedService> input = new ArrayList<>();
        input.add(export(GreetingService.class, "1.0.0", "default"));
        ApplicationManifest result = ApplicationManifest.create(APPLICATION, input);
        String revision = result.revision();
        byte[] leaked = result.canonicalBytes();
        leaked[0] ^= (byte) 0xff;
        input.clear();
        assertEquals(1, result.services().size());
        assertEquals(revision, result.revision());
        assertNotEquals(leaked[0], result.canonicalBytes()[0]);
        assertEquals(result, ApplicationManifestCodec.decode(result.canonicalBytes()));
        assertNotNull(result.services().get(0).schemaFingerprint());
    }

    @Test
    void rejectInvalidServiceFieldsAndDuplicates() {
        ExportedService valid = export(GreetingService.class, "1.0.0", "default");
        assertThrows(IllegalArgumentException.class,
                () -> ApplicationManifest.create(APPLICATION, List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> ApplicationManifest.create(APPLICATION, List.of(valid, valid)));
        assertThrows(IllegalArgumentException.class,
                () -> new ExportedService(valid.serviceKey(), 0, Set.of(123),
                        valid.schemaFingerprint()));
        assertThrows(IllegalArgumentException.class,
                () -> new ExportedService(valid.serviceKey(), valid.serviceId(), Set.of(),
                        valid.schemaFingerprint()));
        assertThrows(IllegalArgumentException.class,
                () -> new ExportedService(valid.serviceKey(), valid.serviceId(), Set.of(7), "bad"));
        assertThrows(IllegalArgumentException.class,
                () -> new ExportedService(
                        new ServiceKey("demo.Service/unsafe", "1.0.0", "default"),
                        RpcIds.serviceId(new ServiceKey("demo.Service/unsafe", "1.0.0", "default")),
                        Set.of(7), "a".repeat(64)));
        assertThrows(IllegalArgumentException.class,
                () -> new ExportedService(
                        new ServiceKey("demo.Service", "1.0.0", "east:west"),
                        RpcIds.serviceId(new ServiceKey("demo.Service", "1.0.0", "east:west")),
                        Set.of(7), "a".repeat(64)));
        assertThrows(IllegalArgumentException.class,
                () -> ApplicationManifest.requireRevision("sha256:" + "A".repeat(64)));
        assertThrows(IllegalArgumentException.class,
                () -> ApplicationManifest.requireRevision("sha256:" + "0".repeat(63)));
    }

    @Test
    void rejectOversizedManifestBeforePersistingIt() {
        List<ExportedService> services = new ArrayList<>();
        Set<Integer> methods = IntStream.range(0, ExportedService.MAX_METHODS)
                .boxed()
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        for (int index = 0; index < 100; index++) {
            ServiceKey key = new ServiceKey("demo.service.Contract" + index, "1.0.0", "default");
            services.add(new ExportedService(
                    key, RpcIds.serviceId(key), methods, "a".repeat(64)));
        }
        assertThrows(IllegalArgumentException.class,
                () -> ApplicationManifest.create(APPLICATION, services));

        List<ExportedService> tooMany = new ArrayList<>();
        for (int index = 0; index <= ApplicationManifest.MAX_SERVICES; index++) {
            ServiceKey key = new ServiceKey("demo.service.Empty" + index, "1.0.0", "default");
            tooMany.add(new ExportedService(key, RpcIds.serviceId(key), Set.of(1), "b".repeat(64)));
        }
        assertThrows(IllegalArgumentException.class,
                () -> ApplicationManifest.create(APPLICATION, tooMany));
        assertFalse(tooMany.isEmpty());
    }

    private static ExportedService export(Class<?> api, String version, String group) {
        return ExportedService.fromContract(key(api, version, group), api);
    }

    private static ServiceKey key(Class<?> api, String version, String group) {
        return new ServiceKey(api.getName(), version, group);
    }

    interface GreetingService {
        String greet(String name);
        int overloaded(int count);
        default String local() {
            return "local";
        }
        static String utility() {
            return "utility";
        }
    }

    interface PaymentService {
        void pay(long cents);
    }
}
