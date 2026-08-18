package dev.duo.api.agent;

/**
 * 两个有序的待处理消息列表之一，由 agent 拥有。
 * <p>
 * 对应 TS 源码中的 {@code InboxTarget}。
 * </p>
 *
 * @author zhangyl
 * @date 2026-08-18
 */
public enum InboxTarget {
    /** 等待单个轮次的提示。 */
    NEXT_TURN,
    /** 等待下一个 step 边界的输入。 */
    NEXT_STEP
}