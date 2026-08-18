package dev.dsh.model.session;

/**
 * 持久化的取消原因，包含导入时未携带原始原因的粗粒度记录。
 * 对应 TS 源码中的 {@code TurnEndCancelCause}。
 *
 * @author zhangyl
 * @date 2026-08-18
 */
public sealed interface TurnEndCancelCause {

    /** 用户取消。 */
    record User() implements TurnEndCancelCause {}

    /** 父 agent 取消。 */
    record Parent() implements TurnEndCancelCause {}

    /** 钩子取消。 */
    record Hook(String reason) implements TurnEndCancelCause {}

    /** agent 被销毁。 */
    record Disposed() implements TurnEndCancelCause {}

    /** 从旧格式导入，无原始原因。 */
    record Legacy() implements TurnEndCancelCause {}
}