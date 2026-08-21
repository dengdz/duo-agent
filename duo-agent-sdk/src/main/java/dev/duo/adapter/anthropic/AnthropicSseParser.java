package dev.duo.adapter.anthropic;

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

import java.util.HashMap;
import java.util.Map;

/**
 * Anthropic Messages SSE 流解析器。
 * <p>
 * 解析 Messages 协议的事件流（message_start / content_block_start /
 * content_block_delta / content_block_stop / message_delta / message_stop /
 * ping / error），转换为 {@link StreamChunk}。与 Chat Completions 的差异：
 * 事件按顶层 {@code type} 判别；内容块索引由协议分配（自然支持交错与多工具调用）；
 * thinking 增量经 {@code thinking_delta} 透出；usage 分两段到达
 * （message_start 带输入、message_delta 带输出）。
 * </p>
 * <p>
 * 块索引直接透传协议的 {@code index}（与 Chat Completions 的固定索引约定不同，
 * 两者都只满足「索引用于关联交错 delta」的 StreamChunk 契约）。
 * </p>
 * <p>
 * <b>线程安全</b>：实例非线程安全（携带逐流解析状态，如待定事件名与块缓冲），
 * 每次请求须新建实例（适配器已按此约定创建）。
 * </p>
 *
 * @author zhangyl
 * @date 2026-08-21
 */
final class AnthropicSseParser {

    private static final Logger logger = LoggerFactory.getLogger(AnthropicSseParser.class);

    // SSE 协议常量
    private static final String SSE_DATA_PREFIX = "data:";
    private static final String SSE_EVENT_PREFIX = "event:";

    // 事件类型常量
    private static final String EVT_MESSAGE_START = "message_start";
    private static final String EVT_CONTENT_BLOCK_START = "content_block_start";
    private static final String EVT_CONTENT_BLOCK_DELTA = "content_block_delta";
    private static final String EVT_CONTENT_BLOCK_STOP = "content_block_stop";
    private static final String EVT_MESSAGE_DELTA = "message_delta";
    private static final String EVT_ERROR = "error";

    // 内容块类型常量
    private static final String BLOCK_TYPE_TEXT = "text";
    private static final String BLOCK_TYPE_THINKING = "thinking";
    private static final String BLOCK_TYPE_TOOL_USE = "tool_use";

    // delta 类型常量
    private static final String DELTA_TEXT = "text_delta";
    private static final String DELTA_THINKING = "thinking_delta";
    private static final String DELTA_INPUT_JSON = "input_json_delta";

    /** 每个内容块索引的缓冲（text/thinking 增量、tool_use 参数增量）。 */
    private final Map<Integer, StringBuilder> blockBuffers = new HashMap<>();

    /** 每个内容块索引的块类型（BlockEnd 载荷按此组装为 Text/Reasoning/ToolCall）。 */
    private final Map<Integer, String> blockKinds = new HashMap<>();

    /** 每个工具调用块的 id 与 name（block_start 时到达）。 */
    private final Map<Integer, String[]> toolMeta = new HashMap<>();

    /** 与 data 行成对出现的 event: 行携带的事件名（SSE 标准位置，优先于 data 内提取）。 */
    private String pendingEventName;

    /** message_start 携带的输入 token 数（与 message_delta 的输出合成 Usage）。 */
    private Integer usageInputTokens;

    /**
     * 解析 SSE 流的单行数据。
     *
     * @param line SSE 数据行
     * @param callback 流回调
     */
    void parseLine(String line, StreamCallback callback) {
        if (line.startsWith(SSE_EVENT_PREFIX)) {
            // event: 行是事件名的权威来源（SSE 标准）：data 内的 type 字段位置
            // 依赖发送方的字段顺序，网关重排字段后会误匹配嵌套对象的 type
            pendingEventName = line.substring(SSE_EVENT_PREFIX.length()).trim();
            return;
        }
        if (line.isBlank()) {
            // 空行是 SSE 事件边界：当前事件结束，清空待定事件名
            pendingEventName = null;
            return;
        }
        if (!line.startsWith(SSE_DATA_PREFIX)) {
            return;
        }
        var data = line.substring(SSE_DATA_PREFIX.length());
        if (data.startsWith(" ")) {
            data = data.substring(1);
        }
        if (data.isEmpty()) {
            return;
        }
        parseEvent(data, callback);
    }

