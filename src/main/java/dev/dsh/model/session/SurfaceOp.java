package dev.dsh.model.session;

/**
 * 会话事件如何进入模型可见表面。
 * <p>
 * 对应 TS 源码中的 {@code SurfaceOp}。
 * </p>
 */
public sealed interface SurfaceOp {

    /** 追加到尾部——普通消息路径。 */
    record Append() implements SurfaceOp {}

    /** 替换一段范围内的表面节点。start 和 end 是节点 seq（含）。 */
    record Replace(int start, int end) implements SurfaceOp {}
}