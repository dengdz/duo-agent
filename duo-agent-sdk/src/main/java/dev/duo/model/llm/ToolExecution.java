package dev.duo.model.llm;

import dev.duo.api.agent.CancellationSignal;

import java.util.Map;
import java.util.Objects;

/**
 * 一次工具执行的下发上下文：参数 + 取消信号。
 * <p>
 * cancellation 必填且无默认——注册表不合成回退信号（回退信号没有调用方
 * 生命周期可代表）。无取消权威的调用方（测试、直连）用 {@link #of(Map)}
 * 创建附带独立新鲜信号的执行。
 * </p>
 *
 * @author zhangyl
 * @date 2026-08-21
 */
public record ToolExecution(
        /** JSON 参数字典（保留解析顺序，可含 null 值）。 */
        Map<String, Object> arguments,
        /** 本 turn 的取消信号；工具转发给底层能力或在其安全边界检查。 */
        CancellationSignal cancellation
) {

    public ToolExecution {
        Objects.requireNonNull(arguments, "arguments must not be null");
        Objects.requireNonNull(cancellation, "cancellation must not be null");
    }

    /** 独立执行的便利构造：附带一个永不取消的新鲜信号（测试/直连场景）。 */
    public static ToolExecution of(Map<String, Object> arguments) {
        return new ToolExecution(arguments, new CancellationSignal());
    }
}
