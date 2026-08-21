package dev.duo.adapter.openai;

import dev.duo.api.llm.StreamCallback;
import dev.duo.model.llm.ContentBlock;
import dev.duo.model.llm.FinishReason;
import dev.duo.model.llm.StreamChunk;
import dev.duo.model.llm.TokenUsage;
import dev.duo.model.session.SessionEventTypes;
import dev.duo.util.CallId;
import dev.duo.util.JsonFieldExtractor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * OpenAI Chat Completions SSE 流解析器。
 * <p>
 * 解析 Chat Completions 兼容端点的 SSE 流，提取思考增量（协议变体字段）、
 * 文本 delta、工具调用 delta、usage 与 finish_reason，转换为 {@link StreamChunk}。
 * 泛化自 DeepSeek 协议实现；厂商对流式思考字段的命名差异
 * （如 DeepSeek 的 {@code reasoning_content}）经构造参数 {@code reasoningContentField}
 * 参数化，null 表示该端点不透出流式思考。
 * </p>
 * <p>
 * 块索引仅用于关联交错的 delta（{@link StreamChunk} 契约），无顺序语义：
 * 文本块固定 0、工具调用块固定 1（与泛化前行为一致），思考块固定 2。
 * </p>
 *
 * @author zhangyl
 * @date 2026-08-21
 */
final class OpenAiSseParser {

    private static final Logger logger = LoggerFactory.getLogger(OpenAiSseParser.class);

    // SSE 协议常量
    private static final String SSE_DATA_PREFIX = "data:";
    private static final String SSE_DONE_MARKER = "[DONE]";

    // JSON 常量
    private static final String JSON_NULL_STRING = "null";

    // 块索引常量
    private static final int TEXT_BLOCK_INDEX = 0;
    private static final int TOOL_CALL_BLOCK_INDEX = 1;
    private static final int REASONING_BLOCK_INDEX = 2;

    /** 流式思考的 delta 字段名（如 "reasoning_content"）；null 表示不解析思考流。 */
    private final String reasoningContentField;

    /** 解析器的构造入口在 {@link OpenAiSseParser#create(String)}。 */
    private OpenAiSseParser(String reasoningContentField) {
        this.reasoningContentField = reasoningContentField;
    }

    /**
     * 创建解析器。
     *
     * @param reasoningContentField 思考增量的响应字段名（null 表示端点不透出流式思考）
     * @return 解析器实例
     */
    static OpenAiSseParser create(String reasoningContentField) {
        return new OpenAiSseParser(reasoningContentField);
    }

    // ---- 流式解析状态（每次 reset() 后重置）----

    /** 是否是第一个文本块（用于判断是否需要发送 BlockStart）。 */
    private boolean firstChunk = true;

    /** 思考块是否已开始（用于判断是否需要发送 BlockStart）。 */
    private boolean reasoningBlockStarted = false;

    /** 思考内容累积缓冲区（用于组装 BlockEnd 载荷）。 */
    private final StringBuilder reasoningBuffer = new StringBuilder();

    /** 是否包含工具调用（用于切换文本块到工具调用块）。 */
    private boolean hasToolCalls = false;

    /** 工具调用块是否已开始（避免重复发送 BlockStart）。 */
    private boolean toolCallBlockStarted = false;

    /** 当前工具调用的 ID。 */
    private String currentToolCallId;

    /** 当前工具调用的名称。 */
    private String currentToolCallName;

    /** 当前工具调用的参数累积缓冲区。 */
    private final StringBuilder currentToolCallArgs = new StringBuilder();

    /** 文本内容累积缓冲区。 */
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

        var data = line.substring(SSE_DATA_PREFIX.length());
        if (data.startsWith(" ")) {
            data = data.substring(1);
        }
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
        reasoningBlockStarted = false;
        reasoningBuffer.setLength(0);
        hasToolCalls = false;
        toolCallBlockStarted = false;
        currentToolCallId = null;
        currentToolCallName = null;
        currentToolCallArgs.setLength(0);
        textBuffer.setLength(0);
    }

    /**
     * 解析单个 SSE data 行，提取思考/文本/工具调用增量并发射。
     */
    private void parseChunk(String json, StreamCallback callback) {
        // 提取思考 delta（仅协议变体字段配置时；DeepSeek 系为 reasoning_content）
        if (reasoningContentField != null) {
            var reasoning = JsonFieldExtractor.extractString(json, reasoningContentField);
            if (reasoning != null && !reasoning.isEmpty()) {
                if (!reasoningBlockStarted) {
                    callback.onChunk(new StreamChunk.BlockStart(
                            REASONING_BLOCK_INDEX, SessionEventTypes.BLOCK_REASONING));
                    reasoningBlockStarted = true;
                }
                reasoningBuffer.append(reasoning);
                callback.onChunk(new StreamChunk.ReasoningDelta(REASONING_BLOCK_INDEX, reasoning));
            }
        }

        // 提取 choices[0].delta.content
        var content = JsonFieldExtractor.extractString(json, "content");
        if (content != null && !content.isEmpty()) {
            // 首个文本 delta 前关闭思考块（思考在前、回答在后的流序）
            if (reasoningBlockStarted) {
                callback.onChunk(new StreamChunk.BlockEnd(REASONING_BLOCK_INDEX,
                        new ContentBlock.Reasoning(reasoningBuffer.toString())));
                reasoningBlockStarted = false;
            }
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

        // 提取 usage — 必须先于 finish 处理（StreamChunk 契约：usage 在终结
        // finish 之前发送；DeepSeek 等端点的两者常在同一条 data 中）
        var usageJson = JsonFieldExtractor.extractObject(json, "usage");
        if (usageJson != null) {
            var input = JsonFieldExtractor.extractInt(usageJson, "prompt_tokens");
            var output = JsonFieldExtractor.extractInt(usageJson, "completion_tokens");
            if (input != null && output != null) {
                callback.onChunk(new StreamChunk.Usage(new TokenUsage(input, output)));
            }
        }

        // 提取 finish_reason — 此时关闭文本块
        var finish = JsonFieldExtractor.extractString(json, "finish_reason");
        if (finish != null && !finish.isEmpty() && !JSON_NULL_STRING.equals(finish)) {
            handleFinishReason(finish, callback);
        }
    }

    private void parseToolCall(String json, StreamCallback callback) {
        var toolCallId = JsonFieldExtractor.extractToolCallId(json);
        var toolCallName = JsonFieldExtractor.extractToolCallFunctionName(json);
        var toolCallArgs = JsonFieldExtractor.extractToolCallArguments(json);

        // 首次收到工具调用 → 关闭文本块（如果有），标记 hasToolCalls
        if (!hasToolCalls) {
            if (reasoningBlockStarted) {
                callback.onChunk(new StreamChunk.BlockEnd(REASONING_BLOCK_INDEX,
                        new ContentBlock.Reasoning(reasoningBuffer.toString())));
                reasoningBlockStarted = false;
            }
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
        } else if (reasoningBlockStarted) {
            // 纯思考回复（无文本无工具）：关闭思考块
            callback.onChunk(new StreamChunk.BlockEnd(REASONING_BLOCK_INDEX,
                    new ContentBlock.Reasoning(reasoningBuffer.toString())));
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
