package dev.duo.tool;

import dev.duo.api.agent.TurnCancelledException;
import dev.duo.model.llm.ToolDefinition;
import dev.duo.model.llm.ToolExecution;
import dev.duo.model.llm.ToolExecutionResult;
import dev.duo.model.llm.ToolExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
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
 * <p>
 * <b>取消与超时共用进程树两级终止</b>：SIGTERM 全部后代 → 宽限
 * {@link #KILL_GRACE} → SIGKILL 残余。取消（驱动线程 interrupt 唤醒
 * {@code waitFor}）后查取消信号定性：已取消抛 {@link TurnCancelledException}
 * 交由驱动循环转 sentinel，意外中断保持错误结果语义。
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
    /** 两级终止的宽限期：给 SIGTERM 后的进程自行清理时间。 */
    private static final Duration KILL_GRACE = Duration.ofSeconds(3);

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

    private ToolExecutionResult execute(ToolExecution execution) throws TurnCancelledException {
        var args = execution.arguments();
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

        Process process;
        try {
            process = new ProcessBuilder(SHELL, SHELL_FLAG, command)
                    .directory(directory)
                    .redirectErrorStream(true)
                    .start();
        } catch (IOException e) {
            logger.error("IO error executing bash command: {}", command, e);
            return new ToolExecutionResult("错误：IO 异常 - " + e.getMessage());
        }

        try {
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
                terminateProcessTree(process);
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
            // 双通道定性：interrupt 唤醒后查取消信号——已取消是终态语义交驱动转
            // sentinel；意外中断保持可恢复的错误结果
            Thread.currentThread().interrupt();
            terminateProcessTree(process);
            if (execution.cancellation().isCancelled()) {
                logger.warn("Bash command cancelled by user request: {}", command);
                throw new TurnCancelledException(execution.cancellation().cause());
            }
            logger.warn("Bash command interrupted: {}", command, e);
            return new ToolExecutionResult("错误：命令执行被中断");
        } catch (UncheckedIOException e) {
            logger.error("Unchecked IO error reading bash output: {}", command, e);
            return new ToolExecutionResult("错误：读取输出失败");
        } catch (Exception e) {
            logger.error("Unexpected error executing bash command: {}", command, e);
            return new ToolExecutionResult("错误：命令执行失败 - " + e.getClass().getSimpleName());
        }
    }

    /**
     * 两级终止进程树：对 shell 自身及其全部后代先 {@code destroy()}（SIGTERM，
     * 允许自行清理），等待 {@link #KILL_GRACE} 后对仍存活者 {@code destroyForcibly()}
     * （SIGKILL 兜底）。
     * <p>
     * 不用进程组（setsid/负 PID kill）：macOS 无 setsid 二进制、负 PID 会误杀
     * JVM 同组进程。已知限制：遍历后代快照后孙进程再 fork 的新进程会漏杀
     * （记入 limitations）。输出收集线程无需通知：进程死后管道关闭自然返回。
     * </p>
     */
    private void terminateProcessTree(Process process) {
        var targets = new ArrayList<ProcessHandle>();
        process.toHandle().descendants().forEach(targets::add);
        targets.add(process.toHandle());
        targets.forEach(ProcessHandle::destroy);

        var deadline = System.nanoTime() + KILL_GRACE.toNanos();
        for (var handle : targets) {
            var remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                break;
            }
            try {
                handle.onExit().get(remaining, TimeUnit.NANOSECONDS);
            } catch (Exception e) {
                // 等待单个进程退出失败不阻断整树终止流程
                logger.debug("等待进程 {} 退出时异常: {}", handle.pid(), e.toString());
            }
        }
        targets.stream().filter(ProcessHandle::isAlive).forEach(ProcessHandle::destroyForcibly);
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
