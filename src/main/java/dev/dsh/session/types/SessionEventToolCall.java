package dev.dsh.session.types;

import dev.dsh.util.CallId;

public record SessionEventToolCall(
        int seq, long time, boolean ignorable,
        SurfaceOp surfaceOp, int[] sourceEventSeqs,
        int turn, int step, CallId callId, String name, String arguments
) implements SessionEvent {
    public SessionEventToolCall(int seq, int turn, int step, CallId callId, String name, String arguments) {
        this(seq, System.currentTimeMillis(), false, null, null, turn, step, callId, name, arguments);
    }
    @Override public String type() { return SessionEventTypes.TOOL_CALL; }
}
