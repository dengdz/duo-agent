package dev.duo.core.compaction;

import dev.duo.api.agent.AgentHooks;
import dev.duo.api.agent.AgentOptions;
import dev.duo.api.llm.LlmRuntime;
import dev.duo.api.llm.StreamCallback;
import dev.duo.core.agent.ReactLoopAgent;
import dev.duo.core.llm.MockEchoAdapter;
import dev.duo.core.llm.SystemPromptImpl;
import dev.duo.core.llm.ToolRegistryImpl;
import dev.duo.core.session.Session;
import dev.duo.model.llm.ContentBlock;
import dev.duo.model.llm.FinishReason;
import dev.duo.model.llm.GenerateOptions;
import dev.duo.model.llm.Message;
import dev.duo.model.llm.MessageFactory;
import dev.duo.model.llm.MessageSource;
import dev.duo.model.llm.StreamChunk;
import dev.duo.model.session.SessionEventCompactionEnd;
import dev.duo.model.session.SessionEventCompactionStart;
import dev.duo.model.session.SessionEventUserMessage;
import dev.duo.model.session.SessionId;
import dev.duo.model.session.SessionEventTypes;
import dev.duo.model.session.TurnEndReason;
import dev.duo.model.session.SessionEventTurnEnd;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link CompactionHook} 的端到端测试：压力触发 → 摘要 → 表面替换 → 对话继续。
 *
 * @author zhangyl
 * @date 2026-08-19
 */
class CompactionHookTest {

    /** 摘要路由也走 mock：识别压缩指令返回固定摘要，其余委托回显。 */
    private static class CompactionTestAdapter extends MockEchoAdapter {

        private boolean summarizeRequestSeen;

        @Override
        public void stream(GenerateOptions options, StreamCallback callback) {
            if (lastUserText(options).contains("压缩为一份摘要")) {
                summarizeRequestSeen = true;
                emit("用户想测试压缩功能，已完成三轮简短对话。", callback);
                return;
            }
            super.stream(options, callback);
        }
    }

    /** 摘要恒定失败的适配器：验证压缩失败不阻塞对话。 */
    private static final class FailingSummarizeAdapter extends CompactionTestAdapter {

        @Override
        public void stream(GenerateOptions options, StreamCallback callback) {
            if (lastUserText(options).contains("压缩为一份摘要")) {
                callback.onError(new IllegalStateException("摘要服务不可用"));
                return;
            }
            super.stream(options, callback);
        }
    }

    /** 提取请求中最后一条 user 消息的文本（摘要指令探测）。 */
    private static String lastUserText(dev.duo.model.llm.GenerateOptions options) {
        for (int i = options.messages().size() - 1; i >= 0; i--) {
            if (options.messages().get(i) instanceof Message.UserMessage userMsg) {
                var block = userMsg.content().isEmpty() ? null : userMsg.content().getFirst();
                if (block instanceof ContentBlock.Text text) {
                    return text.text();
                }
                return "";
            }
        }
        return "";
    }

    private static void emit(String text, StreamCallback callback) {
        callback.onChunk(new StreamChunk.BlockStart(0, SessionEventTypes.BLOCK_TEXT));
        callback.onChunk(new StreamChunk.TextDelta(0, text));
        callback.onChunk(new StreamChunk.BlockEnd(0, new ContentBlock.Text(text)));
        callback.onChunk(new StreamChunk.Finish(new FinishReason.Stop()));
        callback.onComplete();
    }

    private static ReactLoopAgent newAgent(Session session, LlmRuntime llm, CompactionConfig config) {
        var hooks = AgentHooks.builder()
                .addPreStepHook(new CompactionHook(
                        llm, new SystemPromptImpl("", false), "mock", "mock-model", config))
                .build();
        return new ReactLoopAgent(
                session.id(),
                new AgentOptions("mock", "mock-model", null, null, hooks),
                session, llm, new SystemPromptImpl("", false), new ToolRegistryImpl());
    }

