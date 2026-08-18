package dev.dsh.agent;

import dev.dsh.session.types.SessionId;

/**
 * 在持久化会话上恢复 agent 的选项。
 * <p>
 * 对应 TS 源码中的 {@code ResumeAgentOptions}。
 * </p>
 */
public record ResumeAgentOptions(
        SessionId resumeSessionId,
        AgentOptions agentOptions
) {
}