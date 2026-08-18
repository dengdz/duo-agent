package dev.dsh.api.agent;

import dev.dsh.core.agent.Inbox;
import dev.dsh.model.llm.Message;
import dev.dsh.core.session.Session;
import dev.dsh.model.session.SessionId;

/**
 * 公开的活跃 Agent 句柄。
 * <p>
 * 对应 TS 源码中的 {@code Agent} 接口。
 * </p>
 */
public interface Agent {

    /** 与 {@code session} 共享的唯一标识。 */
    SessionId id();

    /** 此 Agent 请求使用的提供方路由和模型。 */
    AgentOptions options();

    /** 此 Agent 驱动的活跃会话；其日志是唯一的事实源。 */
    Session session();

    /** Agent 拥有的耐久待处理工作投影。 */
    Inbox inbox();

    /** 当前生命周期状态。 */
    AgentStatus status();

    /**
     * 清除排队和 steering 的工作——除非 {@code keepInbox}——并中止活跃的 turn。
     * @param cause 取消原因
     * @param options 取消选项
     */
    void cancel(AgentCancelCause cause, CancelOptions options);

    /** 使用默认选项取消。 */
    default void cancel(AgentCancelCause cause) {
        cancel(cause, new CancelOptions());
    }

    /**
     * 等待当前整个 Agent 活动达到静止。
     */
    void whenIdle() throws InterruptedException;

    /**
     * 将标识的输入路由到 inbox 边界，并可选择唤醒驱动。
     * @param message 标识的内容和提供它的来源
     * @param target 首选的 next-turn 或 next-step inbox 边界
     * @param wakeup 是否可能唤醒驱动
     */
    void send(Message.UserMessage message, InboxTarget target, boolean wakeup);

    /**
     * 排队一个普通的后续轮次并唤醒驱动。
     */
    void followup(Message.UserMessage message);

    /**
     * 为最近的 step 提交 steering。
     * 空闲的驱动会启动一个 turn；正在运行的驱动会在下一个 step 边界消费它。
     */
    void steer(Message.UserMessage message);

    /**
     * 为下一个 pre-step 排队模型可见上下文，但不唤醒驱动。
     */
    void inject(Message.UserMessage message);
}