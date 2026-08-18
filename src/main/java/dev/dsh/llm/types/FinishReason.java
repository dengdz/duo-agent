package dev.dsh.llm.types;

/**
 * 模型响应停止的原因。
 * <p>
 * 对应 TS 源码中的 {@code FinishReasonMap}。
 * </p>
 */
public sealed interface FinishReason {

    /** 正常完成。 */
    record Stop() implements FinishReason {}

    /** 模型请求了工具调用。 */
    record ToolCalls() implements FinishReason {}

    /** 达到最大 token 限制。 */
    record MaxTokens() implements FinishReason {}

    /** 请求被中止。 */
    record Aborted(LlmFailure failure) implements FinishReason {}

    /** 提供方或传输层错误。 */
    record Error(LlmFailure failure) implements FinishReason {}
}