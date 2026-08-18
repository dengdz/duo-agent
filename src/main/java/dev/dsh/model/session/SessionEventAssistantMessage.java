package dev.dsh.model.session;

import dev.dsh.model.llm.Message;
import dev.dsh.model.llm.TokenUsage;

/**
 * 助手完整消息事件。
 *
 * @author zhangyl
 * @date 2026-08-18
 */
public record SessionEventAssistantMessage(
        int seq, long time, boolean ignorable,
        SurfaceOp surfaceOp, int[] sourceEventSeqs,
        int turn, int step,
        Message.AssistantMessage message, TokenUsage usage
) implements SessionEvent {
    public SessionEventAssistantMessage(
            int seq, int turn, int step,
            Message.AssistantMessage message, SurfaceOp surfaceOp, TokenUsage usage
    ) {
        this(seq, System.currentTimeMillis(), false, surfaceOp, null, turn, step, message, usage);
    }
    @Override
    public String type() {
        return SessionEventTypes.ASSISTANT_MESSAGE;
    }
}
