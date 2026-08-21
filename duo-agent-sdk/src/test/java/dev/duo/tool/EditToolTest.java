package dev.duo.tool;

import dev.duo.model.llm.ToolExecution;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * EditTool 测试。
 *
 * @author zhangyl
 * @date 2026-08-19
 */
class EditToolTest {

    private final EditTool tool = new EditTool();

    private static String textOf(dev.duo.model.llm.ToolExecutionResult result) {
        return ((dev.duo.model.llm.ContentBlock.Text) result.content().getFirst()).text();
    }

    @Test
    void testExecute_whenStrReplaceUnique_thenFileUpdated(@TempDir Path tempDir) throws Exception {
        var file = tempDir.resolve("code.txt");
        Files.writeString(file, "int old = 1;\nint keep = 2;\n");

        var result = tool.getDefinition().executor().execute(ToolExecution.of(Map.of(
                "command", "str_replace",
                "path", file.toString(),
                "old_str", "int old = 1;",
                "new_str", "int updated = 1;"
        )));

        assertEquals("int updated = 1;\nint keep = 2;\n", Files.readString(file));
        assertTrue(textOf(result).startsWith("文件已编辑成功"), textOf(result));
    }

    @Test
    void testExecute_whenOldStrMissing_thenRefused(@TempDir Path tempDir) throws Exception {
        var file = tempDir.resolve("code.txt");
        Files.writeString(file, "content\n");

        var result = tool.getDefinition().executor().execute(ToolExecution.of(Map.of(
                "command", "str_replace",
                "path", file.toString(),
                "old_str", "not-present",
                "new_str", "x"
        )));

        var text = textOf(result);
        assertTrue(text.startsWith("未执行替换"), text);
        assertEquals("content\n", Files.readString(file));
    }

    @Test
    void testExecute_whenOldStrAmbiguous_thenConflictLinesReported(@TempDir Path tempDir) throws Exception {
        var file = tempDir.resolve("code.txt");
        Files.writeString(file, "dup\nmiddle\ndup\n");

        var result = tool.getDefinition().executor().execute(ToolExecution.of(Map.of(
                "command", "str_replace",
                "path", file.toString(),
                "old_str", "dup",
                "new_str", "x"
        )));

        var text = textOf(result);
        assertTrue(text.startsWith("未执行替换"), text);
        assertTrue(text.contains("2 次"), text);
        assertTrue(text.contains("[1, 3]"), text);
        assertEquals("dup\nmiddle\ndup\n", Files.readString(file));
    }

    @Test
    void testExecute_whenNewStrOmitted_thenOldStrDeleted(@TempDir Path tempDir) throws Exception {
        var file = tempDir.resolve("code.txt");
        Files.writeString(file, "keep\nremove-me\n");

        var result = tool.getDefinition().executor().execute(ToolExecution.of(Map.of(
                "command", "str_replace",
                "path", file.toString(),
                "old_str", "remove-me\n"
        )));

        assertEquals("keep\n", Files.readString(file));
        assertTrue(textOf(result).startsWith("文件已编辑成功"));
    }

    @Test
    void testExecute_whenInsertAtMiddle_thenInsertedAfterLine(@TempDir Path tempDir) throws Exception {
        var file = tempDir.resolve("code.txt");
        Files.writeString(file, "one\ntwo\nthree\n");

        var result = tool.getDefinition().executor().execute(ToolExecution.of(Map.of(
                "command", "insert",
                "path", file.toString(),
                "insert_line", 1,
                "new_str", "one-and-half"
        )));

        assertEquals("one\none-and-half\ntwo\nthree\n", Files.readString(file));
        assertTrue(textOf(result).startsWith("文件已编辑成功"));
    }

    @Test
    void testExecute_whenInsertAtZero_thenInsertedAtTop(@TempDir Path tempDir) throws Exception {
        var file = tempDir.resolve("code.txt");
        Files.writeString(file, "first\nsecond\n");

        tool.getDefinition().executor().execute(ToolExecution.of(Map.of(
                "command", "insert",
                "path", file.toString(),
                "insert_line", 0,
                "new_str", "header"
        )));

        assertEquals("header\nfirst\nsecond\n", Files.readString(file));
    }

    @Test
    void testExecute_whenInsertLineOutOfRange_thenRefused(@TempDir Path tempDir) throws Exception {
        var file = tempDir.resolve("code.txt");
        Files.writeString(file, "one\n");

        var result = tool.getDefinition().executor().execute(ToolExecution.of(Map.of(
                "command", "insert",
                "path", file.toString(),
                "insert_line", 5,
                "new_str", "x"
        )));

        assertTrue(textOf(result).startsWith("错误：insert_line 超出范围"), textOf(result));
        assertEquals("one\n", Files.readString(file));
    }

    @Test
    void testExecute_whenFileMissing_thenFriendlyError(@TempDir Path tempDir) throws Exception {
        var result = tool.getDefinition().executor().execute(ToolExecution.of(Map.of(
                "command", "str_replace",
                "path", tempDir.resolve("no-such.txt").toString(),
                "old_str", "x",
                "new_str", "y"
        )));

        assertTrue(textOf(result).startsWith("错误：文件不存在"));
    }

    @Test
    void testExecute_whenUnknownCommand_thenFriendlyError(@TempDir Path tempDir) throws Exception {
        var file = tempDir.resolve("code.txt");
        Files.writeString(file, "content\n");

        var result = tool.getDefinition().executor().execute(ToolExecution.of(Map.of(
                "command", "delete",
                "path", file.toString()
        )));

        assertTrue(textOf(result).startsWith("错误：不支持的 command"));
    }

    @Test
    void testExecute_whenMultiLineOldStr_thenMatchedAcrossLines(@TempDir Path tempDir) throws Exception {
        var file = tempDir.resolve("code.txt");
        Files.writeString(file, "start\nmiddle\nend\n");

        tool.getDefinition().executor().execute(ToolExecution.of(Map.of(
                "command", "str_replace",
                "path", file.toString(),
                "old_str", "start\nmiddle",
                "new_str", "BEGIN"
        )));

        assertEquals("BEGIN\nend\n", Files.readString(file));
    }
}
