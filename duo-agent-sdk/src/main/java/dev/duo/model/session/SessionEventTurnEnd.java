package dev.duo.model.session;

/**
 * turn 结束事件。
 *
 * @author zhangyl
 * @date 2026-08-18
 */
public record SessionEventTurnEnd(
        int seq, long time, boolean ignorable,
        SurfaceOp surfaceOp, int[] sourceEventSeqs,
        int turn, TurnEndReason reason
) implements SessionEvent {
    public SessionEventTurnEnd(int seq, int turn, TurnEndReason reason) {
        this(seq, System.currentTimeMillis(), false, null, null, turn, reason);
    }
    @Override
    public String type() {
        return SessionEventTypes.TURN_END;
    }
}
