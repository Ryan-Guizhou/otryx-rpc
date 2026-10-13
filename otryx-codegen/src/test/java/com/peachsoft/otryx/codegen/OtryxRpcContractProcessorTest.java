package com.peachsoft.otryx.codegen;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 真实 JavaCompiler 注解处理器测试，验证继承、重载与错误诊断。
 *
 * @Author OpenAI
 * @Version 1.0.0-SNAPSHOT
 * @CreateTime 2026/10/12 19:28
 */
class OtryxRpcContractProcessorTest {

    @TempDir
    Path temp;

    @Test
    void generatedAndFallbackMustShareEffectiveAbstractMethods()
            throws Exception {
        Compilation result = compile(
                "valid",
                Map.of(
                        "Base", "public interface Base { int inherited(long value); }",
                        "Greeting", """
                                @com.peachsoft.otryx.api.OtryxRpcContract
                                public interface Greeting extends Base {
                                    String echo(String value);
                                    String echo(int value);
                                    default String local() { return "local"; }
                                    static int helper() { return 0; }
                                }
                                """));
        assertTrue(result.success(), result.messages());
        Path generated = result.sources().resolve(
                "fixture/GreetingOtryxRpcClientFactory.java");
        assertTrue(Files.exists(generated));
        String content = Files.readString(generated);
        assertTrue(content.contains("inherited("));
        assertTrue(content.contains("echo("));
        assertFalse(content.contains(" local("));
        assertFalse(content.contains(" helper("));
    }

    @Test
    void unsupportedGenericMethodsMustFailAtCompilation() throws Exception {
        Compilation result = compile(
                "generic",
                Map.of("Bad", """
                        @com.peachsoft.otryx.api.OtryxRpcContract
                        public interface Bad {
                            <T> T echo(T value);
                        }
                        """));
        assertFalse(result.success());
        assertTrue(result.messages().contains(
                "Generic RPC methods are not supported"));
    }

    @Test
    void nestedContractMustFailAtCompilation() throws Exception {
        Compilation result = compile(
                "nested",
                Map.of("Holder", """
                        public class Holder {
                            @com.peachsoft.otryx.api.OtryxRpcContract
                            public interface Nested {
                                String hello();
                            }
                        }
                        """));
        assertFalse(result.success());
        assertTrue(result.messages().contains(
                "Nested RPC service interfaces are not supported"));
    }

    private Compilation compile(String name, Map<String, String> units)
            throws Exception {
        Path root = temp.resolve(name);
        Path sources = root.resolve("src/fixture");
        Path outputs = root.resolve("classes");
        Path generated = root.resolve("generated");
        Files.createDirectories(sources);
        Files.createDirectories(outputs);
        Files.createDirectories(generated);

        List<Path> inputs = new ArrayList<>();
        for (Map.Entry<String, String> entry : units.entrySet()) {
            Path file = sources.resolve(entry.getKey() + ".java");
            Files.writeString(file, "package fixture;\n" + entry.getValue());
            inputs.add(file);
        }
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        DiagnosticCollector<JavaFileObject> diagnostics =
                new DiagnosticCollector<>();
        try (StandardJavaFileManager files = compiler.getStandardFileManager(
                diagnostics, null, null)) {
            Iterable<? extends JavaFileObject> compilationUnits =
                    files.getJavaFileObjectsFromPaths(inputs);
            JavaCompiler.CompilationTask task = compiler.getTask(
                    null,
                    files,
                    diagnostics,
                    List.of(
                            "-classpath", System.getProperty("java.class.path"),
                            "-d", outputs.toString(),
                            "-s", generated.toString(),
                            "-proc:only"),
                    null,
                    compilationUnits);
            task.setProcessors(List.of(new OtryxRpcContractProcessor()));
            boolean success = Boolean.TRUE.equals(task.call());
            StringBuilder messages = new StringBuilder();
            for (Diagnostic<? extends JavaFileObject> error
                    : diagnostics.getDiagnostics()) {
                messages.append(error.getMessage(null)).append('\n');
            }
            return new Compilation(success, generated, messages.toString());
        }
    }

    private record Compilation(boolean success, Path sources, String messages) {
    }
}
