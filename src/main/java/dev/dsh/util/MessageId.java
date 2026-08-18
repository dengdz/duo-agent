package dev.dsh.util;

import java.util.UUID;

/**
 * 消息的不透明稳定标识。
 * 对应 TS 源码中的 {@code Branded<'MessageId'>}。
 *
 * @author zhangyl
 * @date 2026-08-18
 */
public record MessageId(String value) {
    public MessageId {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("MessageId 不能为空");
        }
    }

    /** 创建新的随机消息标识。 */
    public static MessageId random() {
        return new MessageId(UUID.randomUUID().toString());
    }

    @Override
    public String toString() {
        return value;
    }
}