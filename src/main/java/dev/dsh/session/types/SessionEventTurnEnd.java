package dev.dsh.session.types;

public record SessionEventTurnEnd(
        int seq, long time, boolean ignorable,
        SurfaceOp surfaceOp, int[] sourceEventSeqs,
        int turn, TurnEndReason reason
) implements SessionEvent {
    public SessionEventTurnEnd(int seq, int turn, TurnEndReason reason) {
        this(seq, System.currentTimeMillis(), false, null, null, turn, reason);
    }
    @Override public String type() { return SessionEventTypes.TURN_END; }
}
