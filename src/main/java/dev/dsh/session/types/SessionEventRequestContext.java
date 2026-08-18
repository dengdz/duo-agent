package dev.dsh.session.types;

public record SessionEventRequestContext(
        int seq, long time, boolean ignorable,
        SurfaceOp surfaceOp, int[] sourceEventSeqs,
        String provider, String model, Integer contextWindow
) implements SessionEvent {
    public SessionEventRequestContext(int seq, String provider, String model) {
        this(seq, System.currentTimeMillis(), false, null, null, provider, model, null);
    }
    @Override public String type() { return SessionEventTypes.REQUEST_CONTEXT; }
}
