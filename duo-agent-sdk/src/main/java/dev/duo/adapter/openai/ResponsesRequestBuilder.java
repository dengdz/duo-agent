package dev.duo.adapter.openai;

import dev.duo.model.llm.ContentBlock;
import dev.duo.util.JsonCodec;
import dev.duo.model.llm.GenerateOptions;
import dev.duo.model.llm.Message;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * OpenAI Responses 请求构建器。
 * <p>
 * 将 {@link GenerateOptions} 序列化为 Responses 协议请求 JSON。与
 * Chat Completions 的关键结构差异：系统提示经顶层 {@code instructions}；
 * 输入为 {@code input} items（function_call / function_call_output 为独立
 * item 而非 message 内字段）；工具定义平铺且 schema 字段名为
 * {@code parameters}；推理经 {@code reasoning: {effort}} 控制。
 * 本实现仅使用无状态形态：不使用 previous_response_id 与 OpenAI 托管
 * 内置工具（状态管理与工具执行是 SDK 自身的职责）。
 * </p>
 *
 * @author zhangyl
 * @date 2026-08-21
 */
final class ResponsesRequestBuilder {

    private ResponsesRequestBuilder() {
        // 工具类，禁止实例化
    }

    /**
     * 构建 Responses 请求体。
     *
     * @param options       生成选项
     * @param reasoningEffort 推理努力级别（null 表示不启用推理参数）
     * @return JSON 字符串
     */
    static String buildRequest(GenerateOptions options, String reasoningEffort) {
        var sb = new StringBuilder(1024);
        sb.append("{\n");
        sb.append("  \"model\": \"").append(JsonCodec.escapeJson(options.model())).append("\",\n");
        sb.append("  \"stream\": true,\n");

        // 系统提示经顶层 instructions（协议差异：Chat Completions 放在 messages 内）
        if (options.system() != null && !options.system().isBlank()) {
            sb.append("  \"instructions\": \"").append(JsonCodec.escapeJson(options.system())).append("\",\n");
        }

        sb.append("  \"input\": [\n");
        var items = new ArrayList<String>();
        for (var msg : options.messages()) {
            switch (msg) {
                case Message.UserMessage userMsg -> items.add(
                        "      {\"role\": \"user\", \"content\": \""
                                + JsonCodec.escapeJson(flattenText(userMsg.content())) + "\"}");
                case Message.AssistantMessage asstMsg -> buildAssistantItems(asstMsg, items);
                case Message.ToolResultMessage toolMsg -> {
                    // 工具结果为独立 function_call_output item（非 message 内 tool role）
                    if (!toolMsg.content().isEmpty()
                            && toolMsg.content().getFirst() instanceof ContentBlock.ToolResult tr) {
                        var text = flattenText(tr.content());
                        items.add("      {\"type\": \"function_call_output\", \"call_id\": \""
                                + tr.toolCallId() + "\", \"output\": \""
                                + JsonCodec.escapeJson(text.isEmpty() ? "(no output)" : text) + "\"}");
                    }
                }
                default -> {
                }
            }
        }
        sb.append(String.join(",\n", items));
        sb.append("\n    ],\n");

        // 工具定义平铺格式：{type:"function", name, description, parameters}
        if (options.tools() != null && !options.tools().isEmpty()) {
            sb.append("  \"tools\": [\n");
            var toolEntries = new ArrayList<String>();
            for (var tool : options.tools()) {
                toolEntries.add("    {\"type\": \"function\", \"name\": \"" + JsonCodec.escapeJson(tool.name())
                        + "\", \"description\": \"" + JsonCodec.escapeJson(tool.description())
                        + "\", \"parameters\": " + JsonCodec.toJson(tool.parameters())
                        + "}");
            }
            sb.append(String.join(",\n", toolEntries));
            sb.append("\n  ],\n");
        }

        if (options.temperature() != null) {
            sb.append("  \"temperature\": ").append(options.temperature()).append(",\n");
        }
        if (options.maxTokens() != null) {
            sb.append("  \"max_output_tokens\": ").append(options.maxTokens()).append(",\n");
        }
        if (reasoningEffort != null) {
            // summary 必须显式请求：Responses 协议默认不透出思考内容，
            // 仅在 reasoning.summary 开启（auto）后才以 reasoning_summary_text.delta 事件下发
            sb.append("  \"reasoning\": {\"effort\": \"").append(JsonCodec.escapeJson(reasoningEffort))
                    .append("\", \"summary\": \"auto\"},\n");
        }
        // GenerateOptions.stop 在 Responses 协议无对应参数，忽略

        var result = sb.toString();
        if (result.endsWith(",\n")) {
            result = result.substring(0, result.length() - 2) + "\n";
        }
        result += "}";
        return result;
    }

    /** assistant 消息：纯文本序列化为 message item；含工具调用时为每个调用发独立 function_call item。 */
    private static void buildAssistantItems(Message.AssistantMessage msg, List<String> items) {
        var text = flattenText(msg.content());
        if (!text.isEmpty()) {
            items.add("      {\"role\": \"assistant\", \"content\": \"" + JsonCodec.escapeJson(text) + "\"}");
        }
        for (var block : msg.content()) {
            if (block instanceof ContentBlock.ToolCall tc) {
                items.add("      {\"type\": \"function_call\", \"call_id\": \""
                        + JsonCodec.escapeJson(tc.id().value()) + "\", \"name\": \"" + JsonCodec.escapeJson(tc.name())
                        + "\", \"arguments\": \"" + JsonCodec.escapeJson(tc.arguments()) + "\"}");
            }
        }
    }

    private static String flattenText(List<ContentBlock> blocks) {
        var sb = new StringBuilder();
        for (var block : blocks) {
            if (block instanceof ContentBlock.Text t) {
                sb.append(t.text());
            }
        }
        return sb.toString();
    }

}