    /**
     * 流结束时调用（message_stop 之后由适配器触发 onComplete 前的兜底）。
     */
    void onStreamComplete(StreamCallback callback) {
        callback.onComplete();
    }

    private void parseEvent(String json, StreamCallback callback) {
        var type = pendingEventName != null
                ? pendingEventName
                : JsonFieldExtractor.extractString(json, "type");
        if (type == null) {
            return;
        }
        switch (type) {
            case EVT_MESSAGE_START -> handleMessageStart(json);
            case EVT_CONTENT_BLOCK_START -> handleBlockStart(json, callback);
            case EVT_CONTENT_BLOCK_DELTA -> handleBlockDelta(json, callback);
            case EVT_CONTENT_BLOCK_STOP -> handleBlockStop(json, callback);
            case EVT_MESSAGE_DELTA -> handleMessageDelta(json, callback);
            case EVT_ERROR -> handleError(json, callback);
            // message_stop / ping：无 chunk 语义（完成信号由适配层的 onComplete 承载）
            default -> {
            }
        }
    }

    private void handleMessageStart(String json) {
        var message = JsonFieldExtractor.extractObject(json, "message");
        if (message != null) {
            var usage = JsonFieldExtractor.extractObject(message, "usage");
            if (usage != null) {
                usageInputTokens = JsonFieldExtractor.extractInt(usage, "input_tokens");
            }
        }
    }

    private void handleBlockStart(String json, StreamCallback callback) {
        var index = JsonFieldExtractor.extractInt(json, "index");
        var block = JsonFieldExtractor.extractObject(json, "content_block");
        if (index == null || block == null) {
            return;
        }
        var blockType = JsonFieldExtractor.extractString(block, "type");
        if (blockType == null) {
            return;
        }
        switch (blockType) {
            case BLOCK_TYPE_TEXT -> {
                blockKinds.put(index, BLOCK_TYPE_TEXT);
                callback.onChunk(
                        new StreamChunk.BlockStart(index, SessionEventTypes.BLOCK_TEXT));
            }
            case BLOCK_TYPE_THINKING -> {
                blockKinds.put(index, BLOCK_TYPE_THINKING);
                callback.onChunk(
                        new StreamChunk.BlockStart(index, SessionEventTypes.BLOCK_REASONING));
            }
            case BLOCK_TYPE_TOOL_USE -> {
                // tool_use 块在 block_start 即携带 id 与 name（输入经 input_json_delta 增量到达）
                var id = JsonFieldExtractor.extractString(block, "id");
                var name = JsonFieldExtractor.extractString(block, "name");
                if (id != null) {
                    // id 缺失属协议异常：不登记块类型，避免 BlockStart/BlockEnd 序列不平衡
                    blockKinds.put(index, BLOCK_TYPE_TOOL_USE);
                    toolMeta.put(index, new String[]{id, name != null ? name : ""});
                    callback.onChunk(new StreamChunk.BlockStart(index, SessionEventTypes.BLOCK_TOOL_CALL));
                    callback.onChunk(new StreamChunk.ToolCallDelta(
                            index, new CallId(id), name != null ? name : "", ""));
                }
            }
            default -> logger.debug("忽略未知内容块类型: {}", blockType);
        }
    }

