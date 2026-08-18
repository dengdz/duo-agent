package dev.dsh.model.session;

/**
 * turn 开始事件。
 *
 * @author zhangyl
 * @date 2026-08-18
 */
public record SessionEventTurnStart(
        int seq, long time, boolean ignorable,
        SurfaceOp surfaceOp, int[] sourceEventSeqs,
        int turn
) implements SessionEvent {
    public SessionEventTurnStart(int seq, int turn) {
        this(seq, System.currentTimeMillis(), false, null, null, turn);
    }
    @Override
    public String type() {
        return SessionEventTypes.TURN_START;
    }
}
