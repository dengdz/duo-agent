package dev.dsh.model.session;

/**
 * 会话标识的不透明包装类型。
 * 对应 TS 源码中的 {@code Branded<'SessionId'>}。
 *
 * @author zhangyl
 * @date 2026-08-18
 */
public record SessionId(String value) {
    public SessionId {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("SessionId 不能为空");
        }
    }

    @Override
    public String toString() {
        return value;
    }
}