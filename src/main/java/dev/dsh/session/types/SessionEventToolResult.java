package dev.dsh.session.types;

import dev.dsh.llm.message.Message;

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
    @Override public String type() { return SessionEventTypes.TOOL_RESULT; }
}
