package dev.dsh.core.llm.deepseek;

import dev.dsh.api.llm.LlmAdapter;
import dev.dsh.api.llm.StreamCallback;
import dev.dsh.model.llm.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * DeepSeek API 适配器。
 * <p>
 * 将 harness 的 {@link GenerateOptions} 序列化为 DeepSeek 聊天补全 API 请求，
 * 解析 SSE 响应并翻译为 {@link StreamChunk} 协议。
 * </p>
 */
public class DeepSeekAdapter extends LlmAdapter {

    private static final String DEFAULT_BASE_URL = "https://api.deepseek.com";
    private static final String API_KEY_ENV = "DEEPSEEK_API_KEY";
    private static final Duration TIMEOUT = Duration.ofSeconds(60);

    private final HttpClient httpClient;
    private final String baseUrl;
    private final String apiKey;

    public DeepSeekAdapter() {
        this.httpClient = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
        this.baseUrl = getEnvOrDefault("DEEPSEEK_BASE_URL", DEFAULT_BASE_URL);
        this.apiKey = System.getenv(API_KEY_ENV);
    }

    @Override
    public void stream(GenerateOptions options, StreamCallback callback) {
        if (apiKey == null || apiKey.isBlank()) {
            callback.onError(new IllegalStateException(API_KEY_ENV + " 未设置"));
            return;
        }
        try {
            var requestBody = buildRequest(options);
            var request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/chat/completions"))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .header("Accept", "text/event-stream")
                    .timeout(TIMEOUT)
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody, StandardCharsets.UTF_8))
                    .build();

            httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofLines())
                    .thenAccept(response -> {
                        if (response.statusCode() != 200) {
                            var body = response.body().reduce("", (a, b) -> a + b);
                            callback.onError(new RuntimeException(
                                    "DeepSeek API 返回 " + response.statusCode() + ": " + body));
                            return;
                        }
                        try {
                            var textBuilder = new StringBuilder();
                            response.body().forEach(line -> {
                                if (!line.startsWith("data: ")) return;
                                var data = line.substring(6).trim();
                                if ("[DONE]".equals(data) || data.isEmpty()) return;
                                parseChunk(data, textBuilder, callback);
                            });
                            // 发出 finish
                            callback.onChunk(new StreamChunk.Finish(new FinishReason.Stop()));
                            callback.onComplete();
                        } catch (Exception e) {
                            callback.onError(e);
                        }
                    })
                    .exceptionally(error -> {
                        callback.onError(error);
                        return null;
                    });
        } catch (Exception e) {
            callback.onError(e);
        }
    }

    /** 解析单个 SSE data 行，提取文本 delta 并发射。 */
    private boolean firstChunk = true;

    private void parseChunk(String json, StringBuilder textBuf, StreamCallback callback) {
        // 提取 choices[0].delta.content
        var content = extractJsonString(json, "\"content\":\"");
        if (content != null && !content.isEmpty()) {
            if (firstChunk) {
                callback.onChunk(new StreamChunk.BlockStart(0, "text"));
                firstChunk = false;
            }
            textBuf.append(content);
            callback.onChunk(new StreamChunk.TextDelta(0, content));
        }

        // 提取 finish_reason
        var finish = extractJsonString(json, "\"finish_reason\":\"");
        if (finish != null && !finish.isEmpty() && !"null".equals(finish)) {
            if (!firstChunk) {
                callback.onChunk(new StreamChunk.BlockEnd(0, new ContentBlock.Text(textBuf.toString())));
            }
            callback.onChunk(new StreamChunk.Finish(toFinishReason(finish)));
        }

        // 提取 usage（通常在最后一条非 [DONE] 消息中）
        var usageJson = extractJsonObject(json, "\"usage\":");
        if (usageJson != null) {
            var input = extractJsonInt(usageJson, "\"prompt_tokens\":");
            var output = extractJsonInt(usageJson, "\"completion_tokens\":");
            if (input != null && output != null) {
                callback.onChunk(new StreamChunk.Usage(new TokenUsage(input, output)));
            }
        }
    }

    // ---- JSON 辅助 ----

    /** 提取 JSON 字符串值（处理 "key": "value" 和 "key":"value" 两种格式）。 */
    private String extractJsonString(String json, String key) {
        // 尝试 "key": "value" 和 "key":"value" 两种格式
        var idx = json.indexOf(key);
        if (idx < 0) {
            // 尝试无空格版本
            var noSpace = key.replace("\": \"", "\":\"");
            idx = json.indexOf(noSpace);
            if (idx < 0) return null;
        }
        var start = idx + key.length();
        // 跳过可能存在的空格
        while (start < json.length() && json.charAt(start) == ' ') start++;
        if (start >= json.length() || json.charAt(start) != '"') return null;
        start++; // 跳过开头的引号
        var end = start;
        while (end < json.length()) {
            var c = json.charAt(end);
            if (c == '\\') { end += 2; continue; }
            if (c == '"') break;
            end++;
        }
        if (end >= json.length()) return null;
        return json.substring(start, end)
                .replace("\\n", "\n")
                .replace("\\\"", "\"")
                .replace("\\\\", "\\");
    }

    /** 提取 JSON 对象（从 key 后的第一个 { 到匹配的 }）。 */
    private String extractJsonObject(String json, String key) {
        var idx = json.indexOf(key);
        if (idx < 0) return null;
        var start = json.indexOf("{", idx + key.length());
        if (start < 0) return null;
        var depth = 1;
        var end = start + 1;
        while (depth > 0 && end < json.length()) {
            var c = json.charAt(end);
            if (c == '{') depth++;
            else if (c == '}') depth--;
            end++;
        }
        return json.substring(start, end);
    }

    /** 提取 JSON 整数值。 */
    private Integer extractJsonInt(String json, String key) {
        var idx = json.indexOf(key);
        if (idx < 0) return null;
        var start = idx + key.length();
        while (start < json.length() && (json.charAt(start) == ' ' || json.charAt(start) == ':')) start++;
        var end = start;
        while (end < json.length() && Character.isDigit(json.charAt(end))) end++;
        if (end == start) return null;
        return Integer.parseInt(json.substring(start, end));
    }

    private FinishReason toFinishReason(String reason) {
        return switch (reason) {
            case "stop" -> new FinishReason.Stop();
            case "tool_calls" -> new FinishReason.ToolCalls();
            case "length" -> new FinishReason.MaxTokens();
            default -> new FinishReason.Stop();
        };
    }

    // ---- 请求序列化 ----

    String buildRequest(GenerateOptions options) {
        var sb = new StringBuilder();
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
                    if (toolMsg.content().getFirst() instanceof ContentBlock.ToolResult tr) {
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

    private String flattenText(List<ContentBlock> blocks) {
        var sb = new StringBuilder();
        for (var block : blocks) {
            if (block instanceof ContentBlock.Text t) sb.append(t.text());
        }
        return sb.toString();
    }

    private String escapeJson(String s) {
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    private String getEnvOrDefault(String name, String defaultValue) {
        var v = System.getenv(name);
        return v != null && !v.isBlank() ? v : defaultValue;
    }
}