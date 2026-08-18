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
    private static final int MAX_TIMEOUT_SECONDS = 300;
    private static final int MIN_TIMEOUT_SECONDS = 1;
    private static final String SHELL = "/bin/sh";
    private static final String SHELL_FLAG = "-c";

    private static final String ARG_COMMAND = "command";
    private static final String ARG_CWD = "cwd";
    private static final String ARG_TIMEOUT = "timeout";

    public ToolDefinition getDefinition() {
        return new ToolDefinition(
                "bash",
                "执行 shell 命令并返回标准输出与标准错误。",
                Map.of(
                        "type", "object",
                        "properties", Map.of(
                                ARG_COMMAND, Map.of(
                                        "type", "string",
                                        "description", "要执行的 shell 命令"
                                ),
                                ARG_CWD, Map.of(
                                        "type", "string",
                                        "description", "工作目录（可选，默认为当前目录）"
                                ),
                                ARG_TIMEOUT, Map.of(
                                        "type", "integer",
                                        "description", "超时时间（秒，默认 30，最大 300）"
                                )
                        ),
                        "required", List.of(ARG_COMMAND)
                ),
                this::execute
        );
    }

    private ToolExecutionResult execute(Map<String, Object> args) {
        var command = (String) args.get(ARG_COMMAND);
        if (command == null || command.isBlank()) {
            return new ToolExecutionResult("错误：缺少 command 参数");
        }

        var cwd = (String) args.get(ARG_CWD);
        var directory = cwd != null && !cwd.isBlank() ? new File(cwd) : new File(".").getAbsoluteFile();
        if (!directory.exists() || !directory.isDirectory()) {
            return new ToolExecutionResult("错误：工作目录不存在: " + directory);
        }

        var timeoutSeconds = parseTimeout(args.get(ARG_TIMEOUT));
        var timeout = Duration.ofSeconds(timeoutSeconds);

        try {
            var process = new ProcessBuilder(SHELL, SHELL_FLAG, command)
                    .directory(directory)
                    .redirectErrorStream(true)
                    .start();

            var finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroyForcibly();
                return new ToolExecutionResult("错误：命令执行超时（" + timeoutSeconds + " 秒）");
            }

            String output;
            try (var input = process.getInputStream()) {
                var bytes = input.readNBytes(MAX_OUTPUT_BYTES);
                output = new String(bytes, StandardCharsets.UTF_8);
                if (bytes.length >= MAX_OUTPUT_BYTES) {
                    output += "\n...（输出已截断）";
                }
            }

            var exitCode = process.exitValue();
            var prefix = exitCode == 0 ? "" : "退出码 " + exitCode + "\n";
            return new ToolExecutionResult(prefix + output);
        } catch (Exception e) {
            return new ToolExecutionResult(e);
        }
    }

    private int parseTimeout(Object value) {
        if (value == null) {
            return (int) DEFAULT_TIMEOUT.getSeconds();
        }
        try {
            var seconds = ((Number) value).intValue();
            return Math.clamp(seconds, MIN_TIMEOUT_SECONDS, MAX_TIMEOUT_SECONDS);
        } catch (Exception e) {
            return (int) DEFAULT_TIMEOUT.getSeconds();
        }
    }
}
