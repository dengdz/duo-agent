package dev.duo.adapter.deepseek;

import dev.duo.model.llm.ContentBlock;
import dev.duo.model.llm.GenerateOptions;
import dev.duo.model.llm.Message;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * DeepSeek 请求构建器。
 * <p>
 * 负责将 {@link GenerateOptions} 序列化为 DeepSeek API 请求 JSON 字符串。
 * </p>
 *
 * @author zhangyl
 * @date 2026-08-18
 */
class DeepSeekRequestBuilder {

    private DeepSeekRequestBuilder() {
        // 工具类，禁止实例化
    }

    /**
     * 构建 DeepSeek API 请求体。
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
        sb.append("  \"model\": \"").append(escapeJson(options.model())).append("\",\n");
        sb.append("  \"stream\": true,\n");
        sb.append("  \"stream_options\": {\"include_usage\": true},\n");
        sb.append("  \"messages\": [\n");

        var messages = new ArrayList<String>();

        // system
        if (options.system() != null && !options.system().isBlank()) {
            messages.add("      {\"role\": \"system\", \"content\": \""
                    + escapeJson(options.system()) + "\"}");
        }

        // conversation messages
        for (var msg : options.messages()) {
            switch (msg) {
                case Message.UserMessage userMsg -> {
                    var text = flattenText(userMsg.content());
                    messages.add("      {\"role\": \"user\", \"content\": \""
                            + escapeJson(text) + "\"}");
                }
                case Message.AssistantMessage asstMsg -> {
                    var text = flattenText(asstMsg.content());
                    messages.add("      {\"role\": \"assistant\", \"content\": \""
                            + escapeJson(text) + "\"}");
                }
                case Message.ToolResultMessage toolMsg -> {
                    if (!toolMsg.content().isEmpty()
                            && toolMsg.content().getFirst() instanceof ContentBlock.ToolResult tr) {
                        var text = flattenText(tr.content());
                        messages.add("      {\"role\": \"tool\", \"tool_call_id\": \""
                                + tr.toolCallId() + "\", \"content\": \""
                                + escapeJson(text.isEmpty() ? "(no output)" : text) + "\"}");
                    }
                }
                default -> {}
            }
        }

        sb.append(String.join(",\n", messages));
        sb.append("\n    ],\n");

        // tools
        if (options.tools() != null && !options.tools().isEmpty()) {
            sb.append("  \"tools\": [\n");
            var toolEntries = new ArrayList<String>();
            for (var tool : options.tools()) {
                toolEntries.add("    {\"type\": \"function\", \"function\": {"
                        + "\"name\": \"" + escapeJson(tool.name()) + "\", "
                        + "\"description\": \"" + escapeJson(tool.description()) + "\", "
                        + "\"parameters\": " + toJson(tool.parameters())
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
            if (block instanceof ContentBlock.Text t) sb.append(t.text());
        }
        return sb.toString();
    }

    private static String escapeJson(String s) {
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    /** 将 Map 序列化为 JSON 字符串。 */
    @SuppressWarnings("unchecked")
    private static String toJson(Object value) {
        if (value == null) return "null";
        if (value instanceof String s) return "\"" + escapeJson(s) + "\"";
        if (value instanceof Number || value instanceof Boolean) return value.toString();
        if (value instanceof Map<?, ?> map) {
            var sb = new StringBuilder("{");
            var first = true;
            for (var entry : map.entrySet()) {
                if (!first) sb.append(", ");
                first = false;
                sb.append("\"").append(escapeJson(entry.getKey().toString())).append("\": ");
                sb.append(toJson(entry.getValue()));
            }
            sb.append("}");
            return sb.toString();
        }
        if (value instanceof Iterable<?> iter) {
            var sb = new StringBuilder("[");
            var first = true;
            for (var item : iter) {
                if (!first) sb.append(", ");
                first = false;
                sb.append(toJson(item));
            }
            sb.append("]");
            return sb.toString();
        }
        return "\"" + escapeJson(value.toString()) + "\"";
    }
}
