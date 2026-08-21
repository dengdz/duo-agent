package dev.duo.api.llm;

import dev.duo.model.llm.GenerateOptions;

/**
 * 提供方线路适配器，用于 harness 消息和流词汇。
 * <p>
 * 通过 {@link LlmRuntime#registerAdapter(String, LlmAdapter)} 注册实现。
 * </p>
 * <p>
 * 对应 TS 源码中的 {@code LlmAdapter}。
 * </p>
 *
 * @author zhangyl
 * @date 2026-08-18
 */
public abstract class LlmAdapter {

    /**
     * 将一次模型调用作为原始 chunk 流式输出。唯一必须实现的方法。
     * @param options 完全组装好的请求
     * @param callback 用于接收 chunk 和终结事件的流回调
     */
    public abstract void stream(GenerateOptions options, StreamCallback callback);

    /**
     * 取消感知的流式调用：信号触发时断开 HTTP 连接（服务器停止生成、
     * 不再继续消耗 token）。
     * <p>
     * 默认实现忽略信号（旧适配器行为不变，取消时仅由调用方丢弃迟到 chunk）；
     * 长连接适配器应覆写本方法注册 {@link CancellationSignal#addListener}
     * 关闭响应体流。覆写实现的监听器应幂等。
     * </p>
     * @param options 完全组装好的请求
     * @param callback 用于接收 chunk 和终结事件的流回调
     * @param cancellation 调用方的取消信号
     */
    public void stream(GenerateOptions options, StreamCallback callback,
                       dev.duo.api.agent.CancellationSignal cancellation) {
        stream(options, callback);
    }
}