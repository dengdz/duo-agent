package dev.duo.tool;

import dev.duo.model.llm.ToolExecution;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * GlobTool 测试。
 *
 * @author zhangyl
 * @date 2026-08-19
 */
class GlobToolTest {

    private final GlobTool tool = new GlobTool();

    private static String textOf(dev.duo.model.llm.ToolExecutionResult result) {
        return ((dev.duo.model.llm.ContentBlock.Text) result.content().getFirst()).text();
    }

    @Test
    void testExecute_whenPatternWithoutSlash_thenMatchesAnyDepth(@TempDir Path tempDir) throws Exception {
        Files.writeString(tempDir.resolve("root.java"), "class A {}\n");
        Files.createDirectories(tempDir.resolve("src/deep"));
        Files.writeString(tempDir.resolve("src/deep/Nested.java"), "class B {}\n");
        Files.writeString(tempDir.resolve("src/note.txt"), "text\n");

        var result = tool.getDefinition().executor().execute(ToolExecution.of(Map.of(
                "pattern", "*.java",
                "path", tempDir.toString()
        )));

        var text = textOf(result);
        assertTrue(text.contains("root.java"), text);
        assertTrue(text.contains("Nested.java"), text);
        assertFalse(text.contains("note.txt"), text);
    }

    @Test
    void testExecute_whenPatternWithSlash_thenMatchesRelativePath(@TempDir Path tempDir) throws Exception {
        Files.createDirectories(tempDir.resolve("src/main"));
        Files.writeString(tempDir.resolve("src/main/App.java"), "class App {}\n");
        Files.createDirectories(tempDir.resolve("test"));
        Files.writeString(tempDir.resolve("test/AppTest.java"), "class AppTest {}\n");

        var result = tool.getDefinition().executor().execute(ToolExecution.of(Map.of(
                "pattern", "src/**/*.java",
                "path", tempDir.toString()
        )));

        var text = textOf(result);
        assertTrue(text.contains("App.java"), text);
        assertFalse(text.contains("AppTest.java"), text);
    }

    @Test
    void testExecute_whenResultsSorted_thenNewestFirst(@TempDir Path tempDir) throws Exception {
        var older = tempDir.resolve("older.java");
        var newer = tempDir.resolve("newer.java");
        Files.writeString(older, "old\n");
        Files.writeString(newer, "new\n");
        // 保证 newer 的修改时间严格晚于 older
        Files.setLastModifiedTime(newer, java.nio.file.attribute.FileTime.fromMillis(
                Files.getLastModifiedTime(older).toMillis() + 60_000));

        var result = tool.getDefinition().executor().execute(ToolExecution.of(Map.of(
                "pattern", "*.java",
                "path", tempDir.toString()
        )));

        var text = textOf(result);
        var olderIndex = text.indexOf("older.java");
        var newerIndex = text.indexOf("newer.java");
        assertTrue(newerIndex >= 0 && olderIndex > newerIndex, text);
    }

    @Test
    void testExecute_whenVcsDirectory_thenExcluded(@TempDir Path tempDir) throws Exception {
        Files.createDirectories(tempDir.resolve(".git"));
        Files.writeString(tempDir.resolve(".git/HEAD.java"), "x\n");
        Files.writeString(tempDir.resolve("ok.java"), "y\n");

        var result = tool.getDefinition().executor().execute(ToolExecution.of(Map.of(
                "pattern", "*.java",
                "path", tempDir.toString()
        )));

        var text = textOf(result);
        assertFalse(text.contains("HEAD.java"), text);
        assertTrue(text.contains("ok.java"), text);
    }

    @Test
    void testExecute_whenNoMatch_thenNoFilesFound(@TempDir Path tempDir) throws Exception {
        Files.writeString(tempDir.resolve("a.txt"), "content\n");

        var result = tool.getDefinition().executor().execute(ToolExecution.of(Map.of(
                "pattern", "*.rs",
                "path", tempDir.toString()
        )));

        assertEquals("No files found", textOf(result));
    }

    @Test
    void testExecute_whenResultsExceedCap_thenReportsTotal(@TempDir Path tempDir) throws Exception {
        for (var i = 0; i < 120; i++) {
            Files.writeString(tempDir.resolve("f" + i + ".txt"), "x\n");
        }

        var result = tool.getDefinition().executor().execute(ToolExecution.of(Map.of(
                "pattern", "*.txt",
                "path", tempDir.toString()
        )));

        var text = textOf(result);
        assertTrue(text.contains("Showing 100 of 120 paths"), text);
    }

    @Test
    void testExecute_whenMissingPath_thenFriendlyError(@TempDir Path tempDir) throws Exception {
        var result = tool.getDefinition().executor().execute(ToolExecution.of(Map.of(
                "pattern", "*.java",
                "path", tempDir.resolve("no-such-dir").toString()
        )));

        assertTrue(textOf(result).startsWith("错误：目录不存在"));
    }

    @Test
    void testExecute_whenBlankPattern_thenFriendlyError(@TempDir Path tempDir) throws Exception {
        var result = tool.getDefinition().executor().execute(ToolExecution.of(Map.of(
                "pattern", "  ",
                "path", tempDir.toString()
        )));

        assertTrue(textOf(result).startsWith("错误：缺少 pattern"));
    }
}
