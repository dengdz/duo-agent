package dev.dsh.model.session;

public record SessionEventTurnStart(
        int seq, long time, boolean ignorable,
        SurfaceOp surfaceOp, int[] sourceEventSeqs,
        int turn
) implements SessionEvent {
    public SessionEventTurnStart(int seq, int turn) {
        this(seq, System.currentTimeMillis(), false, null, null, turn);
    }
    @Override public String type() { return SessionEventTypes.TURN_START; }
}
