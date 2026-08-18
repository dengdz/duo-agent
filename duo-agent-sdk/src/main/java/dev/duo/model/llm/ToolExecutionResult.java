package dev.duo.model.llm;

import java.util.List;

/**
 * 工具执行结果。
 *
 * @author zhangyl
 * @date 2026-08-18
 */
public record ToolExecutionResult(
        /** 是否执行失败。 */
        boolean isError,
        /** 结果文本内容。 */
        List<ContentBlock> content
) {
    public ToolExecutionResult(String text) {
        this(false, List.of(new ContentBlock.Text(text)));
    }

    public ToolExecutionResult(Throwable error) {
        this(true, List.of(new ContentBlock.Text(error.getMessage() != null ? error.getMessage() : "未知错误")));
    }
}