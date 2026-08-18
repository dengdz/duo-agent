package dev.dsh.model.session;

/**
 * 种子历史结束标记事件。
 *
 * @author zhangyl
 * @date 2026-08-18
 */
public record SessionEventSessionEndSeed(
        int seq, long time, boolean ignorable,
        SurfaceOp surfaceOp, int[] sourceEventSeqs
) implements SessionEvent {
    public SessionEventSessionEndSeed(int seq) {
        this(seq, System.currentTimeMillis(), true, null, null);
    }
    @Override
    public String type() {
        return SessionEventTypes.SESSION_END_SEED;
    }
    @Override
    public boolean ignorable() {
        return true;
    }
}
