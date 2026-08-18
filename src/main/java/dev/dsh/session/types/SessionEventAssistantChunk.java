package dev.dsh.session.types;

import dev.dsh.llm.types.StreamChunk;

public record SessionEventAssistantChunk(
        int seq, long time, boolean ignorable,
        SurfaceOp surfaceOp, int[] sourceEventSeqs,
        int turn, int step, StreamChunk chunk
) implements SessionEvent {
    public SessionEventAssistantChunk(int seq, int turn, int step, StreamChunk chunk) {
        this(seq, System.currentTimeMillis(), false, null, null, turn, step, chunk);
    }
    @Override public String type() { return SessionEventTypes.ASSISTANT_CHUNK; }
}
