package dev.dsh.model.session;

import dev.dsh.model.llm.Message;

public record SessionEventUserMessage(
        int seq, long time, boolean ignorable,
        SurfaceOp surfaceOp, int[] sourceEventSeqs,
        Message.UserMessage message
) implements SessionEvent {
    public SessionEventUserMessage(int seq, Message.UserMessage message, SurfaceOp surfaceOp) {
        this(seq, System.currentTimeMillis(), false, surfaceOp, null, message);
    }
    @Override public String type() { return SessionEventTypes.USER_MESSAGE; }
}
