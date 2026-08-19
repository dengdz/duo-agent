package dev.duo.core;

import dev.duo.api.agent.AgentOptions;
import dev.duo.api.llm.LlmRuntime;
import dev.duo.core.agent.ReactLoopAgent;
import dev.duo.core.llm.MockEchoAdapter;
import dev.duo.core.llm.SystemPromptImpl;
import dev.duo.core.llm.ToolRegistryImpl;
import dev.duo.core.session.Session;
import dev.duo.model.llm.ContentBlock;
import dev.duo.model.llm.FinishReason;
import dev.duo.model.llm.StreamChunk;
import dev.duo.model.llm.TokenUsage;
import dev.duo.model.session.SessionEventTypes;
import dev.duo.model.session.SessionId;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link DuoAgentImpl#stream(String)} 响应式流 API 的测试。
 * <p>
 * 验证：冷发布者语义、文本增量按序推送、ReasoningDelta/ToolCallDelta 过滤、
 * 背压（request 分批）、取消、错误走 onError、多 step 多段流、单订阅约束。
 * </p>
 *
 * @author zhangyl
 * @date 2026-08-19
 */
class DuoAgentStreamTest {

    /** 过滤测试中脚本发起的工具名与调用 id。 */
    private static final String TOOL_GREP = "grep";
    private static final String CALL_ID_1 = "call-1";

    /** 异步收集流事件的订阅者：记录 onNext 元素与终态，latch 标记流结束。 */
    private static final class CollectingSubscriber implements Flow.Subscriber<String> {

        final List<String> received = new CopyOnWriteArrayList<>();
        final CountDownLatch finished = new CountDownLatch(1);
        final AtomicReference<Throwable> error = new AtomicReference<>();
        /** 每次 onNext 后追加的 request 数量；默认首个元素后不再 request（无限模式传 MAX_VALUE）。 */
        private final long requestBatch;
        private Flow.Subscription subscription;
        private volatile boolean firstOnSubscribe = true;

        CollectingSubscriber(long requestBatch) {
            this.requestBatch = requestBatch;
        }

        static CollectingSubscriber unlimited() {
            return new CollectingSubscriber(Long.MAX_VALUE);
        }

        @Override
        public void onSubscribe(Flow.Subscription s) {
            this.subscription = s;
            s.request(requestBatch);
        }

        @Override
        public void onNext(String item) {
            received.add(item);
            if (requestBatch != Long.MAX_VALUE) {
                subscription.request(requestBatch);
            }
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

        /** 等待流终态，失败则抛断言错误。 */
        void awaitEnd() {
            try {
                assertTrue(finished.await(10, TimeUnit.SECONDS), "流应在超时内到达终态");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                fail("等待流终态被中断");
            }
        }
    }

    /** 组装一个使用指定适配器的 DuoAgentImpl（provider 名固定 mock-scripted）。 */
    private DuoAgentImpl newAgent(ScriptedStreamAdapter adapter, Session session) {
        var llm = new LlmRuntime();
        llm.registerAdapter("mock-scripted", adapter);
        var agent = new ReactLoopAgent(
                new SessionId("agent-stream-test"),
                new AgentOptions("mock-scripted", "mock-model", null, Duration.ofSeconds(10)),
                session,
                llm,
                new SystemPromptImpl("", false),
                new ToolRegistryImpl()
        );
        return new DuoAgentImpl(agent, session);
    }

    @Test
    void shouldStreamTextDeltasInOrderThenComplete() {
        var session = new Session(new SessionId("order-test"));
        var agent = newAgent(new ScriptedStreamAdapter(
                ScriptedStreamAdapter.textReply("Hello", " ", "World")), session);

        var subscriber = CollectingSubscriber.unlimited();
        agent.stream("hi").subscribe(subscriber);
        subscriber.awaitEnd();

        assertNull(subscriber.error.get(), "正常流不应出错");
        assertEquals(List.of("Hello", " ", "World"), subscriber.received,
                "文本增量应按序实时推送");
    }

