package dev.duo.model.session;

/**
 * 不可变的存储元数据，保存在会话事件日志之外。
 * <p>
 * </p>
 *
 * @author zhangyl
 * @date 2026-08-18
 */
public record SessionHeader(
        int version,
        SessionId id,
        long createdAt,
        String cwd,
        SessionId parentSession,
        Integer seedLength,
        String origin,
        Integer delegationDepth,
        String agentPreset
) {
    public SessionHeader(int version, SessionId id) {
        this(version, id, System.currentTimeMillis(), null, null, null, null, null, null);
    }
}