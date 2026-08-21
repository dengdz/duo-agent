package dev.duo.adapter.openai;

import dev.duo.model.llm.ContentBlock;
import dev.duo.util.JsonCodec;
import dev.duo.model.llm.GenerateOptions;
import dev.duo.model.llm.Message;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * OpenAI Chat Completions 请求构建器。
 * <p>
 * 将 {@link GenerateOptions} 序列化为 Chat Completions 协议请求 JSON。
 * 该格式是 DeepSeek、Kimi、通义、Ollama、vLLM 等厂商的事实标准，
 * 泛化自 DeepSeek 协议实现，请求结构与厂商无关。
 * </p>
 *
 * @author zhangyl
 * @date 2026-08-21
 */
final class OpenAiRequestBuilder {

    private OpenAiRequestBuilder() {
        // 工具类，禁止实例化
    }

    /**
     * 构建 Chat Completions 请求体。
     *
     * @param options 生成选项
     * @return JSON 字符串
     */
    static String buildRequest(GenerateOptions options) {
        // 预估容量：基础结构 ~200 字符 + system ~1000 + 每条消息 ~500 + 每个工具 ~300
        var estimatedCapacity = 200
                + (options.system() != null ? options.system().length() : 0)
                + options.messages().size() * 500
                + (options.tools() != null ? options.tools().size() * 300 : 0);
        var sb = new StringBuilder(estimatedCapacity);
        sb.append("{\n");
        sb.append("  \"model\": \"").append(JsonCodec.escapeJson(options.model())).append("\",\n");
        sb.append("  \"stream\": true,\n");
        sb.append("  \"messages\": [\n");

        var messages = new ArrayList<String>();

        // system
        if (options.system() != null && !options.system().isBlank()) {
            messages.add("      {\"role\": \"system\", \"content\": \""
                    + JsonCodec.escapeJson(options.system()) + "\"}");
        }

        // conversation messages
        for (var msg : options.messages()) {
            switch (msg) {
                case Message.UserMessage userMsg -> {
                    var text = flattenText(userMsg.content());
                    messages.add("      {\"role\": \"user\", \"content\": \""
                            + JsonCodec.escapeJson(text) + "\"}");
                }
                case Message.AssistantMessage asstMsg -> {
                    var text = flattenText(asstMsg.content());
                    var toolCalls = extractToolCalls(asstMsg.content());

                    if (!toolCalls.isEmpty()) {
                        // 有工具调用时，序列化为 tool_calls 格式
                        var toolCallsJson = new StringBuilder("[");
                        var first = true;
                        for (var tc : toolCalls) {
                            if (!first) {
                                toolCallsJson.append(", ");
                            }
                            first = false;
                            toolCallsJson.append("{\"id\": \"").append(JsonCodec.escapeJson(tc.id().value()))
                                    .append("\", \"type\": \"function\", \"function\": {\"name\": \"")
                                    .append(JsonCodec.escapeJson(tc.name()))
                                    .append("\", \"arguments\": \"")
                                    .append(JsonCodec.escapeJson(tc.arguments()))
                                    .append("\"}}");
                        }
                        toolCallsJson.append("]");
                        messages.add("      {\"role\": \"assistant\", \"content\": \""
                                + JsonCodec.escapeJson(text) + "\", \"tool_calls\": " + toolCallsJson + "}");
                    } else {
                        // 无工具调用，只有文本内容
                        messages.add("      {\"role\": \"assistant\", \"content\": \""
                                + JsonCodec.escapeJson(text) + "\"}");
                    }
                }
                case Message.ToolResultMessage toolMsg -> {
                    if (!toolMsg.content().isEmpty()
                            && toolMsg.content().getFirst() instanceof ContentBlock.ToolResult tr) {
                        var text = flattenText(tr.content());
                        messages.add("      {\"role\": \"tool\", \"tool_call_id\": \""
                                + tr.toolCallId() + "\", \"content\": \""
                                + JsonCodec.escapeJson(text.isEmpty() ? "(no output)" : text) + "\"}");
                    }
                }
                default -> {
                }
            }
        }

        sb.append(String.join(",\n", messages));
        sb.append("\n    ],\n");

        // tools（Chat Completions 嵌套格式：{type:"function", function:{...}}）
        if (options.tools() != null && !options.tools().isEmpty()) {
            sb.append("  \"tools\": [\n");
            var toolEntries = new ArrayList<String>();
            for (var tool : options.tools()) {
                toolEntries.add("    {\"type\": \"function\", \"function\": {"
                        + "\"name\": \"" + JsonCodec.escapeJson(tool.name()) + "\", "
                        + "\"description\": \"" + JsonCodec.escapeJson(tool.description()) + "\", "
                        + "\"parameters\": " + JsonCodec.toJson(tool.parameters())
                        + "}}");
            }
            sb.append(String.join(",\n", toolEntries));
            sb.append("\n  ],\n");
        }

        if (options.temperature() != null) {
            sb.append("  \"temperature\": ").append(options.temperature()).append(",\n");
        }
        if (options.maxTokens() != null) {
            sb.append("  \"max_tokens\": ").append(options.maxTokens()).append(",\n");
        }

        // 推理模式说明：DeepSeek-R1 等模型经 Chat Completions 无需显式推理参数，
        // 思考内容由响应的 reasoning 字段透出（见 OpenAiSseParser 的 reasoningContentField）

        // 去掉末尾的逗号
        var result = sb.toString();
        if (result.endsWith(",\n")) {
            result = result.substring(0, result.length() - 2) + "\n";
        }
        result += "}";
        return result;
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

    private static List<ContentBlock.ToolCall> extractToolCalls(List<ContentBlock> blocks) {
        var toolCalls = new ArrayList<ContentBlock.ToolCall>();
        for (var block : blocks) {
            if (block instanceof ContentBlock.ToolCall tc) {
                toolCalls.add(tc);
            }
        }
        return toolCalls;
    }

}
