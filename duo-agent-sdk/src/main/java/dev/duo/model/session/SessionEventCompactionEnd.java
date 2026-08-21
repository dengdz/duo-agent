package dev.duo.model.session;

/**
 * 压缩事务的关闭标记：无论成败都恰好落一条，与 {@link SessionEventCompactionStart} 配对。
 * <p>
 * 非表面事件。失败时携带 error 摘要，保证未闭合的 start 可被检测（压缩锁语义）。
 * </p>
 *
 * @author zhangyl
 * @date 2026-08-19
 */
public record SessionEventCompactionEnd(
        int seq,
        long time,
        boolean ignorable,
        SurfaceOp surfaceOp,
        int[] sourceEventSeqs,
        /** 与 start 配对的事务标识。 */
        String compactionId,
        /** 归属 turn 号。 */
        Integer turn,
        /** 失败时的错误摘要；成功为 null。 */
        String error
) implements SessionEvent {

    public SessionEventCompactionEnd(int seq, String compactionId, Integer turn, String error) {
        this(seq, System.currentTimeMillis(), false, null, null, compactionId, turn, error);
    }

    @Override
    public String type() {
        return SessionEventTypes.COMPACTION_END;
    }
}
