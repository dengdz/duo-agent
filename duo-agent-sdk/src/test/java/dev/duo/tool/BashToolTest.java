package dev.duo.tool;

import dev.duo.core.llm.ToolRegistryImpl;
import dev.duo.model.llm.ContentBlock;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link BashTool} 的测试（真实进程执行，仅限 POSIX 平台）。
 *
 * @author zhangyl
 * @date 2026-08-18
 */
class BashToolTest {

    @Test
    void testExecute_whenSimpleCommand_thenReturnsOutput() {
        assumePosix();
        var registry = new ToolRegistryImpl();
        registry.register(new BashTool().getDefinition());

        var result = registry.execute("bash", java.util.Map.of("command", "echo hello"));
        printResult("simple command", result);

        assertFalse(result.isError());
        var text = ((ContentBlock.Text) result.content().getFirst()).text();
        assertEquals("hello\n", text);
    }

    @Test
    void testExecute_whenCwdSpecified_thenRunsInDirectory() throws IOException {
        assumePosix();
        var tmp = Files.createTempDirectory("bash-test");
        Files.writeString(tmp.resolve("marker.txt"), "in here");

        var registry = new ToolRegistryImpl();
        registry.register(new BashTool().getDefinition());

        var result = registry.execute("bash", java.util.Map.of(
                "command", "cat marker.txt",
                "cwd", tmp.toString()
        ));
        printResult("specified cwd", result);

        assertFalse(result.isError());
        var text = ((ContentBlock.Text) result.content().getFirst()).text();
        assertTrue(text.contains("in here"), text);
    }

    @Test
    void testExecute_whenNonZeroExit_thenIncludesExitCode() {
        assumePosix();
        var registry = new ToolRegistryImpl();
        registry.register(new BashTool().getDefinition());

        var result = registry.execute("bash", java.util.Map.of("command", "exit 42"));
        printResult("non-zero exit", result);

        assertFalse(result.isError()); // 工具本身没抛异常，结果中带上退出码
        var text = ((ContentBlock.Text) result.content().getFirst()).text();
        assertTrue(text.contains("退出码 42"), text);
    }

    @Test
    void testExecute_whenCommandEmpty_thenReturnsError() {
        assumePosix();
        var registry = new ToolRegistryImpl();
        registry.register(new BashTool().getDefinition());

        var result = registry.execute("bash", java.util.Map.of("command", ""));
        printResult("empty command", result);

        assertFalse(result.isError());
        var text = ((ContentBlock.Text) result.content().getFirst()).text();
        assertTrue(text.contains("缺少 command"), text);
    }

    private static void printResult(String scenario, dev.duo.model.llm.ToolExecutionResult result) {
        var text = ((ContentBlock.Text) result.content().getFirst()).text();
        System.out.printf("[BashTool][%s] %s%n", scenario, text);
    }

    private static void assumePosix() {
        Assumptions.assumeTrue(
                !System.getProperty("os.name", "").toLowerCase().contains("win"),
                "需要 POSIX shell（/bin/sh）");
    }
}