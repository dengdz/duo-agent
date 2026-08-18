package dev.dsh.core.llm.tools;

import dev.dsh.model.llm.ToolDefinition;
import dev.dsh.model.llm.ToolExecutionResult;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Map;

/**
 * file_write 工具：将内容写入指定文件。
 */
public class FileWriteTool {

    private static final String ARG_PATH = "path";
    private static final String ARG_CONTENT = "content";

    public ToolDefinition getDefinition() {
        return new ToolDefinition(
                "file_write",
                "将文本内容写入指定文件，若文件已存在则覆盖。",
                Map.of(
                        "type", "object",
                        "properties", Map.of(
                                ARG_PATH, Map.of(
                                        "type", "string",
                                        "description", "文件路径（绝对路径或相对于当前工作目录）"
                                ),
                                ARG_CONTENT, Map.of(
                                        "type", "string",
                                        "description", "要写入的文本内容"
                                )
                        ),
                        "required", List.of(ARG_PATH, ARG_CONTENT)
                ),
                this::execute
        );
    }

    private ToolExecutionResult execute(Map<String, Object> args) {
        var pathStr = (String) args.get(ARG_PATH);
        var content = (String) args.get(ARG_CONTENT);
        if (pathStr == null || pathStr.isBlank()) {
            return new ToolExecutionResult("错误：缺少 path 参数");
        }
        if (content == null) {
            return new ToolExecutionResult("错误：缺少 content 参数");
        }

        try {
            var path = resolvePath(pathStr);
            var parent = path.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(path, content, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            return new ToolExecutionResult("已写入文件: " + path);
        } catch (IOException e) {
            return new ToolExecutionResult(e);
        }
    }

    protected Path resolvePath(String path) {
        return Paths.get(path).toAbsolutePath().normalize();
    }
}
