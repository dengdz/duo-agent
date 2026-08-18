package dev.duo.model.session;

/**
 * step 结束事件。
 *
 * @author zhangyl
 * @date 2026-08-18
 */
public record SessionEventStepEnd(
        int seq, long time, boolean ignorable,
        SurfaceOp surfaceOp, int[] sourceEventSeqs,
        int turn, int step
) implements SessionEvent {
    public SessionEventStepEnd(int seq, int turn, int step) {
        this(seq, System.currentTimeMillis(), false, null, null, turn, step);
    }
    @Override
    public String type() {
        return SessionEventTypes.STEP_END;
    }
}
