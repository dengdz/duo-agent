package dev.dsh.model.llm;

import java.util.List;
import java.util.Map;

/**
 * 组装后的模型输入。sections 和 contexts 在渲染前保持未插值状态。
 * <p>
 * 对应 TS 源码中的 {@code PromptAssembly}。
 * </p>
 */
public record PromptAssembly(
        List<AssembledSection> sections,
        List<AssembledContext> contexts,
        List<ToolSchema> tools,
        Map<String, String> variables
) {
    public PromptAssembly {
        if (sections == null) sections = List.of();
        if (contexts == null) contexts = List.of();
        if (tools == null) tools = List.of();
        if (variables == null) variables = Map.of();
    }
}