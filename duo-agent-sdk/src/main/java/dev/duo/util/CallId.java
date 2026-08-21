package dev.duo.util;

/**
 * 提供方颁发的工具调用 ID 的不透明包装类型。
 *
 * @author zhangyl
 * @date 2026-08-18
 */
public record CallId(String value) {
    public CallId {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("CallId 不能为空");
        }
    }

    @Override
    public String toString() {
        return value;
    }
}