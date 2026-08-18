package dev.dsh.model.llm;

/**
 * 可序列化的提供方或传输层故障信息。
 * <p>
 * 对应 TS 源码中的 {@code LlmFailure}。
 * </p>
 *
 * @author zhangyl
 * @date 2026-08-18
 */
public record LlmFailure(
        String message,
        String code,
        Integer status,
        Long providerRetryAfterMs,
        String requestId
) {
    public LlmFailure(String message, String code) {
        this(message, code, null, null, null);
    }

    public LlmFailure {
        if (message == null || message.isBlank()) {
            throw new IllegalArgumentException("message 不能为空");
        }
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("code 不能为空");
        }
    }
}