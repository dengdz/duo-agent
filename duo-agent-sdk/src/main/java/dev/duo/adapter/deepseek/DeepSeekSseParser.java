package dev.duo.adapter.deepseek;

import dev.duo.api.llm.StreamCallback;
import dev.duo.model.llm.ContentBlock;
import dev.duo.model.llm.FinishReason;
import dev.duo.model.llm.StreamChunk;
import dev.duo.model.llm.TokenUsage;
import dev.duo.model.session.SessionEventTypes;
import dev.duo.util.CallId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * DeepSeek SSE 流解析器。
 * <p>
 * 负责解析 DeepSeek API 返回的 SSE 流数据，提取文本 delta、工具调用 delta 并转换为 StreamChunk。
 * </p>
 *
 * @author zhangyl
 * @date 2026-08-18
 */
class DeepSeekSseParser {

    private static final Logger logger = LoggerFactory.getLogger(DeepSeekSseParser.class);

    // SSE 协议常量
    private static final String SSE_DATA_PREFIX = "data: ";
    private static final String SSE_DONE_MARKER = "[DONE]";

    // JSON 常量
    private static final String JSON_NULL_STRING = "null";

    // 块索引常量
    private static final int TEXT_BLOCK_INDEX = 0;
    private static final int TOOL_CALL_BLOCK_INDEX = 1;

    // ---- 流式解析状态（每次 reset() 后重置）----

    /** 是否是第一个文本块（用于判断是否需要发送 BlockStart）。*/
    private boolean firstChunk = true;

    /** 是否包含工具调用（用于切换文本块到工具调用块）。*/
    private boolean hasToolCalls = false;

    /** 工具调用块是否已开始（避免重复发送 BlockStart）。*/
    private boolean toolCallBlockStarted = false;

    /** 当前工具调用的 ID。*/
    private String currentToolCallId;

    /** 当前工具调用的名称。*/
    private String currentToolCallName;

    /** 当前工具调用的参数累积缓冲区。*/
    private final StringBuilder currentToolCallArgs = new StringBuilder();

    /** 文本内容累积缓冲区。*/
    private final StringBuilder textBuffer = new StringBuilder();

    /**
     * 解析 SSE 流的单行数据。
     *
     * @param line SSE 数据行
     * @param callback 流回调
     */
    void parseLine(String line, StreamCallback callback) {
        if (!line.startsWith(SSE_DATA_PREFIX)) {
            return;
        }

        var data = line.substring(SSE_DATA_PREFIX.length()).trim();
        if (SSE_DONE_MARKER.equals(data) || data.isEmpty()) {
            return;
        }

        parseChunk(data, callback);
    }

    /**
     * 流结束时调用，确保所有块都已关闭。
     */
    void onStreamComplete(StreamCallback callback) {
        // 如果流中没有 finish_reason，兜底发送
        callback.onComplete();
    }

    /**
     * 重置解析状态，准备解析新的流。
     */
    void reset() {
        firstChunk = true;
        hasToolCalls = false;
        toolCallBlockStarted = false;
        currentToolCallId = null;
        currentToolCallName = null;
        currentToolCallArgs.setLength(0);
        textBuffer.setLength(0);
    }

