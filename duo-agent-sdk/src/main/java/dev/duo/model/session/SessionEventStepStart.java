package dev.duo.model.session;

/**
 * step 开始事件。
 *
 * @author zhangyl
 * @date 2026-08-18
 */
public record SessionEventStepStart(
        int seq, long time, boolean ignorable,
        SurfaceOp surfaceOp, int[] sourceEventSeqs,
        int turn, int step
) implements SessionEvent {
    public SessionEventStepStart(int seq, int turn, int step) {
        this(seq, System.currentTimeMillis(), false, null, null, turn, step);
    }
    @Override
    public String type() {
        return SessionEventTypes.STEP_START;
    }
}
