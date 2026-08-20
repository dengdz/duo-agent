package dev.duo.core.model;

import dev.duo.api.llm.LlmAdapter;
import dev.duo.api.llm.StreamCallback;
import dev.duo.core.ScriptedStreamAdapter;
import dev.duo.model.llm.FinishReason;
import dev.duo.model.llm.GenerateOptions;
import dev.duo.model.llm.StreamChunk;
import dev.duo.model.llm.TokenUsage;
import dev.duo.model.session.SessionEventTypes;
import dev.duo.model.llm.ContentBlock;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link AbstractDuoModel} 的 call/stream 语义与适配器工厂行为测试。
 * <p>
 * 验证：call 拼接文本增量且跳过 ReasoningDelta、失败与空文本报错、
 * prompt 空值快速失败；stream 冷发布、chunk 顺序透传、失败走 onError、
 * 单订阅约束；createAdapter 工厂复用与隔离语义。
 * </p>
 *
 * @author zhangyl
 * @date 2026-08-20
 */
class AbstractDuoModelTest {

    /** 记录调用次数的脚本适配器：验证冷发布（订阅前零调用）。 */
    private static final class CountingScriptedAdapter extends ScriptedStreamAdapter {

        final AtomicInteger calls = new AtomicInteger();

        CountingScriptedAdapter(List<StreamChunk> script) {
            super(script);
        }

        @Override
        public void stream(GenerateOptions options, StreamCallback callback) {
            calls.incrementAndGet();
            super.stream(options, callback);
        }
    }

    /** 异步收集 chunk 的订阅者：request(Long.MAX_VALUE)，latch 标记流结束。 */
    private static final class ChunkSubscriber implements Flow.Subscriber<StreamChunk> {

        final List<StreamChunk> received = new CopyOnWriteArrayList<>();
        final CountDownLatch finished = new CountDownLatch(1);
        final AtomicReference<Throwable> error = new AtomicReference<>();

        @Override
        public void onSubscribe(Flow.Subscription s) {
            s.request(Long.MAX_VALUE);
        }

        @Override
        public void onNext(StreamChunk chunk) {
            received.add(chunk);
        }

        @Override
        public void onError(Throwable t) {
            error.set(t);
            finished.countDown();
        }

        @Override
        public void onComplete() {
            finished.countDown();
        }

        void awaitEnd() {
            try {
                assertTrue(finished.await(10, TimeUnit.SECONDS), "chunk 流应在超时内到达终态");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                fail("等待 chunk 流终态被中断");
            }
        }
    }

    /** 带思考过程的脚本：BlockStart → Reasoning → Text → Usage → Finish。 */
    private static List<StreamChunk> reasoningThenAnswerScript() {
        var script = new ArrayList<StreamChunk>();
        script.add(new StreamChunk.BlockStart(0, SessionEventTypes.BLOCK_REASONING));
        script.add(new StreamChunk.ReasoningDelta(0, "thinking..."));
        script.add(new StreamChunk.BlockEnd(0, new ContentBlock.Reasoning("thinking...")));
        script.add(new StreamChunk.BlockStart(1, SessionEventTypes.BLOCK_TEXT));
        script.add(new StreamChunk.TextDelta(1, "Hello"));
        script.add(new StreamChunk.TextDelta(1, " World"));
        script.add(new StreamChunk.BlockEnd(1, new ContentBlock.Text("Hello World")));
        script.add(new StreamChunk.Usage(new TokenUsage(5, 5)));
        script.add(new StreamChunk.Finish(new FinishReason.Stop()));
        return script;
    }

    @Test
    void callShouldJoinTextDeltasAndSkipReasoning() {
        var model = new ScriptedDuoModel(new ScriptedStreamAdapter(reasoningThenAnswerScript()));

        assertEquals("Hello World", model.call("hi"),
                "call 应拼接 TextDelta 且不含思考内容");
    }

    @Test
    void callShouldFailOnAdapterError() {
        var model = new ScriptedDuoModel(new ScriptedStreamAdapter(
                ScriptedStreamAdapter.textReply("partial"),
                new IllegalStateException("mock LLM failure")));

        var thrown = assertThrows(RuntimeException.class, () -> model.call("hi"));
        assertEquals("mock LLM failure", thrown.getCause().getMessage(),
                "底层失败应作为原因链传播");
    }

    @Test
    void callShouldRejectBlankPrompt() {
        var model = new ScriptedDuoModel(new ScriptedStreamAdapter(
                ScriptedStreamAdapter.textReply("ok")));

        assertThrows(IllegalArgumentException.class, () -> model.call(" "));
        assertThrows(IllegalArgumentException.class, () -> model.call(null));
    }

