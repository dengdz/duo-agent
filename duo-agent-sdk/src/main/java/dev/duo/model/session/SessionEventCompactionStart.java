package dev.duo.model.session;

/**
 * 压缩事务的开启标记：持久锁，直到配对的 {@link SessionEventCompactionEnd}。
 * <p>
 * 非表面事件。
 * </p>
 *
 * @author zhangyl
 * @date 2026-08-19
 */
public record SessionEventCompactionStart(
        int seq,
        long time,
        boolean ignorable,
        SurfaceOp surfaceOp,
        int[] sourceEventSeqs,
        /** 本次压缩事务的身份标识（与 end 配对）。 */
        String compactionId,
        /** 归属 turn 号；自动压缩包在 open turn 内。 */
        Integer turn
) implements SessionEvent {

    public SessionEventCompactionStart(int seq, String compactionId, Integer turn) {
        this(seq, System.currentTimeMillis(), false, null, null, compactionId, turn);
    }

    @Override
    public String type() {
        return SessionEventTypes.COMPACTION_START;
    }
}