    @Test
    void shouldBeColdUntilSubscribed() {
        var session = new Session(new SessionId("cold-test"));
        var agent = newAgent(new ScriptedStreamAdapter(
                ScriptedStreamAdapter.textReply("Hello")), session);

        // 未订阅：不发起对话，session 不产生任何事件
        var publisher = agent.stream("hi");
        assertEquals(0, session.events().size(), "未订阅时不应有任何对话活动");

        var subscriber = CollectingSubscriber.unlimited();
        publisher.subscribe(subscriber);
        subscriber.awaitEnd();

        assertEquals(List.of("Hello"), subscriber.received, "订阅后才应收到增量");
    }

    @Test
    void shouldFilterOutReasoningAndToolCallDeltas() {
        var script = new ArrayList<StreamChunk>();
        script.add(new StreamChunk.BlockStart(0, SessionEventTypes.BLOCK_REASONING));
        script.add(new StreamChunk.ReasoningDelta(0, "thinking about the answer..."));
        script.add(new StreamChunk.BlockEnd(0,
                new ContentBlock.Reasoning("thinking about the answer...")));
        script.add(new StreamChunk.BlockStart(1, SessionEventTypes.BLOCK_TEXT));
        script.add(new StreamChunk.TextDelta(1, "Answer"));
        script.add(new StreamChunk.BlockEnd(1, new ContentBlock.Text("Answer")));
        script.add(new StreamChunk.BlockStart(2, SessionEventTypes.BLOCK_TOOL_CALL));
        script.add(new StreamChunk.ToolCallDelta(
                2, new dev.duo.util.CallId(CALL_ID_1), TOOL_GREP, "{\"q\":\"x\"}"));
        script.add(new StreamChunk.BlockEnd(2, new ContentBlock.ToolCall(
                new dev.duo.util.CallId(CALL_ID_1), TOOL_GREP, "{\"q\":\"x\"}")));
        script.add(new StreamChunk.Usage(new TokenUsage(10, 10)));
        script.add(new StreamChunk.Finish(new FinishReason.ToolCalls()));

        var session = new Session(new SessionId("filter-test"));
        var agent = newAgent(new ScriptedStreamAdapter(script), session);

        var subscriber = CollectingSubscriber.unlimited();
        agent.stream("hi").subscribe(subscriber);
        subscriber.awaitEnd();

        assertTrue(subscriber.received.stream().noneMatch(s ->
                        s.contains("thinking") || s.contains("\"q\"")),
                "思考内容与工具参数碎片不得进入流");
        assertEquals("Answer", subscriber.received.getFirst(),
                "首个推送应是 TextDelta；ReasoningDelta/ToolCallDelta 被过滤");
    }

    @Test
    void shouldRespectBackpressureWithBatchedRequest() {
        var session = new Session(new SessionId("backpressure-test"));
        var agent = newAgent(new ScriptedStreamAdapter(
                ScriptedStreamAdapter.textReply("One", "Two", "Three")), session);

        // 每次只 request 1 个：元素必须逐个到达，不许多推
        var subscriber = new CollectingSubscriber(1);
        agent.stream("hi").subscribe(subscriber);
        subscriber.awaitEnd();

        assertEquals(List.of("One", "Two", "Three"), subscriber.received,
                "分批 request 下元素仍应按序全部到达");
    }

    @Test
    void shouldSignalErrorOnErrorScenario() {
        var failure = new IllegalStateException("mock LLM failure");
        var session = new Session(new SessionId("error-test"));
        var agent = newAgent(new ScriptedStreamAdapter(
                ScriptedStreamAdapter.textReply("partial"), failure), session);

        var subscriber = CollectingSubscriber.unlimited();
        agent.stream("hi").subscribe(subscriber);
        subscriber.awaitEnd();

        // 错误链路：LLM 失败 → whenIdle 吞掉并记 WARN → chat() 的消息计数检查
        // 抛出面向调用方的 IllegalStateException → 流走 onError
        assertNotNull(subscriber.error.get(), "失败场景应走 onError");
        assertInstanceOf(IllegalStateException.class, subscriber.error.get());
        assertTrue(subscriber.error.get().getMessage().contains("未生成新的响应"),
                "错误应携带可定位的失败说明，实际: " + subscriber.error.get().getMessage());
    }

