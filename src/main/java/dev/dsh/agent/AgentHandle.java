package dev.dsh.agent;

/**
 * 拥有的 Agent 加上其处置器，由 {@link AgentRegistry#create(CreateAgentOptions)} 返回。
 * <p>
 * 对应 TS 源码中的 {@code AgentHandle}。
 * </p>
 */
public record AgentHandle(
        Agent agent,
        AutoCloseable disposer
) {
    /** 处置此 Agent：停止循环、取消注册、移除会话。 */
    public void dispose() throws Exception {
        disposer.close();
    }
}