    @Test
    void callShouldFailWhenResponseHasNoText() {
        // 纯思考脚本：无 TextDelta，call 无法产出文本
        var script = new ArrayList<StreamChunk>();
        script.add(new StreamChunk.BlockStart(0, SessionEventTypes.BLOCK_REASONING));
        script.add(new StreamChunk.ReasoningDelta(0, "only thinking"));
        script.add(new StreamChunk.BlockEnd(0, new ContentBlock.Reasoning("only thinking")));
        script.add(new StreamChunk.Finish(new FinishReason.Stop()));

        var model = new ScriptedDuoModel(new ScriptedStreamAdapter(script));

        assertThrows(IllegalStateException.class, () -> model.call("hi"),
                "纯推理响应应明确报错而非返回空串");
    }

    @Test
    void streamShouldBeColdUntilSubscribed() {
        var adapter = new CountingScriptedAdapter(
                ScriptedStreamAdapter.textReply("Hello"));
        var model = new ScriptedDuoModel(adapter);

        var publisher = model.stream("hi");
        assertEquals(0, adapter.calls.get(), "未订阅时不应发起任何模型调用");

        var subscriber = new ChunkSubscriber();
        publisher.subscribe(subscriber);
        subscriber.awaitEnd();

        assertEquals(1, adapter.calls.get(), "订阅后才应发起模型调用");
    }

    @Test
    void streamShouldPassThroughAllChunkTypesInOrder() {
        var model = new ScriptedDuoModel(new ScriptedStreamAdapter(reasoningThenAnswerScript()));

        var subscriber = new ChunkSubscriber();
        model.stream("hi").subscribe(subscriber);
        subscriber.awaitEnd();

        assertNull(subscriber.error.get(), "正常流不应出错");
        // 9 个 chunk 全量透传：BlockStart×2 + ReasoningDelta + BlockEnd×2 + TextDelta×2 + Usage + Finish
        assertEquals(9, subscriber.received.size(), "原生 chunk 应全量按序透传");
        assertInstanceOf(StreamChunk.ReasoningDelta.class, subscriber.received.get(1),
                "ReasoningDelta 应可见（Model 层不做事件包装与过滤）");
        assertInstanceOf(StreamChunk.Usage.class, subscriber.received.get(7),
                "Usage 应透传");
        assertInstanceOf(StreamChunk.Finish.class, subscriber.received.get(8),
                "Finish 应透传");
    }

    @Test
    void streamShouldSignalErrorOnErrorScenario() {
        var model = new ScriptedDuoModel(new ScriptedStreamAdapter(
                ScriptedStreamAdapter.textReply("partial"),
                new IllegalStateException("mock LLM failure")));

        var subscriber = new ChunkSubscriber();
        model.stream("hi").subscribe(subscriber);
        subscriber.awaitEnd();

        assertInstanceOf(IllegalStateException.class, subscriber.error.get(),
                "底层失败应走 onError");
    }

    @Test
    void streamShouldRejectSecondSubscription() {
        var model = new ScriptedDuoModel(new ScriptedStreamAdapter(
                ScriptedStreamAdapter.textReply("Hello")));

        var publisher = model.stream("hi");

        var first = new ChunkSubscriber();
        publisher.subscribe(first);
        first.awaitEnd();
        assertFalse(first.received.isEmpty(), "首次订阅应收到 chunk");

        var second = new ChunkSubscriber();
        publisher.subscribe(second);
        second.awaitEnd();
        assertInstanceOf(IllegalStateException.class, second.error.get(),
                "重复订阅应走 onError(IllegalStateException)");
    }

    @Test
    void streamShouldRejectBlankPrompt() {
        var model = new ScriptedDuoModel(new ScriptedStreamAdapter(
                ScriptedStreamAdapter.textReply("ok")));

        assertThrows(IllegalArgumentException.class, () -> model.stream(" "));
        assertThrows(IllegalArgumentException.class, () -> model.stream(null));
    }

    @Test
    void createAdapterShouldReuseInstanceForSelfUse() {
        var model = new ScriptedDuoModel(new ScriptedStreamAdapter(
                ScriptedStreamAdapter.textReply("ok")));

        // 无参工厂（Model 自用）：同一实例复用，避免 HttpClient 连接池重建
        assertSame(model.createAdapter(), model.createAdapter(),
                "无参工厂应复用同一适配器实例");
    }

    @Test
    void createAdapterWithDurationShouldReturnFreshAdapter() {
        // 用可区分实例的桩验证：带参工厂（Agent 组装）每次返回新实例
        var model = new ScriptedDuoModel(timeout -> new DistinctAdapter(), null, false, Duration.ofMinutes(5));
        assertNotSame(model.createAdapter(Duration.ofMinutes(2)),
                        model.createAdapter(Duration.ofMinutes(2)),
                "带参工厂应为每次组装创建独立适配器");
        assertNotSame(model.createAdapter(Duration.ofMinutes(2)), model.createAdapter(),
                "带参与无参工厂的实例应彼此独立");
    }

    /** 每次构造返回可区分实例的适配器桩。 */
    private static final class DistinctAdapter extends LlmAdapter {

        @Override
        public void stream(GenerateOptions options, StreamCallback callback) {
            callback.onError(new IllegalStateException("不应被调用"));
        }
    }
}
