package dev.duo.model.session;

/**
 * 请求上下文事件。
 *
 * @author zhangyl
 * @date 2026-08-18
 */
public record SessionEventRequestContext(
        int seq, long time, boolean ignorable,
        SurfaceOp surfaceOp, int[] sourceEventSeqs,
        String provider, String model, Integer contextWindow
) implements SessionEvent {
    public SessionEventRequestContext(int seq, String provider, String model) {
        this(seq, System.currentTimeMillis(), false, null, null, provider, model, null);
    }
    @Override
    public String type() {
        return SessionEventTypes.REQUEST_CONTEXT;
    }
}
