package dev.dsh.session.types;

public record SessionEventStepEnd(
        int seq, long time, boolean ignorable,
        SurfaceOp surfaceOp, int[] sourceEventSeqs,
        int turn, int step
) implements SessionEvent {
    public SessionEventStepEnd(int seq, int turn, int step) {
        this(seq, System.currentTimeMillis(), false, null, null, turn, step);
    }
    @Override public String type() { return SessionEventTypes.STEP_END; }
}
