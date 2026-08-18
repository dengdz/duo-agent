package dev.dsh.model.session;

import dev.dsh.model.llm.LlmFailure;

/**
 * 一轮对话为什么结束。
 * <p>
 * 对应 TS 源码中的 {@code TurnEndReasonMap}。
 * </p>
 */
public sealed interface TurnEndReason {

    /** 正常完成。 */
    record Completed() implements TurnEndReason {}

    /** 被取消请求中断。 */
    record Aborted(TurnEndCancelCause reason) implements TurnEndReason {}

    /** 被 pre-step 拒绝（无消息进入）。 */
    record Blocked() implements TurnEndReason {}

    /** 出错终止。 */
    record Error(LlmFailure failure) implements TurnEndReason {}

    /** 至少一个 step 达到最大 token 限制。 */
    record MaxTokens() implements TurnEndReason {}

    /** 持久化后端在重载时关闭了崩溃遗留的 turn。 */
    record Interrupted() implements TurnEndReason {}
}