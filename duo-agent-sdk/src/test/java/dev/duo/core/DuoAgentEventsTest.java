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
import dev.duo.model.session.SessionEvent;
import dev.duo.model.session.SessionEventAssistantChunk;
import dev.duo.model.session.SessionEventAssistantMessage;
import dev.duo.model.session.SessionEventToolCall;
import dev.duo.model.session.SessionEventToolResult;
import dev.duo.model.session.SessionEventTurnEnd;
import dev.duo.model.session.SessionEventTypes;
import dev.duo.model.session.SessionId;
import dev.duo.model.session.TurnEndReason;
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
 * {@link DuoAgentImpl#chatEvents(String)} 多事件流的测试。
 * <p>
 * 验证：完整事件序列、工具往返事件、思考过程可见（对比 stream 的过滤）、
 * sourceEventSeqs 回链、seq 单调递增、冷启动与单订阅约束。
 * </p>
 *
 * @author zhangyl
 * @date 2026-08-19
 */
class DuoAgentEventsTest {

    /** 工具桩名称。 */
    private static final String TOOL_GREP = "grep";
    private static final String CALL_ID_1 = "call-1";

    /** 异步收集事件的订阅者：request(Long.MAX_VALUE)，latch 标记流结束。 */
    private static final class EventsSubscriber implements Flow.Subscriber<SessionEvent> {

        final List<SessionEvent> received = new CopyOnWriteArrayList<>();
        final CountDownLatch finished = new CountDownLatch(1);
        final AtomicReference<Throwable> error = new AtomicReference<>();

        @Override
        public void onSubscribe(Flow.Subscription s) {
            s.request(Long.MAX_VALUE);
        }

        @Override
        public void onNext(SessionEvent event) {
            received.add(event);
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
                assertTrue(finished.await(10, TimeUnit.SECONDS), "事件流应在超时内到达终态");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                fail("等待事件流终态被中断");
            }
        }
    }

    /** 组装使用指定适配器的 DuoAgentImpl。 */
    private DuoAgentImpl newAgent(ScriptedStreamAdapter adapter, Session session) {
        var llm = new LlmRuntime();
        llm.registerAdapter("mock-scripted", adapter);
        var agent = new ReactLoopAgent(
                new SessionId("agent-events-test"),
                new AgentOptions("mock-scripted", "mock-model", null, Duration.ofSeconds(10)),
                session,
                llm,
                new SystemPromptImpl("", false),
                new ToolRegistryImpl()
        );
        return new DuoAgentImpl(agent, session);
    }

    private static List<String> typeNames(List<SessionEvent> events) {
        return events.stream().map(SessionEvent::type).toList();
    }

    @Test
    void shouldEmitCompleteEventSequenceForSimpleTurn() {
        var session = new Session(new SessionId("seq-test"));
        var agent = newAgent(new ScriptedStreamAdapter(
                ScriptedStreamAdapter.textReply("Hello")), session);

        var subscriber = new EventsSubscriber();
        agent.chatEvents("hi").subscribe(subscriber);
        subscriber.awaitEnd();

        assertNull(subscriber.error.get(), "正常对话不应出错");
        assertEquals(List.of(
                SessionEventTypes.TURN_START,
                SessionEventTypes.STEP_START,
                SessionEventTypes.USER_MESSAGE,
                SessionEventTypes.ASSISTANT_CHUNK,     // BlockStart/TextDelta/BlockEnd/Usage/Finish 若干条
                SessionEventTypes.ASSISTANT_MESSAGE,
                SessionEventTypes.STEP_END,
                SessionEventTypes.TURN_END
        ), dedupe(typeNames(subscriber.received)),
                "单 turn 事件序列应为：turn/start → step/start → user/message → chunk* → assistant/message → step/end → turn/end");

        // turn/end 原因为 Completed
        var turnEnd = (SessionEventTurnEnd) subscriber.received.getLast();
        assertInstanceOf(TurnEndReason.Completed.class, turnEnd.reason());
    }

    /** 相邻同类型事件折叠为一行（chunk 有多条，只验证边界序列）。 */
    private static List<String> dedupe(List<String> types) {
        var result = new ArrayList<String>();
        for (var type : types) {
            if (result.isEmpty() || !result.getLast().equals(type)) {
                result.add(type);
            }
        }
        return result;
    }

    @Test
    void shouldEmitToolCallAndResultEvents() {
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

        var session = new Session(new SessionId("tool-events-test"));
        var agent = new ReactLoopAgent(
                new SessionId("agent-tool-events"),
                new AgentOptions("mock-echo", "mock-model", null, Duration.ofSeconds(10)),
                session,
                llm,
                new SystemPromptImpl("", false),
                toolRegistry
        );
        var duo = new DuoAgentImpl(agent, session);

        var subscriber = new EventsSubscriber();
        duo.chatEvents("echo hi").subscribe(subscriber);
        subscriber.awaitEnd();

        var types = typeNames(subscriber.received);
        assertTrue(types.contains(SessionEventTypes.TOOL_CALL), "应收到 tool/call 事件");
        assertTrue(types.contains(SessionEventTypes.TOOL_RESULT), "应收到 tool/result 事件");
        // 时序：tool/call 在 tool/result 之前
        assertTrue(types.indexOf(SessionEventTypes.TOOL_CALL)
                        < types.indexOf(SessionEventTypes.TOOL_RESULT),
                "tool/call 应先于 tool/result");

        var call = subscriber.received.stream()
                .filter(e -> e instanceof SessionEventToolCall)
                .map(SessionEventToolCall.class::cast).findFirst().orElseThrow();
        assertEquals("echo", call.name(), "工具调用事件应携带工具名");
    }

    @Test
    void shouldExposeReasoningDeltasUnlikStream() {
        // 同一脚本：chatEvents 应收到 ReasoningDelta，stream() 应过滤掉
        var script = new ArrayList<StreamChunk>();
        script.add(new StreamChunk.BlockStart(0, SessionEventTypes.BLOCK_REASONING));
        script.add(new StreamChunk.ReasoningDelta(0, "thinking..."));
        script.add(new StreamChunk.BlockEnd(0, new ContentBlock.Reasoning("thinking...")));
        script.add(new StreamChunk.BlockStart(1, SessionEventTypes.BLOCK_TEXT));
        script.add(new StreamChunk.TextDelta(1, "Answer"));
        script.add(new StreamChunk.BlockEnd(1, new ContentBlock.Text("Answer")));
        script.add(new StreamChunk.Usage(new TokenUsage(5, 5)));
        script.add(new StreamChunk.Finish(new FinishReason.Stop()));

        var session = new Session(new SessionId("reasoning-test"));
        var agent = newAgent(new ScriptedStreamAdapter(script), session);

        var subscriber = new EventsSubscriber();
        agent.chatEvents("hi").subscribe(subscriber);
        subscriber.awaitEnd();

        var reasoningDeltas = subscriber.received.stream()
                .filter(e -> e instanceof SessionEventAssistantChunk c
                        && c.chunk() instanceof StreamChunk.ReasoningDelta)
                .toList();
        assertEquals(1, reasoningDeltas.size(),
                "chatEvents 应透传 ReasoningDelta（思考过程可见）");
    }

    @Test
    void shouldLinkAssistantMessageToChunkSeqs() {
        var session = new Session(new SessionId("link-test"));
        var agent = newAgent(new ScriptedStreamAdapter(
                ScriptedStreamAdapter.textReply("Hello", " ", "World")), session);

        var subscriber = new EventsSubscriber();
        agent.chatEvents("hi").subscribe(subscriber);
        subscriber.awaitEnd();

        var message = subscriber.received.stream()
                .filter(e -> e instanceof SessionEventAssistantMessage)
                .map(SessionEventAssistantMessage.class::cast).findFirst().orElseThrow();
        var chunkSeqs = subscriber.received.stream()
                .filter(e -> e instanceof SessionEventAssistantChunk)
                .map(SessionEvent::seq).toList();

        assertNotNull(message.sourceEventSeqs(), "assistant/message 应携带 sourceEventSeqs 回链");
        assertEquals(chunkSeqs, java.util.Arrays.stream(message.sourceEventSeqs()).boxed().toList(),
                "sourceEventSeqs 应精确指向本 step 的全部 chunk 事件 seq");
        assertEquals(7, message.sourceEventSeqs().length,
                "textReply(3 deltas) 脚本产生 7 个 chunk（block-start + delta×3 + block-end + usage + finish）");
    }

    @Test
    void shouldHaveMonotonicSeq() {
        var session = new Session(new SessionId("seq-monotonic-test"));
        var agent = newAgent(new ScriptedStreamAdapter(
                ScriptedStreamAdapter.textReply("Hello")), session);

        var subscriber = new EventsSubscriber();
        agent.chatEvents("hi").subscribe(subscriber);
        subscriber.awaitEnd();

        int lastSeq = -1;
        for (var event : subscriber.received) {
            assertTrue(event.seq() > lastSeq,
                    "seq 应严格单调递增，发现回退: " + lastSeq + " -> " + event.seq());
            lastSeq = event.seq();
        }
    }

    @Test
    void shouldBeColdAndSingleSubscription() {
        var session = new Session(new SessionId("cold-single-test"));
        var agent = newAgent(new ScriptedStreamAdapter(
                ScriptedStreamAdapter.textReply("Hello")), session);

        var publisher = agent.chatEvents("hi");
        assertEquals(0, session.events().size(), "未订阅时不应有任何对话活动");

        var first = new EventsSubscriber();
        publisher.subscribe(first);
        first.awaitEnd();
        assertFalse(first.received.isEmpty(), "订阅后应收到事件");

        var second = new EventsSubscriber();
        publisher.subscribe(second);
        second.awaitEnd();
        assertInstanceOf(IllegalStateException.class, second.error.get(),
                "重复订阅应走 onError(IllegalStateException)");
    }

    @Test
    void shouldRejectBlankMessageEagerly() {
        var session = new Session(new SessionId("blank-events-test"));
        var agent = newAgent(new ScriptedStreamAdapter(
                ScriptedStreamAdapter.textReply("ok")), session);

        assertThrows(IllegalArgumentException.class, () -> agent.chatEvents(" "));
        assertThrows(IllegalArgumentException.class, () -> agent.chatEvents(null));
    }

    @Test
    void shouldTerminateSlowConsumerOnBufferOverflow() {
        // 脚本产生远超缓冲上限（8192）的事件量
        var deltas = new ArrayList<String>();
        for (int i = 0; i < 8300; i++) {
            deltas.add("x");
        }
        var session = new Session(new SessionId("overflow-test"));
        var agent = newAgent(new ScriptedStreamAdapter(
                ScriptedStreamAdapter.textReply(deltas.toArray(new String[0]))), session);

        // 慢订阅者：仅 request(1)，之后不再消费
        var received = new CopyOnWriteArrayList<SessionEvent>();
        var finished = new CountDownLatch(1);
        var error = new AtomicReference<Throwable>();
        agent.chatEvents("hi").subscribe(new Flow.Subscriber<>() {
            @Override
            public void onSubscribe(Flow.Subscription s) {
                s.request(1);
            }

            @Override
            public void onNext(SessionEvent event) {
                received.add(event);
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
        });

        try {
            assertTrue(finished.await(15, TimeUnit.SECONDS), "溢出应触发 onError 终止");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            fail("等待溢出终止被中断");
        }

        assertNotNull(error.get(), "慢消费者应收到溢出错误");
        assertTrue(error.get().getMessage().contains("缓冲达到上限"),
                "错误消息应说明缓冲溢出，实际: " + error.get().getMessage());
        assertTrue(received.size() < 100,
                "慢订阅者只消费了 request 的量，实际: " + received.size());
    }
}
