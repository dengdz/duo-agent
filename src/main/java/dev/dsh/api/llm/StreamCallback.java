package dev.dsh.api.llm;

import dev.dsh.model.llm.StreamChunk;

/**
 * 接收流式模型响应的回调接口。
 * <p>
 * 对应 TS 源码中消费 {@code AsyncIterable<StreamChunk>} 的行为。
 * </p>
 */
public interface StreamCallback {
    /** 流中每个 chunk 到达时调用。 */
    void onChunk(StreamChunk chunk);

    /** 流成功完成时调用。 */
    void onComplete();

    /** 流出错时调用。 */
    void onError(Throwable error);
}