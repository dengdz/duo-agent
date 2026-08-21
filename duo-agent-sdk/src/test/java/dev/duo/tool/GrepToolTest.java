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
 * GrepTool 测试。
 *
 * @author zhangyl
 * @date 2026-08-19
 */
class GrepToolTest {

    private final GrepTool tool = new GrepTool();

    private static String textOf(dev.duo.model.llm.ToolExecutionResult result) {
        return ((dev.duo.model.llm.ContentBlock.Text) result.content().getFirst()).text();
    }

    @Test
    void testExecute_whenMatchesInMultipleFiles_thenGroupedByFileWithLineNumbers(
            @TempDir Path tempDir) throws Exception {
        Files.writeString(tempDir.resolve("a.txt"), "hello world\nno match here\nhello again\n");
        Files.writeString(tempDir.resolve("b.txt"), "hello from b\n");

        var result = tool.getDefinition().executor().execute(ToolExecution.of(Map.of(
                "pattern", "hello",
                "path", tempDir.toString()
        )));

        var text = textOf(result);
        assertTrue(text.contains("Found 3 matches"), text);
        assertTrue(text.contains("a.txt"), text);
        assertTrue(text.contains("Line 1: hello world"), text);
        assertTrue(text.contains("Line 3: hello again"), text);
        assertTrue(text.contains("b.txt"), text);
        assertTrue(text.contains("Line 1: hello from b"), text);
    }

    @Test
    void testExecute_whenPatternWithGroups_thenRegexSemantics(@TempDir Path tempDir) throws Exception {
        Files.writeString(tempDir.resolve("code.txt"), "foo(1)\nbar(2)\nfoo(3)\n");

        var result = tool.getDefinition().executor().execute(ToolExecution.of(Map.of(
                "pattern", "foo\\(\\d+\\)",
                "path", tempDir.toString()
        )));

        var text = textOf(result);
        assertTrue(text.contains("Found 2 matches"), text);
        assertFalse(text.contains("bar"), text);
    }

    @Test
    void testExecute_whenIncludeFilter_thenOnlyMatchingFiles(@TempDir Path tempDir) throws Exception {
        Files.writeString(tempDir.resolve("A.java"), "needle\n");
        Files.writeString(tempDir.resolve("B.txt"), "needle\n");

        var result = tool.getDefinition().executor().execute(ToolExecution.of(Map.of(
                "pattern", "needle",
                "path", tempDir.toString(),
                "include", "*.java"
        )));

        var text = textOf(result);
        assertTrue(text.contains("Found 1 match"), text);
        assertTrue(text.contains("A.java"), text);
        assertFalse(text.contains("B.txt"), text);
    }

    @Test
    void testExecute_whenNoMatch_thenNoMatchesFound(@TempDir Path tempDir) throws Exception {
        Files.writeString(tempDir.resolve("a.txt"), "nothing relevant\n");

        var result = tool.getDefinition().executor().execute(ToolExecution.of(Map.of(
                "pattern", "zzz-missing",
                "path", tempDir.toString()
        )));

        assertEquals("No matches found", textOf(result));
    }

    @Test
    void testExecute_whenInvalidRegex_thenFriendlyError() throws Exception {
        var result = tool.getDefinition().executor().execute(ToolExecution.of(Map.of("pattern", "[unclosed")));

        var text = textOf(result);
        assertTrue(text.startsWith("错误：正则表达式非法"), text);
    }

    @Test
    void testExecute_whenInvalidInclude_thenFriendlyError(@TempDir Path tempDir) throws Exception {
        var result = tool.getDefinition().executor().execute(ToolExecution.of(Map.of(
                "pattern", "x",
                "path", tempDir.toString(),
                "include", "*.java,*.txt"
        )));

        var text = textOf(result);
        assertTrue(text.startsWith("错误：include"), text);
    }

    @Test
    void testExecute_whenNegatedInclude_thenRejected(@TempDir Path tempDir) throws Exception {
        var result = tool.getDefinition().executor().execute(ToolExecution.of(Map.of(
                "pattern", "x",
                "path", tempDir.toString(),
                "include", "!*.log"
        )));

        var text = textOf(result);
        assertTrue(text.startsWith("错误：include"), text);
    }

    @Test
    void testExecute_whenMatchesExceedCap_thenReportsTotalAndTruncates(@TempDir Path tempDir) throws Exception {
        var sb = new StringBuilder();
        for (var i = 1; i <= 300; i++) {
            sb.append("hit ").append(i).append('\n');
        }
        Files.writeString(tempDir.resolve("big.txt"), sb.toString());

        var result = tool.getDefinition().executor().execute(ToolExecution.of(Map.of(
                "pattern", "hit",
                "path", tempDir.toString()
        )));

        var text = textOf(result);
        assertTrue(text.contains("Found 250 of 300 matches"), text);
        assertTrue(text.contains("结果已截断"), text);
        assertTrue(text.contains("Line 250:"), text);
        assertFalse(text.contains("Line 251:"), text);
    }

    @Test
    void testExecute_whenBinaryFile_thenSkipped(@TempDir Path tempDir) throws Exception {
        Files.write(tempDir.resolve("blob.bin"), new byte[]{0x61, 0x00, 0x62});
        Files.writeString(tempDir.resolve("text.txt"), "needle\n");

        var result = tool.getDefinition().executor().execute(ToolExecution.of(Map.of(
                "pattern", "needle|blob",
                "path", tempDir.toString()
        )));

        var text = textOf(result);
        assertTrue(text.contains("Found 1 match"), text);
        assertFalse(text.contains("blob.bin"), text);
    }

    @Test
    void testExecute_whenVcsDirectory_thenExcluded(@TempDir Path tempDir) throws Exception {
        Files.createDirectories(tempDir.resolve(".git"));
        Files.writeString(tempDir.resolve(".git/config"), "needle\n");
        Files.writeString(tempDir.resolve("ok.txt"), "needle\n");

        var result = tool.getDefinition().executor().execute(ToolExecution.of(Map.of(
                "pattern", "needle",
                "path", tempDir.toString()
        )));

        var text = textOf(result);
        assertTrue(text.contains("Found 1 match"), text);
        assertFalse(text.contains(".git"), text);
    }

    @Test
    void testExecute_whenPathIsFile_thenSearchSingleFile(@TempDir Path tempDir) throws Exception {
        var file = tempDir.resolve("single.txt");
        Files.writeString(file, "needle\n");

        var result = tool.getDefinition().executor().execute(ToolExecution.of(Map.of(
                "pattern", "needle",
                "path", file.toString()
        )));

        assertTrue(textOf(result).contains("Found 1 match"));
    }
}
