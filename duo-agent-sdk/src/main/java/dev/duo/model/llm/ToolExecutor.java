package dev.duo.model.llm;

import dev.duo.api.agent.TurnCancelledException;

import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/**
 * 工具执行入口：接收 {@link ToolExecution}（参数 + 取消信号）并返回结果。
 * <p>
 * 取消通道的分工：阻塞原语由驱动线程 interrupt 即时唤醒，工具在
 * {@code catch InterruptedException} 中完成自身清理（杀进程等）后抛出
 * {@link TurnCancelledException}，由驱动循环统一转为 sentinel 结果——
 * 工具不自行编写取消文案。纯计算类工具可用 {@link #of(Function)} 适配
 * 旧式 lambda（声明"不需要打断"）。
 * </p>
 *
 * @author zhangyl
 * @date 2026-08-21
 */
@FunctionalInterface
public interface ToolExecutor {

    /**
     * 执行一次工具调用。
     *
     * @param execution 参数与取消信号（cancellation 必填）
     * @return 执行结果
     * @throws TurnCancelledException 执行被取消：工具已自行清理后抛出，
     *         由驱动循环转为 sentinel 结果
     */
    ToolExecutionResult execute(ToolExecution execution) throws TurnCancelledException;

    /**
     * 适配不消费取消信号的执行函数：保持旧式 {@code (args) -> result} lambda
     * 的简洁写法。
     * <p>
     * 适配后的工具仍受驱动层取消保护：dispatch 前的检查点会拦截
     * （ABORTED_BEFORE_DISPATCH），执行完毕后若已取消则结果被替换
     * （ABORTED）；仅执行中途不可打断。
     * </p>
     */
    static ToolExecutor of(Function<Map<String, Object>, ToolExecutionResult> fn) {
        Objects.requireNonNull(fn, "fn must not be null");
        return execution -> fn.apply(execution.arguments());
    }
}