    @Test
    void shouldStopPushingAfterCancel() throws Exception {
        var session = new Session(new SessionId("cancel-test"));
        var agent = newAgent(new ScriptedStreamAdapter(
                ScriptedStreamAdapter.textReply("Hello", " ", "World")), session);

        var received = new CopyOnWriteArrayList<String>();
        var subscribed = new CountDownLatch(1);
        var subscriber = new Flow.Subscriber<String>() {
            private Flow.Subscription subscription;

            @Override
            public void onSubscribe(Flow.Subscription s) {
                subscription = s;
                s.request(Long.MAX_VALUE);
                // 首个元素尚未到达即取消：之后不应再收到 onNext
                subscription.cancel();
                subscribed.countDown();
            }

            @Override
            public void onNext(String item) {
                received.add(item);
            }

            @Override
            public void onError(Throwable t) {
                // 取消后错误也不应到达（驱动线程吞掉）
            }

            @Override
            public void onComplete() {
                // 取消后完成信号不应到达
            }
        };

        agent.stream("hi").subscribe(subscriber);
        assertTrue(subscribed.await(5, TimeUnit.SECONDS));
        // 给驱动线程时间跑完对话轮（取消语义：停止推送，但对话继续执行完毕）
        Thread.sleep(300);

        assertTrue(received.isEmpty(), "cancel 后不应再收到任何 onNext");
    }

    @Test
    void shouldStreamMultipleSegmentsAcrossToolLoop() {
        // MockEchoAdapter：输入 "echo hi" 触发工具往返，
        // step1 流式输出 "Let me echo that for you."（含 ToolCallDelta），
        // step2 输出 "The echo tool has spoken."
        var llm = new LlmRuntime();
        llm.registerAdapter("mock-echo", new MockEchoAdapter());

        var toolRegistry = new ToolRegistryImpl();
        toolRegistry.register(new dev.duo.model.llm.ToolDefinition(
                "echo",
                "回显文本（测试用）",
                java.util.Map.of("type", "object",
                        "properties", java.util.Map.of("text", java.util.Map.of("type", "string"))),
                args -> new dev.duo.model.llm.ToolExecutionResult(
                        String.valueOf(args.getOrDefault("text", "")))
        ));

        var session = new Session(new SessionId("echo-stream-test"));
        var agent = new ReactLoopAgent(
                new SessionId("agent-echo-stream"),
                new AgentOptions("mock-echo", "mock-model", null, Duration.ofSeconds(10)),
                session,
                llm,
                new SystemPromptImpl("", false),
                toolRegistry
        );
        var duo = new DuoAgentImpl(agent, session);

        var subscriber = CollectingSubscriber.unlimited();
        duo.stream("echo hi").subscribe(subscriber);
        subscriber.awaitEnd();

        // 两段 step 的文本都应流式到达（工具调用 delta 被过滤）
        assertEquals(List.of("Let me echo that for you.", "The echo tool has spoken."),
                subscriber.received, "多 step 应产生多段连续文本流");
        assertNull(subscriber.error.get());
    }

    @Test
    void shouldRejectSecondSubscription() {
        var session = new Session(new SessionId("single-sub-test"));
        var agent = newAgent(new ScriptedStreamAdapter(
                ScriptedStreamAdapter.textReply("Hello")), session);

        var publisher = agent.stream("hi");

        var first = CollectingSubscriber.unlimited();
        publisher.subscribe(first);
        first.awaitEnd();
        assertEquals(List.of("Hello"), first.received);

        // 同一 Publisher 二次订阅：应收到 onError 而非再次驱动对话
        var second = CollectingSubscriber.unlimited();
        publisher.subscribe(second);
        second.awaitEnd();
        assertInstanceOf(IllegalStateException.class, second.error.get(),
                "重复订阅应走 onError(IllegalStateException)");
        assertEquals(List.of("Hello"), first.received, "首次订阅的结果不受影响");
    }

    @Test
    void shouldRejectBlankMessageEagerly() {
        var session = new Session(new SessionId("blank-test"));
        var agent = newAgent(new ScriptedStreamAdapter(
                ScriptedStreamAdapter.textReply("ok")), session);

        assertThrows(IllegalArgumentException.class, () -> agent.stream(" "));
        assertThrows(IllegalArgumentException.class, () -> agent.stream(null));
    }
}
