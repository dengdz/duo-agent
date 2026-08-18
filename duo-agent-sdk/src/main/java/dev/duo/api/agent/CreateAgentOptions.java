package dev.duo.api.agent;

import dev.duo.model.session.SessionEvent;
import dev.duo.model.session.SessionId;

/**
 * 通过 registry 以编程方式创建 agent 的选项。
 * <p>
 * 对应 TS 源码中的 {@code CreateAgentOptions}。
 * </p>
 *
 * @author zhangyl
 * @date 2026-08-18
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