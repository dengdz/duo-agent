package dev.duo.adapter.anthropic;

import dev.duo.model.llm.ContentBlock;
import dev.duo.util.JsonCodec;
import dev.duo.model.llm.GenerateOptions;
import dev.duo.model.llm.Message;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Anthropic Messages 请求构建器。
 * <p>
 * 将 {@link GenerateOptions} 序列化为 Anthropic Messages 协议请求 JSON。
 * 与 Chat Completions 的关键结构差异：system 为顶层参数（非 messages 内
 * system role）；工具定义平铺且 schema 字段名为 {@code input_schema}；
 * {@code max_tokens} 为必填参数（未配置时按协议兜底默认）。
 * </p>
 *
 * @author zhangyl
 * @date 2026-08-21
 */
final class AnthropicRequestBuilder {

    /** Anthropic 协议要求 max_tokens 必填；GenerateOptions 未配置时的兜底值。 */
    static final int DEFAULT_MAX_TOKENS = 8192;

    /** 扩展思考的最小预算校验由服务端执行；此处仅负责参数序列化。 */

    private AnthropicRequestBuilder() {
        // 工具类，禁止实例化
    }

    /**
     * 构建 Anthropic Messages 请求体。
     *
     * @param options      生成选项
     * @param maxTokens    兜底的 max_tokens（options.maxTokens() 为 null 时使用）
     * @param thinkingBudget 扩展思考预算 token 数（null 表示不启用思考）
     * @return JSON 字符串
     */
    static String buildRequest(GenerateOptions options, Integer maxTokens, Long thinkingBudget) {
        var resolvedMaxTokens = options.maxTokens() != null ? options.maxTokens() : maxTokens;

        var sb = new StringBuilder(1024);
        sb.append("{\n");
        sb.append("  \"model\": \"").append(JsonCodec.escapeJson(options.model())).append("\",\n");
        sb.append("  \"max_tokens\": ").append(resolvedMaxTokens).append(",\n");
        sb.append("  \"stream\": true,\n");

        // system 为顶层参数（协议差异：Chat Completions 放在 messages 首条）
        if (options.system() != null && !options.system().isBlank()) {
            sb.append("  \"system\": \"").append(JsonCodec.escapeJson(options.system())).append("\",\n");
        }

        sb.append("  \"messages\": [\n");
        var messages = new ArrayList<String>();
        for (var msg : options.messages()) {
            switch (msg) {
                case Message.UserMessage userMsg -> messages.add(
                        "      {\"role\": \"user\", \"content\": \""
                                + JsonCodec.escapeJson(flattenText(userMsg.content())) + "\"}");
                case Message.AssistantMessage asstMsg -> messages.add(buildAssistantMessage(asstMsg));
                case Message.ToolResultMessage toolMsg -> {
                    // 工具结果以 user 角色 + tool_result 块回注（协议差异：非独立 tool role）
                    if (!toolMsg.content().isEmpty()
                            && toolMsg.content().getFirst() instanceof ContentBlock.ToolResult tr) {
                        var text = flattenText(tr.content());
                        messages.add("      {\"role\": \"user\", \"content\": [{\"type\": \"tool_result\", "
                                + "\"tool_use_id\": \"" + tr.toolCallId() + "\", \"content\": \""
                                + JsonCodec.escapeJson(text.isEmpty() ? "(no output)" : text) + "\"}]}");
                    }
                }
                default -> {
                }
            }
        }
        sb.append(String.join(",\n", messages));
        sb.append("\n    ],\n");

        // 工具定义平铺格式：{name, description, input_schema}（非 Chat Completions 的嵌套 function）
        if (options.tools() != null && !options.tools().isEmpty()) {
            sb.append("  \"tools\": [\n");
            var toolEntries = new ArrayList<String>();
            for (var tool : options.tools()) {
                toolEntries.add("    {\"name\": \"" + JsonCodec.escapeJson(tool.name()) + "\", "
                        + "\"description\": \"" + JsonCodec.escapeJson(tool.description()) + "\", "
                        + "\"input_schema\": " + JsonCodec.toJson(tool.parameters())
                        + "}");
            }
            sb.append(String.join(",\n", toolEntries));
            sb.append("\n  ],\n");
        }

        if (thinkingBudget != null) {
            // 扩展思考：启用时不发送 temperature（协议要求思考模式下 temperature 固定为 1）
            sb.append("  \"thinking\": {\"type\": \"enabled\", \"budget_tokens\": ")
                    .append(thinkingBudget).append("},\n");
        } else if (options.temperature() != null) {
            sb.append("  \"temperature\": ").append(options.temperature()).append(",\n");
        }

        // GenerateOptions.stop 在 Messages 协议无对应参数（stop_sequence 语义不同），忽略

        var result = sb.toString();
        if (result.endsWith(",\n")) {
            result = result.substring(0, result.length() - 2) + "\n";
        }
        result += "}";
        return result;
    }

    /** assistant 消息：纯文本序列化为字符串，含工具调用时用 text + tool_use 块数组。 */
    private static String buildAssistantMessage(Message.AssistantMessage msg) {
        var text = flattenText(msg.content());
        var toolCalls = new ArrayList<ContentBlock.ToolCall>();
        for (var block : msg.content()) {
            if (block instanceof ContentBlock.ToolCall tc) {
                toolCalls.add(tc);
            }
        }
        if (toolCalls.isEmpty()) {
            return "      {\"role\": \"assistant\", \"content\": \"" + JsonCodec.escapeJson(text) + "\"}";
        }
        var sb = new StringBuilder();
        sb.append("      {\"role\": \"assistant\", \"content\": [");
        var first = true;
        if (!text.isEmpty()) {
            sb.append("{\"type\": \"text\", \"text\": \"").append(JsonCodec.escapeJson(text)).append("\"}");
            first = false;
        }
        for (var tc : toolCalls) {
            if (!first) {
                sb.append(", ");
            }
            first = false;
            // input 直接嵌入原始 JSON 字符串（arguments 即 JSON 对象文本），零解析透传
            sb.append("{\"type\": \"tool_use\", \"id\": \"").append(JsonCodec.escapeJson(tc.id().value()))
                    .append("\", \"name\": \"").append(JsonCodec.escapeJson(tc.name()))
                    .append("\", \"input\": ").append(tc.arguments().isBlank() ? "{}" : tc.arguments())
                    .append("}");
        }
        sb.append("]}");
        return sb.toString();
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
