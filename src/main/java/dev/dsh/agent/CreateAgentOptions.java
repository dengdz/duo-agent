package dev.dsh.agent;

import dev.dsh.session.types.SessionEvent;
import dev.dsh.session.types.SessionId;

/**
 * 通过 registry 以编程方式创建 agent 的选项。
 * <p>
 * 对应 TS 源码中的 {@code CreateAgentOptions}。
 * </p>
 */
public record CreateAgentOptions(
        SessionId sessionId,
        Meta meta,
        SessionEvent[] seed,
        AgentOptions agentOptions
) {
    public record Meta(
            String cwd,
            SessionId parentSession,
            Integer seedLength,
            String origin,
            Integer delegationDepth,
            String agentPreset
    ) {
        public Meta() {
            this(null, null, null, null, null, null);
        }
    }
}