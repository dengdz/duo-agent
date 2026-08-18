package dev.dsh.api.agent;

import dev.dsh.model.session.SessionId;

/**
 * 在持久化会话上恢复 agent 的选项。
 * <p>
 * 对应 TS 源码中的 {@code ResumeAgentOptions}。
 * </p>
 *
 * @author zhangyl
 * @date 2026-08-18
 */
public record ResumeAgentOptions(
        SessionId resumeSessionId,
        AgentOptions agentOptions
) {
}