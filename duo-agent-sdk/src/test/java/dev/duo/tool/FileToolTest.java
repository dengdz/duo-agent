package dev.duo.tool;

import dev.duo.core.llm.ToolRegistryImpl;
import dev.duo.model.llm.ContentBlock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link FileWriteTool} 与 {@link FileReadTool} 的测试。
 *
 * @author zhangyl
 * @date 2026-08-18
 */
class FileToolTest {

    @TempDir
    Path tempDir;

    @Test
    void testWriteRead_whenValidPath_thenRoundTripsContent() {
        var registry = new ToolRegistryImpl();
        registry.register(new FileWriteTool().getDefinition());
        registry.register(new FileReadTool().getDefinition());

        var path = tempDir.resolve("test.txt").toString();
        var writeResult = registry.execute("file_write", java.util.Map.of(
                "path", path,
                "content", "hello world"
        ));
        printResult("write file", writeResult);
        assertFalse(writeResult.isError(), textOf(writeResult));

        var readResult = registry.execute("file_read", java.util.Map.of("path", path));
        printResult("read file", readResult);
        assertFalse(readResult.isError(), textOf(readResult));
        assertEquals("hello world", textOf(readResult));
    }

    @Test
    void testRead_whenFileMissing_thenReturnsError() {
        var registry = new ToolRegistryImpl();
        registry.register(new FileReadTool().getDefinition());

        var result = registry.execute("file_read", java.util.Map.of(
                "path", tempDir.resolve("missing.txt").toString()
        ));
        printResult("read missing file", result);

        assertFalse(result.isError());
        assertTrue(textOf(result).contains("文件不存在"), textOf(result));
    }

    @Test
    void testWrite_whenFileExists_thenOverwrites() {
        var registry = new ToolRegistryImpl();
        registry.register(new FileWriteTool().getDefinition());
        registry.register(new FileReadTool().getDefinition());

        var path = tempDir.resolve("overwrite.txt").toString();
        registry.execute("file_write", java.util.Map.of("path", path, "content", "old"));
        registry.execute("file_write", java.util.Map.of("path", path, "content", "new"));

        var result = registry.execute("file_read", java.util.Map.of("path", path));
        printResult("read overwritten file", result);
        assertEquals("new", textOf(result));
    }

    private static void printResult(String scenario, dev.duo.model.llm.ToolExecutionResult result) {
        System.out.printf("[FileTool][%s] %s%n", scenario, textOf(result));
    }

    private static String textOf(dev.duo.model.llm.ToolExecutionResult result) {
        return ((ContentBlock.Text) result.content().getFirst()).text();
    }
}