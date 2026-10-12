package com.peachsoft.otryx.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 使用不同 ClassLoader 中同名契约证明 DTO 结构与泛型差异能够被探测。
 *
 * @Author OpenAI
 * @Version 1.0.0-SNAPSHOT
 * @CreateTime 2026/10/12 19:07
 */
class RpcSchemaFingerprintStructuralTest {

    private static final ServiceKey SAME_CONTRACT =
            new ServiceKey("demo.Contract", "1.0.0", "default");

    @TempDir
    Path temporary;

    @Test
    void sameBinaryNameDtoFieldChangeMustChangeFingerprint()
            throws Exception {
        Class<?> before = compile(
                "simple-old",
                Map.of(
                        "Contract", "public interface Contract { Envelope fetch(); }",
                        "Envelope", "public record Envelope(String value) { }"));
        Class<?> after = compile(
                "simple-new",
                Map.of(
                        "Contract", "public interface Contract { Envelope fetch(); }",
                        "Envelope", "public record Envelope(String value, long revision) { }"));

        String oldFingerprint =
                RpcSchemaFingerprint.serviceFingerprint(SAME_CONTRACT, before);
        String newFingerprint =
                RpcSchemaFingerprint.serviceFingerprint(SAME_CONTRACT, after);
        assertEquals(64, oldFingerprint.length());
        assertNotEquals(oldFingerprint, newFingerprint);
    }

    @Test
    void genericDtoFieldChangeMustChangeFingerprint() throws Exception {
        Map<String, String> unchanged = Map.of(
                "Contract", "public interface Contract { Box<Payload> fetch(); }",
                "Payload", "public record Payload(String name) { }");
        Class<?> before = compile(
                "generic-old",
                combine(unchanged, "public record Box<T>(T payload) { }"));
        Class<?> after = compile(
                "generic-new",
                combine(unchanged,
                        "public record Box<T>(T payload, long revision) { }"));

        assertNotEquals(
                RpcSchemaFingerprint.serviceFingerprint(SAME_CONTRACT, before),
                RpcSchemaFingerprint.serviceFingerprint(SAME_CONTRACT, after));
    }

    @Test
    void enumConstantsChangeMustChangeFingerprint() throws Exception {
        Class<?> before = compile(
                "enum-old",
                Map.of(
                        "Contract", "public interface Contract { Flavor fetch(); }",
                        "Flavor", "public enum Flavor { ORIGINAL }"));
        Class<?> after = compile(
                "enum-new",
                Map.of(
                        "Contract", "public interface Contract { Flavor fetch(); }",
                        "Flavor", "public enum Flavor { ORIGINAL, EXTRA }"));
        assertNotEquals(
                RpcSchemaFingerprint.serviceFingerprint(SAME_CONTRACT, before),
                RpcSchemaFingerprint.serviceFingerprint(SAME_CONTRACT, after));
    }

    private static Map<String, String> combine(
            Map<String, String> fixed, String box) {
        java.util.Map<String, String> sources = new java.util.HashMap<>(fixed);
        sources.put("Box", box);
        return Map.copyOf(sources);
    }

    private Class<?> compile(String name, Map<String, String> classes)
            throws Exception {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "Tests require a JDK with javac");
        Path output = temporary.resolve(name);
        Path pkg = output.resolve("demo");
        Files.createDirectories(pkg);
        List<String> args = new ArrayList<>();
        args.add("-d");
        args.add(output.toString());
        for (Map.Entry<String, String> entry : classes.entrySet()) {
            Path source = pkg.resolve(entry.getKey() + ".java");
            Files.writeString(source, "package demo; " + entry.getValue());
            args.add(source.toString());
        }
        assertEquals(0, compiler.run(null, null, null, args.toArray(String[]::new)));
        URLClassLoader loader = new URLClassLoader(
                new URL[] {output.toUri().toURL()},
                ClassLoader.getPlatformClassLoader());
        return Class.forName("demo.Contract", true, loader);
    }
}
