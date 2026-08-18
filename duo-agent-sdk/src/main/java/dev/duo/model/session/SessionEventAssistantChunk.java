package dev.duo.model.session;

import dev.duo.model.llm.StreamChunk;

/**
 * 助手消息的流式输出分块事件。
 *
 * @author zhangyl
 * @date 2026-08-18
 */
public record SessionEventAssistantChunk(
        int seq, long time, boolean ignorable,
        SurfaceOp surfaceOp, int[] sourceEventSeqs,
        int turn, int step, StreamChunk chunk
) implements SessionEvent {
    public SessionEventAssistantChunk(int seq, int turn, int step, StreamChunk chunk) {
        this(seq, System.currentTimeMillis(), false, null, null, turn, step, chunk);
    }
    @Override
    public String type() {
        return SessionEventTypes.ASSISTANT_CHUNK;
    }
}
