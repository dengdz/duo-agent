package dev.dsh.core.llm.tools;

import dev.dsh.model.llm.ToolDefinition;
import dev.dsh.model.llm.ToolExecutionResult;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * bash 工具：在指定工作目录下执行 shell 命令。
 * <p>
 * 对应原版 harness 中的 bash/terminal 工具能力。
 * 默认工作目录为当前进程启动目录，可通 {@code cwd} 参数覆盖。
 * </p>
 */
public class BashTool {

    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);
    private static final int MAX_OUTPUT_BYTES = 100 * 1024;

    public ToolDefinition getDefinition() {
        return new ToolDefinition(
                "bash",
                "执行 shell 命令并返回标准输出与标准错误。",
                Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "command", Map.of(
                                        "type", "string",
                                        "description", "要执行的 shell 命令"
                                ),
                                "cwd", Map.of(
                                        "type", "string",
                                        "description", "工作目录（可选，默认为当前目录）"
                                ),
                                "timeout", Map.of(
                                        "type", "integer",
                                        "description", "超时时间（秒，默认 30，最大 300）"
                                )
                        ),
                        "required", List.of("command")
                ),
                this::execute
        );
    }

    private ToolExecutionResult execute(Map<String, Object> args) {
        var command = (String) args.get("command");
        if (command == null || command.isBlank()) {
            return new ToolExecutionResult("错误：缺少 command 参数");
        }

        var cwd = (String) args.get("cwd");
        var directory = cwd != null && !cwd.isBlank() ? new File(cwd) : new File(".").getAbsoluteFile();
        if (!directory.exists() || !directory.isDirectory()) {
            return new ToolExecutionResult("错误：工作目录不存在: " + directory);
        }

        var timeoutSeconds = parseTimeout(args.get("timeout"));
        var timeout = Duration.ofSeconds(timeoutSeconds);

        try {
            var process = new ProcessBuilder("/bin/sh", "-c", command)
                    .directory(directory)
                    .redirectErrorStream(true)
                    .start();

            var finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroyForcibly();
                return new ToolExecutionResult("错误：命令执行超时（" + timeoutSeconds + " 秒）");
            }

            var output = new String(process.getInputStream().readNBytes(MAX_OUTPUT_BYTES), StandardCharsets.UTF_8);
            if (output.length() >= MAX_OUTPUT_BYTES) {
                output += "\n...（输出已截断）";
            }

            var exitCode = process.exitValue();
            var prefix = exitCode == 0 ? "" : "退出码 " + exitCode + "\n";
            return new ToolExecutionResult(prefix + output);
        } catch (Exception e) {
            return new ToolExecutionResult(e);
        }
    }

    private int parseTimeout(Object value) {
        if (value == null) return (int) DEFAULT_TIMEOUT.getSeconds();
        try {
            var seconds = ((Number) value).intValue();
            return Math.clamp(seconds, 1, 300);
        } catch (Exception e) {
            return (int) DEFAULT_TIMEOUT.getSeconds();
        }
    }
}
