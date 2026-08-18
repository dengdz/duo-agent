package dev.duo.model.session;

/**
 * 请求头事件。
 *
 * @author zhangyl
 * @date 2026-08-18
 */
public record SessionEventRequestHeader(
        int seq, long time, boolean ignorable,
        SurfaceOp surfaceOp, int[] sourceEventSeqs,
        EpochHeader header, String reason
) implements SessionEvent {
    public SessionEventRequestHeader(int seq, EpochHeader header, String reason) {
        this(seq, System.currentTimeMillis(), false, null, null, header, reason);
    }
    @Override
    public String type() {
        return SessionEventTypes.REQUEST_HEADER;
    }
}