    private void handleBlockDelta(String json, StreamCallback callback) {
        var index = JsonFieldExtractor.extractInt(json, "index");
        var delta = JsonFieldExtractor.extractObject(json, "delta");
        if (index == null || delta == null) {
            return;
        }
        var deltaType = JsonFieldExtractor.extractString(delta, "type");
        if (deltaType == null) {
            return;
        }
        switch (deltaType) {
            case DELTA_TEXT -> {
                var text = JsonFieldExtractor.extractString(delta, "text");
                if (text != null && !text.isEmpty()) {
                    buffer(index).append(text);
                    callback.onChunk(new StreamChunk.TextDelta(index, text));
                }
            }
            case DELTA_THINKING -> {
                // 思考增量载荷字段为 thinking（非 text）
                var thinking = JsonFieldExtractor.extractString(delta, "thinking");
                if (thinking != null && !thinking.isEmpty()) {
                    buffer(index).append(thinking);
                    callback.onChunk(new StreamChunk.ReasoningDelta(index, thinking));
                }
            }
            case DELTA_INPUT_JSON -> {
                // 工具参数增量载荷字段为 partial_json
                var partial = JsonFieldExtractor.extractString(delta, "partial_json");
                if (partial != null && !partial.isEmpty()) {
                    buffer(index).append(partial);
                    var meta = toolMeta.get(index);
                    if (meta != null) {
                        callback.onChunk(new StreamChunk.ToolCallDelta(
                                index, new CallId(meta[0]), meta[1], partial));
                    }
                }
            }
            // signature_delta：思考块签名（用于回传验证思考完整性），本 SDK 不回传思考，忽略
            default -> {
            }
        }
    }

    private void handleBlockStop(String json, StreamCallback callback) {
        var index = JsonFieldExtractor.extractInt(json, "index");
        if (index == null) {
            return;
        }
        var content = buffer(index).toString();
        var kind = blockKinds.get(index);
        if (BLOCK_TYPE_TOOL_USE.equals(kind)) {
            var meta = toolMeta.get(index);
            if (meta != null) {
                var fullArgs = content.isBlank() ? "{}" : content;
                callback.onChunk(new StreamChunk.BlockEnd(index,
                        new ContentBlock.ToolCall(new CallId(meta[0]), meta[1], fullArgs)));
            }
        } else if (BLOCK_TYPE_THINKING.equals(kind)) {
            callback.onChunk(new StreamChunk.BlockEnd(index,
                    new ContentBlock.Reasoning(content)));
        } else {
            callback.onChunk(new StreamChunk.BlockEnd(index, new ContentBlock.Text(content)));
        }
    }

    private void handleMessageDelta(String json, StreamCallback callback) {
        // usage（输出 token）与 stop_reason 都在 message_delta 到达：先 Usage 后 Finish。
        // message_start 缺输入计数属协议异常：以 0 兜底，不静默丢弃输出计数
        var usage = JsonFieldExtractor.extractObject(json, "usage");
        if (usage != null) {
            var output = JsonFieldExtractor.extractInt(usage, "output_tokens");
            if (output != null) {
                var input = usageInputTokens != null ? usageInputTokens : 0;
                callback.onChunk(new StreamChunk.Usage(new TokenUsage(input, output)));
            }
        }
        var delta = JsonFieldExtractor.extractObject(json, "delta");
        if (delta != null) {
            var stopReason = JsonFieldExtractor.extractString(delta, "stop_reason");
            if (stopReason != null) {
                callback.onChunk(new StreamChunk.Finish(toFinishReason(stopReason)));
            }
        }
    }

    private void handleError(String json, StreamCallback callback) {
        var error = JsonFieldExtractor.extractObject(json, "error");
        var message = error != null
                ? JsonFieldExtractor.extractString(error, "message")
                : "Anthropic 流式错误";
        callback.onError(new IllegalStateException(
                message != null ? message : "Anthropic 流式错误"));
    }

    private FinishReason toFinishReason(String stopReason) {
        return switch (stopReason) {
            case "end_turn", "stop_sequence" -> new FinishReason.Stop();
            case "tool_use" -> new FinishReason.ToolCalls();
            case "max_tokens" -> new FinishReason.MaxTokens();
            default -> {
                logger.warn("Unknown stop_reason: {}", stopReason);
                yield new FinishReason.Stop();
            }
        };
    }

    private StringBuilder buffer(int index) {
        return blockBuffers.computeIfAbsent(index, k -> new StringBuilder());
    }
}
