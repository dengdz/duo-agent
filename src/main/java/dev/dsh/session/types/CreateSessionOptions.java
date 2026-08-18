package dev.dsh.session.types;

/**
 * 创建会话时的选项。
 * <p>
 * 对应 TS 源码中的 {@code CreateSessionOptions}。
 * </p>
 */
public record CreateSessionOptions(
        /** 初始重放或 fork 历史。 */
        SessionEvent[] seed,
        /** 存储元数据。 */
        SessionMeta meta
) {
    public CreateSessionOptions {
        if (seed == null) seed = new SessionEvent[0];
    }

    public CreateSessionOptions() {
        this(new SessionEvent[0], null);
    }

    public record SessionMeta(
            String cwd,
            SessionId parentSession,
            Long createdAt,
            Integer seedLength,
            String origin,
            Integer delegationDepth,
            String agentPreset
    ) {
        public SessionMeta() {
            this(null, null, null, null, null, null, null);
        }
    }
}