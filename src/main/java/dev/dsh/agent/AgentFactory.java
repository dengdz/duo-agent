package dev.dsh.agent;

import dev.dsh.exception.AgentCreationException;

/**
 * Agent 创建工厂——loop 实现通过 {@link AgentRegistry#setFactory(AgentFactory)} 向 registry 注册。
 * <p>
 * 对应 TS 源码中的 {@code AgentFactory} 接口。
 * </p>
 */
public interface AgentFactory {

    /**
     * 在调用者提供的会话 id 上创建新 Agent。
     * @param options 创建选项
     * @return 拥有的句柄
     * @throws AgentCreationException 创建失败时抛出
     */
    AgentHandle createAgent(CreateAgentOptions options) throws AgentCreationException;

    /**
     * 在持久化会话上恢复一个 Agent。
     * @param options 恢复选项
     * @return 拥有的句柄
     * @throws AgentCreationException 恢复失败时抛出
     */
    AgentHandle resume(ResumeAgentOptions options) throws AgentCreationException;
}