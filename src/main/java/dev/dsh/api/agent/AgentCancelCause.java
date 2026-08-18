package dev.dsh.api.agent;

/**
 * 活跃 Agent 驱动被取消的原因。
 * <p>
 * 对应 TS 源码中的 {@code AgentCancelCause}。
 * </p>
 */
public sealed interface AgentCancelCause {

    /** 用户取消。 */
    record User() implements AgentCancelCause {}

    /** 父 Agent 取消。 */
    record Parent() implements AgentCancelCause {}

    /** 钩子取消。 */
    record Hook(String reason) implements AgentCancelCause {}

    /** Agent 被销毁。 */
    record Disposed() implements AgentCancelCause {}
}