package dev.duo.model.llm;

/**
 * 一次模型调用的 token 计数。
 * <p>
 * 计数是互斥的：{@code inputTokens} 仅是非缓存的输入；
 * 缓存输入分别报告为 {@code cacheReadTokens}/{@code cacheWriteTokens}
 * （计费输入 = 三者之和）。
 * </p>
 * <p>
 * 对应 TS 源码中的 {@code TokenUsage}。
 * </p>
 *
 * @author zhangyl
 * @date 2026-08-18
 */
public record TokenUsage(
        int inputTokens,
        int outputTokens,
        Integer cacheReadTokens,
        Integer cacheWriteTokens,
        Integer reasoningTokens
) {
    public TokenUsage(int inputTokens, int outputTokens) {
        this(inputTokens, outputTokens, null, null, null);
    }
}