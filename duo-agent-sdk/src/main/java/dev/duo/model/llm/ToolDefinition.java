package dev.duo.model.llm;

import java.util.Map;

/**
 * 工具定义。
 * <p>
 * 对应 TS 源码中的 {@code ToolDefinition}。
 * </p>
 *
 * @author zhangyl
 * @date 2026-08-18
 */
public record ToolDefinition(
        String name,
        String description,
        /** JSON Schema 对象，描述参数结构。 */
        Map<String, Object> parameters,
        /** 执行函数（参数 + 取消信号）。 */
        ToolExecutor executor
) {
    public ToolDefinition {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("tool name 不能为空");
        }
        if (executor == null) {
            throw new IllegalArgumentException("tool executor 不能为 null");
        }
    }
}