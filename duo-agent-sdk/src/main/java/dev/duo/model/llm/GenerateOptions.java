package dev.duo.model.llm;

import java.util.List;

/**
 * 一次完整的模型请求，已完全组装。
 * <p>
 * </p>
 *
 * @author zhangyl
 * @date 2026-08-18
 */
public record GenerateOptions(
        /** 选择适配器的已注册提供方路由。 */
        String provider,
        /** 模型名称。 */
        String model,
        /** 按顺序排列的对话消息。 */
        List<Message> messages,
        /** 系统提示词文本（适配器映射到提供方的 system 槽位）。 */
        String system,
        /** 工具 schema（适配器映射到提供方的 {@code tools} 字段）。 */
        List<ToolSchema> tools,
        Double temperature,
        Integer maxTokens,
        List<String> stop,
        /** 辅助模型调用的提供方中立分类。 */
        String purpose,
        /** 是否启用深度推理（如 DeepSeek-R1）。 */
        Boolean reasoningEnabled
) {
    public GenerateOptions {
        if (provider == null || provider.isBlank()) {
            throw new IllegalArgumentException("provider 不能为空");
        }
        if (model == null || model.isBlank()) {
            throw new IllegalArgumentException("model 不能为空");
        }
        if (messages == null) {
            messages = List.of();
        }
    }

    /** 创建仅含 provider、model 和 messages 的最小请求。 */
    public GenerateOptions(String provider, String model, List<Message> messages) {
        this(provider, model, messages, null, null, null, null, null, null, null);
    }

    /** 创建含 system 提示与工具 schema 的请求（无温度/停止等可选参数）。 */
    public GenerateOptions(String provider, String model, List<Message> messages,
                           String system, List<ToolSchema> tools) {
        this(provider, model, messages, system, tools, null, null, null, null, null);
    }
}