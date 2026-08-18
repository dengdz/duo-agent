package dev.dsh.llm.types;

import dev.dsh.util.CallId;

/**
 * 适配器输出的原始流式协议。
 * <p>
 * 块索引用于关联交错的 delta，{@code blockEnd} 携带组装好的完整块。
 * usage 在终结 finish 之前发送，之后不再有数据；
 * 工具参数保持原始 JSON 字符串。
 * </p>
 * <p>
 * 对应 TS 源码中的 {@code StreamChunk}。
 * </p>
 */
public sealed interface StreamChunk {

    /** 指定索引处的新块开始。 */
    record BlockStart(int index, String blockType) implements StreamChunk {}

    /** 索引处块的文本内容增量。 */
    record TextDelta(int index, String text) implements StreamChunk {}

    /** 索引处块的推理内容增量。 */
    record ReasoningDelta(int index, String text) implements StreamChunk {}

    /** 索引处工具调用块的增量。 */
    record ToolCallDelta(
            int index,
            CallId id,
            String name,
            /** 部分 JSON 字符串；跨多个 delta 累加构成完整参数。 */
            String argumentsDelta
    ) implements StreamChunk {}

    /** 块结束；携带完整的组装块。 */
    record BlockEnd(int index, ContentBlock block) implements StreamChunk {}

    /** 本次请求的 token 用量。 */
    record Usage(TokenUsage usage) implements StreamChunk {}

    /** 终结的 finish 原因。 */
    record Finish(FinishReason reason, Object replayState) implements StreamChunk {
        public Finish(FinishReason reason) {
            this(reason, null);
        }
    }
}