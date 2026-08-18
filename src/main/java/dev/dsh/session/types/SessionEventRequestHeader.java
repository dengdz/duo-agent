package dev.dsh.session.types;

public record SessionEventRequestHeader(
        int seq, long time, boolean ignorable,
        SurfaceOp surfaceOp, int[] sourceEventSeqs,
        EpochHeader header, String reason
) implements SessionEvent {
    public SessionEventRequestHeader(int seq, EpochHeader header, String reason) {
        this(seq, System.currentTimeMillis(), false, null, null, header, reason);
    }
    @Override public String type() { return SessionEventTypes.REQUEST_HEADER; }
}
