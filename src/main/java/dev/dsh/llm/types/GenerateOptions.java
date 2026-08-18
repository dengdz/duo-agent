package dev.dsh.llm.types;

import dev.dsh.llm.message.Message;

import java.util.List;

/**
 * 一次完整的模型请求，已完全组装。
 * <p>
 * 对应 TS 源码中的 {@code GenerateOptions}。
 * </p>
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
        String purpose
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
        this(provider, model, messages, null, null, null, null, null, null);
    }
}