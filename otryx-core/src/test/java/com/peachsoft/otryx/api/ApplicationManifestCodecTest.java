package com.peachsoft.otryx.api;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * 验证 Manifest 格式、严格解码及恶意输入边界。
 *
 * @Author OpenAI
 * @Version 1.0.0-SNAPSHOT
 * @CreateTime 2026/10/15 17:48
 */
class ApplicationManifestCodecTest {

    private static final ApplicationKey APPLICATION = new ApplicationKey("default", "orders");
    private static final int HEADER_MAGIC_OFFSET = 0;
    private static final int HEADER_FORMAT_OFFSET = 4;
    private static final int HEADER_WIRE_OFFSET = 8;
    private static final int HEADER_SCHEMA_OFFSET = 12;
    private static final int NAMESPACE_LENGTH_OFFSET = 16;
    private static final int NAMESPACE_FIRST_BYTE = 20;

    @Test
    void roundTripAndStableRevisionMustUseCanonicalBytes() {
        ApplicationManifest manifest = manifest();
        byte[] encoded = ApplicationManifestCodec.encode(manifest);
        ApplicationManifest decoded = ApplicationManifestCodec.decode(encoded);
        assertEquals(manifest.revision(), decoded.revision());
        assertEquals(manifest, decoded);
        assertArrayEquals(encoded, ApplicationManifestCodec.encode(decoded));
        encoded[0] ^= 0x1;
        assertNotEquals(encoded[0], ApplicationManifestCodec.encode(manifest)[0]);
    }

    @Test
    void shouldRejectMalformedVersionAndPayloadSize() {
        byte[] encoded = manifest().canonicalBytes();
        assertThrows(IllegalArgumentException.class, () -> ApplicationManifestCodec.decode(null));
        assertThrows(IllegalArgumentException.class, () -> ApplicationManifestCodec.decode(new byte[0]));
        assertThrows(IllegalArgumentException.class,
                () -> ApplicationManifestCodec.decode(new byte[ApplicationManifest.MAX_CANONICAL_BYTES + 1]));
        assertThrows(IllegalArgumentException.class,
                () -> ApplicationManifestCodec.decode(Arrays.copyOf(encoded, encoded.length - 1)));
        assertThrows(IllegalArgumentException.class,
                () -> ApplicationManifestCodec.decode(Arrays.copyOf(encoded, encoded.length + 1)));

        assertInvalidInt(encoded, HEADER_MAGIC_OFFSET, 0);
        assertInvalidInt(encoded, HEADER_FORMAT_OFFSET, ApplicationManifestCodec.FORMAT_VERSION + 1);
        assertInvalidInt(encoded, HEADER_WIRE_OFFSET, ApplicationManifestCodec.WIRE_PROTOCOL_VERSION + 1);
        assertInvalidInt(encoded, HEADER_SCHEMA_OFFSET, RpcSchemaFingerprint.SCHEMA_VERSION + 1);
        assertInvalidInt(encoded, NAMESPACE_LENGTH_OFFSET, Integer.MAX_VALUE);
    }

    @Test
    void shouldRejectMalformedUtf8AndNoncanonicalMethodOrdering() {
        byte[] encoded = manifest().canonicalBytes();
        byte[] invalidUtf8 = encoded.clone();
        invalidUtf8[NAMESPACE_FIRST_BYTE] = (byte) 0xff;
        assertThrows(IllegalArgumentException.class,
                () -> ApplicationManifestCodec.decode(invalidUtf8));

        byte[] swapped = encoded.clone();
        int a = ByteBuffer.wrap(swapped).getInt(swapped.length - 8);
        int b = ByteBuffer.wrap(swapped).getInt(swapped.length - 4);
        ByteBuffer.wrap(swapped).putInt(swapped.length - 8, b).putInt(swapped.length - 4, a);
        assertThrows(IllegalArgumentException.class,
                () -> ApplicationManifestCodec.decode(swapped));

        byte[] duplicate = encoded.clone();
        ByteBuffer.wrap(duplicate).putInt(duplicate.length - 4, a);
        assertThrows(IllegalArgumentException.class,
                () -> ApplicationManifestCodec.decode(duplicate));
    }

    @Test
    void shouldRejectNoncanonicalServiceOrdering() {
        ApplicationManifest original = ApplicationManifest.create(APPLICATION, List.of(
                service("app.a.One", 13), service("app.b.Two", 29)));
        byte[] bytes = original.canonicalBytes();
        byte[] swappedNames = bytes.clone();
        int firstIndex = indexOf(swappedNames, "app.a.One".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        int secondIndex = indexOf(swappedNames, "app.b.Two".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        if (firstIndex < 0 || secondIndex < 0) {
            throw new AssertionError("Expected service names in manifest");
        }
        byte[] first = Arrays.copyOfRange(swappedNames, firstIndex, firstIndex + 9);
        byte[] second = Arrays.copyOfRange(swappedNames, secondIndex, secondIndex + 9);
        System.arraycopy(second, 0, swappedNames, firstIndex, second.length);
        System.arraycopy(first, 0, swappedNames, secondIndex, first.length);
        assertThrows(IllegalArgumentException.class,
                () -> ApplicationManifestCodec.decode(swappedNames));
    }

    private static int indexOf(byte[] data, byte[] target) {
        for (int i = 0; i <= data.length - target.length; i++) {
            boolean matches = true;
            for (int j = 0; j < target.length; j++) {
                if (data[i + j] != target[j]) {
                    matches = false;
                    break;
                }
            }
            if (matches) {
                return i;
            }
        }
        return -1;
    }

    private static void assertInvalidInt(byte[] original, int offset, int value) {
        byte[] bytes = original.clone();
        ByteBuffer.wrap(bytes).putInt(offset, value);
        assertThrows(IllegalArgumentException.class, () -> ApplicationManifestCodec.decode(bytes));
    }

    private static ApplicationManifest manifest() {
        return ApplicationManifest.create(APPLICATION, List.of(service("app.Example", 1, 2)));
    }

    private static ExportedService service(String name, int... methodIds) {
        ServiceKey key = new ServiceKey(name, "1.0.0", "default");
        Set<Integer> methods = Arrays.stream(methodIds).boxed()
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        return new ExportedService(key, RpcIds.serviceId(key), methods, "f".repeat(64));
    }
}
