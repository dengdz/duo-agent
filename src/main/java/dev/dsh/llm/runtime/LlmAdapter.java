package dev.dsh.llm.runtime;

import dev.dsh.llm.types.GenerateOptions;

/**
 * 提供方线路适配器，用于 harness 消息和流词汇。
 * <p>
 * 通过 {@link LlmRuntime#registerAdapter(String, LlmAdapter)} 注册实现。
 * </p>
 * <p>
 * 对应 TS 源码中的 {@code LlmAdapter}。
 * </p>
 */
public abstract class LlmAdapter {

    /**
     * 将一次模型调用作为原始 chunk 流式输出。唯一必须实现的方法。
     * @param options 完全组装好的请求
     * @param callback 用于接收 chunk 和终结事件的流回调
     */
    public abstract void stream(GenerateOptions options, StreamCallback callback);
}