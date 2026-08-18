package dev.duo.tool;

import dev.duo.model.llm.ToolDefinition;
import dev.duo.model.llm.ToolExecutionResult;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * file_read 工具：读取指定文件内容。
 *
 * @author zhangyl
 * @date 2026-08-18
 */
public class FileReadTool {

    private static final int MAX_BYTES = 200 * 1024;
    private static final String ARG_PATH = "path";

    public ToolDefinition getDefinition() {
        return new ToolDefinition(
                "file_read",
                "读取指定文本文件的内容。",
                Map.of(
                        "type", "object",
                        "properties", Map.of(
                                ARG_PATH, Map.of(
                                        "type", "string",
                                        "description", "文件路径（绝对路径或相对于当前工作目录）"
                                )
                        ),
                        "required", List.of(ARG_PATH)
                ),
                this::execute
        );
    }

    private ToolExecutionResult execute(Map<String, Object> args) {
        Objects.requireNonNull(args, "args must not be null");
        
        var pathStr = (String) args.get(ARG_PATH);
        if (pathStr == null || pathStr.isBlank()) {
            return new ToolExecutionResult("错误：缺少 path 参数");
        }

        try {
            var path = resolvePath(pathStr);
            if (!Files.exists(path)) {
                return new ToolExecutionResult("错误：文件不存在: " + path);
            }
            if (!Files.isRegularFile(path)) {
                return new ToolExecutionResult("错误：不是普通文件: " + path);
            }

            var bytes = Files.readAllBytes(path);
            if (bytes.length > MAX_BYTES) {
                return new ToolExecutionResult("错误：文件超过 " + (MAX_BYTES / 1024) + " KB 限制");
            }
            return new ToolExecutionResult(new String(bytes, StandardCharsets.UTF_8));
        } catch (IOException e) {
            return new ToolExecutionResult(e);
        }
    }

    protected Path resolvePath(String path) {
        return Paths.get(path).toAbsolutePath().normalize();
    }
}