    @Test
    void pressureTriggerCompactsSurfaceAndConversationContinues() throws Exception {
        var llm = new LlmRuntime();
        var adapter = new CompactionTestAdapter();
        llm.registerAdapter("mock", adapter);
        var session = new Session(new SessionId("compaction-e2e"));
        // 低阈值强制触发；保留很小的尾部
        var agent = newAgent(session, llm, new CompactionConfig(40, 12, 2));

        for (int i = 1; i <= 3; i++) {
            agent.followup(MessageFactory.createUserMessage(
                    List.of(new ContentBlock.Text("第 " + i + " 轮的消息内容，稍微长一点点")),
                    new MessageSource.User()));
            agent.whenIdle();
        }

        // 摘要请求确实发出
        assertTrue(adapter.summarizeRequestSeen, "超阈值时应发起摘要调用");
        // 事务事件成对且成功
        var events = session.events();
        assertEquals(1, events.stream().filter(e -> e instanceof SessionEventCompactionStart).count());
        var end = events.stream()
                .filter(e -> e instanceof SessionEventCompactionEnd)
                .map(e -> (SessionEventCompactionEnd) e)
                .findFirst().orElseThrow();
        org.junit.jupiter.api.Assertions.assertNull(end.error(), "成功压缩的 end 不带 error");
        // 表面已替换：首条派生消息是摘要 checkpoint
        var derived = session.deriveMessages();
        var first = (ContentBlock.Text) ((Message.UserMessage) derived.getFirst()).content().getFirst();
        assertTrue(first.text().startsWith("[对话摘要]"), "表面首条应为摘要 checkpoint");
        assertTrue(derived.size() < 6, "表面消息应从 6 条（3 轮×2）减少");
        // 原消息仍完整保留在日志中（回放保真）
        assertTrue(events.stream().filter(e -> e instanceof SessionEventUserMessage).count() >= 3,
                "原始事件不因表面替换而消失");
        // 对话正常收尾
        var last = (SessionEventTurnEnd) events.getLast();
        assertInstanceOf(TurnEndReason.Completed.class, last.reason());
    }

    @Test
    void compactionFailureDoesNotBlockTurn() throws Exception {
        var llm = new LlmRuntime();
        llm.registerAdapter("mock", new FailingSummarizeAdapter());
        var session = new Session(new SessionId("compaction-fail"));
        var agent = newAgent(session, llm, new CompactionConfig(40, 12, 1));

        for (int i = 1; i <= 3; i++) {
            agent.followup(MessageFactory.createUserMessage(
                    List.of(new ContentBlock.Text("第 " + i + " 轮的消息内容，稍微长一点点")),
                    new MessageSource.User()));
            agent.whenIdle();
        }

        // 压缩失败也要落成对的 end（带 error），且 turn 不被阻塞
        var end = session.events().stream()
                .filter(e -> e instanceof SessionEventCompactionEnd)
                .map(e -> (SessionEventCompactionEnd) e)
                .findFirst().orElseThrow();
        assertTrue(end.error() != null && !end.error().isBlank(), "失败路径的 end 应携带 error");
        var last = (SessionEventTurnEnd) session.events().getLast();
        assertInstanceOf(TurnEndReason.Completed.class, last.reason(),
                "压缩失败不得阻塞对话（压缩是优化，不是依赖）");
        // 表面未被破坏：仍是普通消息（无摘要 checkpoint）
        var first = (ContentBlock.Text) ((Message.UserMessage) session.deriveMessages().getFirst())
                .content().getFirst();
        assertTrue(first.text().startsWith("第 1 轮"), "失败路径不得改动表面");
    }

    @Test
    void belowThresholdDoesNothing() throws Exception {
        var llm = new LlmRuntime();
        var adapter = new CompactionTestAdapter();
        llm.registerAdapter("mock", adapter);
        var session = new Session(new SessionId("compaction-idle"));
        var agent = newAgent(session, llm, new CompactionConfig(100000, 100, 2));

        agent.followup(MessageFactory.createUserMessage(
                List.of(new ContentBlock.Text("很短")), new MessageSource.User()));
        agent.whenIdle();

        assertEquals(0, session.events().stream()
                .filter(e -> e instanceof SessionEventCompactionStart).count(),
                "低于阈值不触发压缩");
        assertTrue(!adapter.summarizeRequestSeen, "不应发起摘要调用");
    }
}