    /**
     * 解析单个 SSE data 行，提取文本 delta、工具调用 delta 并发射。
     */
    private void parseChunk(String json, StreamCallback callback) {
        // 提取 choices[0].delta.content
        var content = DeepSeekJsonExtractor.extractString(json, "content");
        if (content != null && !content.isEmpty()) {
            if (firstChunk) {
                callback.onChunk(new StreamChunk.BlockStart(TEXT_BLOCK_INDEX, SessionEventTypes.BLOCK_TEXT));
                firstChunk = false;
            }
            textBuffer.append(content);
            callback.onChunk(new StreamChunk.TextDelta(TEXT_BLOCK_INDEX, content));
        }

        // 提取工具调用：仅在 JSON 包含 "tool_calls" 时解析
        if (json.contains("\"tool_calls\"")) {
            parseToolCall(json, callback);
        }

        // 提取 finish_reason — 此时关闭工具调用块
        var finish = DeepSeekJsonExtractor.extractString(json, "finish_reason");
        if (finish != null && !finish.isEmpty() && !JSON_NULL_STRING.equals(finish)) {
            handleFinishReason(finish, callback);
        }

        // 提取 usage（通常在最后一条非 [DONE] 消息中）
        var usageJson = DeepSeekJsonExtractor.extractObject(json, "usage");
        if (usageJson != null) {
            var input = DeepSeekJsonExtractor.extractInt(usageJson, "prompt_tokens");
            var output = DeepSeekJsonExtractor.extractInt(usageJson, "completion_tokens");
            if (input != null && output != null) {
                callback.onChunk(new StreamChunk.Usage(new TokenUsage(input, output)));
            }
        }
    }

    private void parseToolCall(String json, StreamCallback callback) {
        var toolCallId = DeepSeekJsonExtractor.extractToolCallId(json);
        var toolCallName = DeepSeekJsonExtractor.extractToolCallFunctionName(json);
        var toolCallArgs = DeepSeekJsonExtractor.extractToolCallArguments(json);

        // 首次收到工具调用 → 关闭文本块（如果有），标记 hasToolCalls
        if (!hasToolCalls) {
            if (textBuffer.length() > 0) {
                callback.onChunk(new StreamChunk.BlockEnd(TEXT_BLOCK_INDEX, new ContentBlock.Text(textBuffer.toString())));
            }
            hasToolCalls = true;
        }

        // 首次收到工具调用 id → 发出 BlockStart 并记录名称
        if (toolCallId != null) {
            currentToolCallId = toolCallId;
            currentToolCallName = toolCallName != null ? toolCallName : "";
            currentToolCallArgs.setLength(0);
            if (!toolCallBlockStarted) {
                callback.onChunk(new StreamChunk.BlockStart(TOOL_CALL_BLOCK_INDEX, SessionEventTypes.BLOCK_TOOL_CALL));
                toolCallBlockStarted = true;
            }
        }

        // 累加参数并实时发射 ToolCallDelta
        if (toolCallArgs != null) {
            currentToolCallArgs.append(toolCallArgs);
            if (currentToolCallId != null) {
                callback.onChunk(new StreamChunk.ToolCallDelta(
                        TOOL_CALL_BLOCK_INDEX, new CallId(currentToolCallId),
                        currentToolCallName, toolCallArgs));
            }
        }
    }

    private void handleFinishReason(String finish, StreamCallback callback) {
        if (hasToolCalls && currentToolCallId != null) {
            var id = new CallId(currentToolCallId);
            callback.onChunk(new StreamChunk.ToolCallDelta(
                    TOOL_CALL_BLOCK_INDEX, id, currentToolCallName, currentToolCallArgs.toString()
            ));
            callback.onChunk(new StreamChunk.BlockEnd(TOOL_CALL_BLOCK_INDEX,
                    new ContentBlock.ToolCall(id, currentToolCallName, currentToolCallArgs.toString())));
        } else if (!firstChunk) {
            callback.onChunk(new StreamChunk.BlockEnd(TEXT_BLOCK_INDEX, new ContentBlock.Text(textBuffer.toString())));
        }

        callback.onChunk(new StreamChunk.Finish(toFinishReason(finish)));
    }

    private FinishReason toFinishReason(String reason) {
        return switch (reason) {
            case "stop" -> new FinishReason.Stop();
            case "tool_calls" -> new FinishReason.ToolCalls();
            case "length" -> new FinishReason.MaxTokens();
            default -> {
                logger.warn("Unknown finish reason: {}", reason);
                yield new FinishReason.Stop();
            }
        };
    }
}

