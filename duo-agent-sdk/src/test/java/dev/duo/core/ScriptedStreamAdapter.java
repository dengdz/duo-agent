package dev.duo.core;

import dev.duo.api.llm.LlmAdapter;
import dev.duo.api.llm.StreamCallback;
import dev.duo.model.llm.FinishReason;
import dev.duo.model.llm.GenerateOptions;
import dev.duo.model.llm.StreamChunk;
import dev.duo.model.llm.TokenUsage;
import dev.duo.model.session.SessionEventTypes;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 按预设脚本回放 chunk 序列的 mock 适配器。
 * <p>
 * 用于 {@link DuoAgentImpl} 流式 API 的测试：<b>首次</b>调用回放脚本指定的
 * {@link StreamChunk} 列表（可选失败异常）；后续调用返回固定文本回复——
 * 避免脚本含工具调用时 agent 循环再次收到同样的 ToolCall 导致死循环
 * （模拟真实模型"工具往返后给出文本回答"的行为）。
 * 需要更复杂工具往返演练的场景请使用 {@link dev.duo.core.llm.MockEchoAdapter}。
 * </p>
 *
 * @author zhangyl
 * @date 2026-08-19
 */
public class ScriptedStreamAdapter extends LlmAdapter {

    private static final String SUBSEQUENT_REPLY = "done.";

    private final List<StreamChunk> script;
    private final RuntimeException failure;
    /** 首次调用回放脚本前的等待毫秒数（模拟推理模型思考耗时）。 */
    private final long delayMillis;
    private final AtomicBoolean firstCall = new AtomicBoolean(true);

    /**
     * 创建按脚本产出 chunk 的适配器。
     *
     * @param script 要依次回调的 chunk 列表
     */
    public ScriptedStreamAdapter(List<StreamChunk> script) {
        this(script, null, 0);
    }

    /**
     * 创建按脚本产出 chunk、随后以指定异常失败的适配器。
     *
     * @param script 要依次回调的 chunk 列表
     * @param failure 产出全部 chunk 后回调 onError 的异常；为 null 则正常结束
     */
    public ScriptedStreamAdapter(List<StreamChunk> script, RuntimeException failure) {
        this(script, failure, 0);
    }

    /**
     * 创建带延迟的适配器：首次调用先等待 {@code delayMillis} 毫秒再回放脚本
     * （模拟推理模型思考耗时，用于超时行为测试）。
     *
     * @param script 要依次回调的 chunk 列表
     * @param failure 回放后回调 onError 的异常；为 null 则正常结束
     * @param delayMillis 首次调用前的等待毫秒数
     */
    public ScriptedStreamAdapter(List<StreamChunk> script, RuntimeException failure, long delayMillis) {
        this.script = List.copyOf(script);
        this.failure = failure;
        this.delayMillis = delayMillis;
    }

    /** 便捷构造：单个文本块的完整 chunk 序列（block-start → text-delta → block-end → usage → finish-stop）。 */
    public static List<StreamChunk> textReply(String... deltas) {
        var script = new java.util.ArrayList<StreamChunk>();
        script.add(new StreamChunk.BlockStart(0, SessionEventTypes.BLOCK_TEXT));
        var full = new StringBuilder();
        for (var delta : deltas) {
            script.add(new StreamChunk.TextDelta(0, delta));
            full.append(delta);
        }
        script.add(new StreamChunk.BlockEnd(0, new dev.duo.model.llm.ContentBlock.Text(full.toString())));
        script.add(new StreamChunk.Usage(new TokenUsage(10, full.length())));
        script.add(new StreamChunk.Finish(new FinishReason.Stop()));
        return script;
    }

    @Override
    public void stream(GenerateOptions options, StreamCallback callback) {
        // 异步回放（对齐真实 DeepSeekAdapter 的 sendAsync 语义）：
        // 同步执行会让 ReactLoopAgent 的 barrier 在 stream() 返回后才等待，
        // 流早已完成、超时逻辑永不生效
        // 首次调用回放脚本；后续调用（如脚本含工具调用导致的下一 step）返回固定文本，
        // 防止无状态脚本在 agent 工具循环中被重复消费造成死循环
        var isFirstCall = firstCall.compareAndSet(true, false);
        Thread.ofVirtual().name("scripted-adapter").start(() -> {
            try {
                if (isFirstCall && delayMillis > 0) {
                    Thread.sleep(delayMillis);
                }
                var chunks = isFirstCall ? script : textReply(SUBSEQUENT_REPLY);
                for (var chunk : chunks) {
                    callback.onChunk(chunk);
                }
                if (isFirstCall && failure != null) {
                    callback.onError(failure);
                    return;
                }
                callback.onComplete();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                callback.onError(e);
            }
        });
    }
}
