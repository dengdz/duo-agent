package dev.duo.tool;

import dev.duo.model.llm.ToolDefinition;
import dev.duo.model.llm.ToolExecutionResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * bash 工具：在指定工作目录下执行 shell 命令。
 * <p>
 * 对应原版 harness 中的 bash/terminal 工具能力。
 * 默认工作目录为当前进程启动目录，可通 {@code cwd} 参数覆盖。
 * </p>
 *
 * @author zhangyl
 * @date 2026-08-18
 */
public class BashTool {

    private static final Logger logger = LoggerFactory.getLogger(BashTool.class);
    
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);
    private static final int MAX_OUTPUT_BYTES = 100 * 1024;
    private static final int MAX_TIMEOUT_SECONDS = 300;
    private static final int MIN_TIMEOUT_SECONDS = 1;
    
    // 根据操作系统选择 Shell
    private static final boolean IS_WINDOWS = System.getProperty("os.name")
            .toLowerCase()
            .contains("win");
    private static final String SHELL = IS_WINDOWS ? "cmd.exe" : "/bin/sh";
    private static final String SHELL_FLAG = IS_WINDOWS ? "/c" : "-c";

    private static final String ARG_COMMAND = "command";
    private static final String ARG_CWD = "cwd";
    private static final String ARG_TIMEOUT = "timeout";

    /**
     * 专用线程池：避免使用 ForkJoinPool.commonPool()，防止任务堆积影响其他组件。
     * <p>
     * 核心线程数 2：处理常规并发场景
     * 最大线程数 10：限制资源消耗
     * 队列容量 100：防止无限任务堆积
     * 拒绝策略 CallerRunsPolicy：由调用线程执行，提供背压
     * </p>
     */
    private static final ExecutorService BASH_EXECUTOR = new ThreadPoolExecutor(
            2,
            10,
            60L,
            TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(100),
            runnable -> {
                var thread = new Thread(runnable);
                thread.setName("bash-tool-" + thread.threadId());
                thread.setDaemon(true);
                return thread;
            },
            new ThreadPoolExecutor.CallerRunsPolicy()
    );

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

            // 先异步读取输出再等待退出：若先 waitFor，子进程输出超过管道缓冲时会
            // 阻塞在写端，父进程等不到退出而误判超时
            var outputFuture = CompletableFuture.supplyAsync(() -> {
                try (var input = process.getInputStream()) {
                    return input.readNBytes(MAX_OUTPUT_BYTES);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }, BASH_EXECUTOR);

            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                return new ToolExecutionResult("错误：命令执行超时（" + timeoutSeconds + " 秒）");
            }

            // 进程已退出，管道已关闭，读取必然结束
            var bytes = outputFuture.join();
            var output = new String(bytes, StandardCharsets.UTF_8);
            if (bytes.length >= MAX_OUTPUT_BYTES) {
                output += "\n...（输出已截断）";
            }

            var exitCode = process.exitValue();
            var prefix = exitCode == 0 ? "" : "退出码 " + exitCode + "\n";
            return new ToolExecutionResult(prefix + output);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.warn("Bash command interrupted: {}", command, e);
            return new ToolExecutionResult("错误：命令执行被中断");
        } catch (IOException e) {
            logger.error("IO error executing bash command: {}", command, e);
            return new ToolExecutionResult("错误：IO 异常 - " + e.getMessage());
        } catch (UncheckedIOException e) {
            logger.error("Unchecked IO error reading bash output: {}", command, e);
            return new ToolExecutionResult("错误：读取输出失败");
        } catch (Exception e) {
            logger.error("Unexpected error executing bash command: {}", command, e);
            return new ToolExecutionResult("错误：命令执行失败 - " + e.getClass().getSimpleName());
        }
    }

    private int parseTimeout(Object value) {
        if (value == null) {
            return (int) DEFAULT_TIMEOUT.getSeconds();
        }
        try {
            var seconds = ((Number) value).intValue();
            return Math.clamp(seconds, MIN_TIMEOUT_SECONDS, MAX_TIMEOUT_SECONDS);
        } catch (ClassCastException e) {
            logger.debug("Invalid timeout value type: {}", value.getClass().getSimpleName());
            return (int) DEFAULT_TIMEOUT.getSeconds();
        }
    }
}
