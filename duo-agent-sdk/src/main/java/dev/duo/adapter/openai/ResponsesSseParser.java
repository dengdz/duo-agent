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

import java.util.HashMap;
import java.util.Map;

/**
 * OpenAI Responses SSE 流解析器。
 * <p>
 * 解析 {@code response.*} 事件流，转换为 {@link StreamChunk}。仅处理无状态
 * 推理所需的事件子集：output item 的增删（message / reasoning /
 * function_call）、文本与思考摘要增量、工具参数增量、completed / failed /
 * incomplete 终态；托管内置工具（web_search 等）与 refusal 事件忽略。
 * </p>
 * <p>
 * 块索引采用事件携带的 {@code output_index}（与 Anthropic 的协议分配索引
 * 同理，满足「索引用于关联交错 delta」的 StreamChunk 契约）。
 * </p>
 * <p>
 * <b>线程安全</b>：实例非线程安全（携带逐流解析状态，如待定事件名与块缓冲），
 * 每次请求须新建实例（适配器已按此约定创建）。
 * </p>
 *
 * @author zhangyl
 * @date 2026-08-21
 */
final class ResponsesSseParser {

    private static final Logger logger = LoggerFactory.getLogger(ResponsesSseParser.class);

    private static final String SSE_DATA_PREFIX = "data:";
    private static final String SSE_EVENT_PREFIX = "event:";

    // 事件类型常量（data.type 的完整事件名）
    private static final String EVT_OUTPUT_ITEM_ADDED = "response.output_item.added";
    private static final String EVT_OUTPUT_ITEM_DONE = "response.output_item.done";
    private static final String EVT_OUTPUT_TEXT_DELTA = "response.output_text.delta";
    private static final String EVT_REASONING_SUMMARY_DELTA = "response.reasoning_summary_text.delta";
    private static final String EVT_FUNCTION_CALL_ARGS_DELTA = "response.function_call_arguments.delta";
    private static final String EVT_COMPLETED = "response.completed";
    private static final String EVT_FAILED = "response.failed";
    private static final String EVT_INCOMPLETE = "response.incomplete";

    // output item 类型常量
    private static final String ITEM_MESSAGE = "message";
    private static final String ITEM_REASONING = "reasoning";
    private static final String ITEM_FUNCTION_CALL = "function_call";

    /** 与 data 行成对出现的 event: 行携带的事件名（SSE 标准位置，优先于 data 内提取）。 */
    private String pendingEventName;

    /** 每个输出索引的增量缓冲（文本/思考摘要/工具参数）。 */
    private final Map<Integer, StringBuilder> itemBuffers = new HashMap<>();

    /** 每个输出索引的 item 类型（BlockEnd 载荷按此组装）。 */
    private final Map<Integer, String> itemKinds = new HashMap<>();

