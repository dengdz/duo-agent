package dev.dsh.session.types;

import dev.dsh.llm.message.Message;
import dev.dsh.llm.types.TokenUsage;

public record SessionEventAssistantMessage(
        int seq, long time, boolean ignorable,
        SurfaceOp surfaceOp, int[] sourceEventSeqs,
        int turn, int step,
        Message.AssistantMessage message, TokenUsage usage
) implements SessionEvent {
    public SessionEventAssistantMessage(int seq, int turn, int step, Message.AssistantMessage message, SurfaceOp surfaceOp, TokenUsage usage) {
        this(seq, System.currentTimeMillis(), false, surfaceOp, null, turn, step, message, usage);
    }
    @Override public String type() { return SessionEventTypes.ASSISTANT_MESSAGE; }
}
