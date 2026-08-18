package dev.dsh.model.session;

import dev.dsh.model.llm.Message;

/**
 * 用户消息事件。
 *
 * @author zhangyl
 * @date 2026-08-18
 */
public record SessionEventUserMessage(
        int seq, long time, boolean ignorable,
        SurfaceOp surfaceOp, int[] sourceEventSeqs,
        Message.UserMessage message
) implements SessionEvent {
    public SessionEventUserMessage(int seq, Message.UserMessage message, SurfaceOp surfaceOp) {
        this(seq, System.currentTimeMillis(), false, surfaceOp, null, message);
    }
    @Override
    public String type() {
        return SessionEventTypes.USER_MESSAGE;
    }
}
