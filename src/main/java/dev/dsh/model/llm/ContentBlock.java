package dev.dsh.model.llm;

import dev.dsh.util.CallId;

/**
 * 消息内容的原子单元——内容块。
 * <p>
 * 对应 TS 源码中的 {@code ContentBlockMap}。
 * 如需新增块类型，扩展此 sealed 层次结构即可。
 * </p>
 */
public sealed interface ContentBlock {

    /** 用户可见的纯文本。 */
    record Text(String text) implements ContentBlock {}

    /** 推理/思考内容，与可见文本分离。 */
    record Reasoning(String text) implements ContentBlock {}

    /** 模型请求的工具调用。 */
    record ToolCall(CallId id, String name, String arguments) implements ContentBlock {
        /** arguments 是模型产出的原始 JSON 字符串。 */
    }

    /** 工具执行结果，送回给模型。 */
    record ToolResult(
            CallId toolCallId,
            java.util.List<ContentBlock> content,
            boolean isError
    ) implements ContentBlock {
        public ToolResult(CallId toolCallId, java.util.List<ContentBlock> content) {
            this(toolCallId, content, false);
        }
    }
}