    /** function_call item 的 call_id 与 name（output_item.added 时到达）。 */
    private final Map<Integer, String[]> callMeta = new HashMap<>();

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
        if (data.isEmpty()) {
            return;
        }
        parseEvent(data, callback);
    }

    /**
     * 流结束时调用（completed/failed/incomplete 之后由适配层触发 onComplete）。
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
            case EVT_OUTPUT_ITEM_ADDED -> handleItemAdded(json, callback);
            case EVT_OUTPUT_ITEM_DONE -> handleItemDone(json, callback);
            case EVT_OUTPUT_TEXT_DELTA -> handleTextDelta(json, callback);
            case EVT_REASONING_SUMMARY_DELTA -> handleReasoningDelta(json, callback);
            case EVT_FUNCTION_CALL_ARGS_DELTA -> handleFunctionArgsDelta(json, callback);
            case EVT_COMPLETED -> handleCompleted(json, callback);
            case EVT_FAILED -> handleFailed(json, callback);
            case EVT_INCOMPLETE -> handleIncomplete(json, callback);
            // created/in_progress/content_part*/annotation*/内置工具/refusal 事件：无 chunk 语义，忽略
            default -> {
            }
        }
    }

    private void handleItemAdded(String json, StreamCallback callback) {
        var index = JsonFieldExtractor.extractInt(json, "output_index");
        var item = JsonFieldExtractor.extractObject(json, "item");
        if (index == null || item == null) {
            return;
        }
        var itemType = JsonFieldExtractor.extractString(item, "type");
        if (itemType == null) {
            return;
        }
        switch (itemType) {
            case ITEM_MESSAGE -> {
                itemKinds.put(index, ITEM_MESSAGE);
                callback.onChunk(new StreamChunk.BlockStart(index, SessionEventTypes.BLOCK_TEXT));
            }
            case ITEM_REASONING -> {
                itemKinds.put(index, ITEM_REASONING);
                callback.onChunk(new StreamChunk.BlockStart(index, SessionEventTypes.BLOCK_REASONING));
            }
            case ITEM_FUNCTION_CALL -> {
                // function_call item 在 added 时即携带 call_id 与 name（参数经增量到达）
                var callId = JsonFieldExtractor.extractString(item, "call_id");
                var name = JsonFieldExtractor.extractString(item, "name");
                if (callId != null) {
                    // call_id 缺失属协议异常：不登记块类型，避免 BlockStart/BlockEnd 序列不平衡
                    itemKinds.put(index, ITEM_FUNCTION_CALL);
                    callMeta.put(index, new String[]{callId, name != null ? name : ""});
                    callback.onChunk(new StreamChunk.BlockStart(index, SessionEventTypes.BLOCK_TOOL_CALL));
                    callback.onChunk(new StreamChunk.ToolCallDelta(
                            index, new CallId(callId), name != null ? name : "", ""));
                }
            }
            default -> logger.debug("忽略未知 output item 类型: {}", itemType);
        }
    }

    private void handleItemDone(String json, StreamCallback callback) {
        var index = JsonFieldExtractor.extractInt(json, "output_index");
        if (index == null) {
            return;
        }
        var kind = itemKinds.get(index);
        if (ITEM_FUNCTION_CALL.equals(kind)) {
            var meta = callMeta.get(index);
            if (meta != null) {
                var args = buffer(index).toString();
                callback.onChunk(new StreamChunk.BlockEnd(index,
                        new ContentBlock.ToolCall(new CallId(meta[0]), meta[1],
                                args.isBlank() ? "{}" : args)));
            }
        } else if (ITEM_REASONING.equals(kind)) {
            callback.onChunk(new StreamChunk.BlockEnd(index,
                    new ContentBlock.Reasoning(buffer(index).toString())));
        } else {
            callback.onChunk(new StreamChunk.BlockEnd(index,
                    new ContentBlock.Text(buffer(index).toString())));
        }
    }

    private void handleTextDelta(String json, StreamCallback callback) {
        var index = JsonFieldExtractor.extractInt(json, "output_index");
        var delta = JsonFieldExtractor.extractString(json, "delta");
        if (index != null && delta != null && !delta.isEmpty()) {
            buffer(index).append(delta);
            callback.onChunk(new StreamChunk.TextDelta(index, delta));
        }
    }

    private void handleReasoningDelta(String json, StreamCallback callback) {
        var index = JsonFieldExtractor.extractInt(json, "output_index");
        var delta = JsonFieldExtractor.extractString(json, "delta");
        if (index != null && delta != null && !delta.isEmpty()) {
            buffer(index).append(delta);
            callback.onChunk(new StreamChunk.ReasoningDelta(index, delta));
        }
    }

    private void handleFunctionArgsDelta(String json, StreamCallback callback) {
        var index = JsonFieldExtractor.extractInt(json, "output_index");
        var delta = JsonFieldExtractor.extractString(json, "delta");
        if (index != null && delta != null && !delta.isEmpty()) {
            buffer(index).append(delta);
            var meta = callMeta.get(index);
            if (meta != null) {
                callback.onChunk(new StreamChunk.ToolCallDelta(
                        index, new CallId(meta[0]), meta[1], delta));
            }
        }
    }

    private void handleCompleted(String json, StreamCallback callback) {
        // usage 在 completed 事件的 response.usage：input_tokens / output_tokens
        var response = JsonFieldExtractor.extractObject(json, "response");
        if (response != null) {
            var usage = JsonFieldExtractor.extractObject(response, "usage");
            if (usage != null) {
                var input = JsonFieldExtractor.extractInt(usage, "input_tokens");
                var output = JsonFieldExtractor.extractInt(usage, "output_tokens");
                if (input != null && output != null) {
                    callback.onChunk(new StreamChunk.Usage(new TokenUsage(input, output)));
                }
            }
        }
        callback.onChunk(new StreamChunk.Finish(new FinishReason.Stop()));
    }

    private void handleFailed(String json, StreamCallback callback) {
        var response = JsonFieldExtractor.extractObject(json, "response");
        var message = "Responses 请求失败";
        if (response != null) {
            var error = JsonFieldExtractor.extractObject(response, "error");
            if (error != null) {
                var msg = JsonFieldExtractor.extractString(error, "message");
                if (msg != null) {
                    message = msg;
                }
            }
        }
        callback.onError(new IllegalStateException(message));
    }

    private void handleIncomplete(String json, StreamCallback callback) {
        // incomplete 常见原因为 max_output_tokens 截断；其余原因归一为 Stop 并告警
        var response = JsonFieldExtractor.extractObject(json, "response");
        var reason = "unknown";
        if (response != null) {
            var details = JsonFieldExtractor.extractObject(response, "incomplete_details");
            if (details != null) {
                var r = JsonFieldExtractor.extractString(details, "reason");
                if (r != null) {
                    reason = r;
                }
            }
        }
        if ("max_output_tokens".equals(reason)) {
            callback.onChunk(new StreamChunk.Finish(new FinishReason.MaxTokens()));
        } else {
            logger.warn("Responses incomplete 原因: {}", reason);
            callback.onChunk(new StreamChunk.Finish(new FinishReason.Stop()));
        }
    }

    private StringBuilder buffer(int index) {
        return itemBuffers.computeIfAbsent(index, k -> new StringBuilder());
    }
}
