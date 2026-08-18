package dev.duo.api.llm;

import dev.duo.model.llm.StreamChunk;

/**
 * 接收流式模型响应的回调接口。
 * <p>
 * 对应 TS 源码中消费 {@code AsyncIterable<StreamChunk>} 的行为。
 * </p>
 *
 * @author zhangyl
 * @date 2026-08-18
 */
public interface StreamCallback {
    /** 流中每个 chunk 到达时调用。 */
    void onChunk(StreamChunk chunk);

    /** 流成功完成时调用。 */
    void onComplete();

    /** 流出错时调用。 */
    void onError(Throwable error);
}