package dev.dsh.core.llm.tools;

import dev.dsh.core.llm.ToolRegistryImpl;
import dev.dsh.model.llm.ContentBlock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class FileToolTest {

    @TempDir
    Path tempDir;

    @Test
    void 读写文件往返() {
        var registry = new ToolRegistryImpl();
        registry.register(new FileWriteTool().getDefinition());
        registry.register(new FileReadTool().getDefinition());

        var path = tempDir.resolve("test.txt").toString();
        var writeResult = registry.execute("file_write", java.util.Map.of(
                "path", path,
                "content", "hello world"
        ));
        assertFalse(writeResult.isError(), textOf(writeResult));

        var readResult = registry.execute("file_read", java.util.Map.of("path", path));
        assertFalse(readResult.isError(), textOf(readResult));
        assertEquals("hello world", textOf(readResult));
    }

    @Test
    void 读取不存在的文件返回错误() {
        var registry = new ToolRegistryImpl();
        registry.register(new FileReadTool().getDefinition());

        var result = registry.execute("file_read", java.util.Map.of(
                "path", tempDir.resolve("missing.txt").toString()
        ));

        assertFalse(result.isError());
        assertTrue(textOf(result).contains("文件不存在"), textOf(result));
    }

    @Test
    void 覆盖已存在文件() {
        var registry = new ToolRegistryImpl();
        registry.register(new FileWriteTool().getDefinition());
        registry.register(new FileReadTool().getDefinition());

        var path = tempDir.resolve("overwrite.txt").toString();
        registry.execute("file_write", java.util.Map.of("path", path, "content", "old"));
        registry.execute("file_write", java.util.Map.of("path", path, "content", "new"));

        var result = registry.execute("file_read", java.util.Map.of("path", path));
        assertEquals("new", textOf(result));
    }

    private static String textOf(dev.dsh.model.llm.ToolExecutionResult result) {
        return ((ContentBlock.Text) result.content().getFirst()).text();
    }
}
