package dev.duo.model.llm;

import java.util.Map;

/**
 * 发送给模型的工具 JSON Schema 描述。
 * <p>
 * </p>
 *
 * @author zhangyl
 * @date 2026-08-18
 */
public record ToolSchema(
        String name,
        String description,
        /** 参数的 JSON Schema 对象。 */
        Map<String, Object> parameters
) {
    public ToolSchema {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("ToolSchema name 不能为空");
        }
        if (parameters == null) {
            throw new IllegalArgumentException("ToolSchema parameters 不能为 null");
        }
    }
}