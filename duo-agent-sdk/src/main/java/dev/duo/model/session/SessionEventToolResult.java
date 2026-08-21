package dev.duo.model.session;

import dev.duo.model.llm.Message;

/**
 * 工具结果事件。
 *
 * @author zhangyl
 * @date 2026-08-18
 */
public record SessionEventToolResult(
        int seq, long time, boolean ignorable,
        SurfaceOp surfaceOp, int[] sourceEventSeqs,
        int turn, int step,
        Message.ToolResultMessage message,
        String errorName, String errorCode
) implements SessionEvent {
    public SessionEventToolResult(int seq, int turn, int step, Message.ToolResultMessage message, SurfaceOp surfaceOp) {
        this(seq, System.currentTimeMillis(), false, surfaceOp, null, turn, step, message, null, null);
    }

    /** 带 errorCode 的便利构造（sentinel 结果的结构化档位标记，如 ABORTED）。 */
    public SessionEventToolResult(int seq, int turn, int step, Message.ToolResultMessage message,
                                  SurfaceOp surfaceOp, String errorCode) {
        this(seq, System.currentTimeMillis(), false, surfaceOp, null, turn, step, message, null, errorCode);
    }
    @Override
    public String type() {
        return SessionEventTypes.TOOL_RESULT;
    }
}
