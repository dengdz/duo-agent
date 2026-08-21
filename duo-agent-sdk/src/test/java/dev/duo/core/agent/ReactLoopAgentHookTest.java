package dev.duo.core.agent;

import dev.duo.api.agent.AgentHooks;
import dev.duo.api.agent.AgentOptions;
import dev.duo.api.agent.PreStepDecision;
import dev.duo.api.agent.RequestErrorAction;
import dev.duo.api.llm.LlmRuntime;
import dev.duo.api.llm.StreamCallback;
import dev.duo.core.llm.LlmRetryHook;
import dev.duo.core.llm.MockEchoAdapter;
import dev.duo.core.llm.SystemPromptImpl;
import dev.duo.core.llm.ToolRegistryImpl;
import dev.duo.core.session.Session;
import dev.duo.exception.LlmException;
import dev.duo.model.llm.ContentBlock;
import dev.duo.model.llm.GenerateOptions;
import dev.duo.model.llm.Message;
import dev.duo.model.llm.MessageFactory;
import dev.duo.model.llm.MessageSource;
import dev.duo.model.llm.StreamChunk;
import dev.duo.model.llm.ToolDefinition;
import dev.duo.model.llm.ToolExecutionResult;
import dev.duo.model.llm.ToolExecutor;
import dev.duo.model.session.SessionEventAssistantChunk;
import dev.duo.model.session.SessionEventStepStart;
import dev.duo.model.session.SessionEventToolResult;
import dev.duo.model.session.SessionEventTurnEnd;
import dev.duo.model.session.SessionEventUserMessage;
import dev.duo.model.session.SessionId;
import dev.duo.model.session.TurnEndReason;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ReactLoopAgent} 四个 hook 分发点的端到端测试。
 * <p>
 * 验证：pre-step 拒绝/改写、request 改写、tool 短路、request-error 重试恢复。
 * </p>
 *
 * @author zhangyl
 * @date 2026-08-18
 */
class ReactLoopAgentHookTest {

    private static Message.UserMessage userMessage(String text) {
        return MessageFactory.createUserMessage(
                List.of(new ContentBlock.Text(text)), new MessageSource.User());
    }

    /** 前 N 次调用报 503，之后委托 mock 回显。 */
    private static final class FlakyAdapter extends MockEchoAdapter {

        private final AtomicInteger calls = new AtomicInteger();
        private final int failures;

        FlakyAdapter(int failures) {
            this.failures = failures;
        }

        @Override
        public void stream(GenerateOptions options, StreamCallback callback) {
            if (calls.incrementAndGet() <= failures) {
                callback.onError(new LlmException("模拟服务端错误", 503));
                return;
            }
            super.stream(options, callback);
        }
    }

    private ReactLoopAgent newAgent(Session session, AgentOptions options) {
        var llm = new LlmRuntime();
        llm.registerAdapter("mock-echo", new MockEchoAdapter());
        llm.registerAdapter("flaky-echo", new FlakyAdapter(2));
        return new ReactLoopAgent(
                new SessionId(options.provider() + "-agent"), options, session,
                llm, new SystemPromptImpl("", false), new ToolRegistryImpl());
    }

    @Test
    void preStepRejectEndsTurnAsBlockedWithoutStep() throws Exception {
        var session = new Session(new SessionId("reject-test"));
        var hooks = AgentHooks.builder()
                .addPreStepHook((ctx, next) -> new PreStepDecision.Reject())
                .build();
        var agent = newAgent(session,
                new AgentOptions("mock-echo", "mock-model", null, null, hooks));

        agent.followup(userMessage("hello"));
        agent.whenIdle();

        var events = session.events();
        var stepStarts = events.stream().filter(e -> e instanceof SessionEventStepStart).count();
        assertEquals(0, stepStarts, "被拒的 turn 不应产生 step/start");

        var turnEnd = (SessionEventTurnEnd) events.getLast();
        assertTrue(turnEnd.reason() instanceof TurnEndReason.Blocked, "turn 应以 Blocked 结束");
    }

    @Test
    void preStepCanRewriteEnteringMessages() throws Exception {
        var session = new Session(new SessionId("rewrite-test"));
        var hooks = AgentHooks.builder()
                .addPreStepHook((ctx, next) -> new PreStepDecision.Enter(
                        List.of(userMessage("rewritten"))))
                .build();
        var agent = newAgent(session,
                new AgentOptions("mock-echo", "mock-model", null, null, hooks));

        agent.followup(userMessage("original"));
        agent.whenIdle();

        var userMsgs = session.events().stream()
                .filter(e -> e instanceof SessionEventUserMessage)
                .map(e -> (SessionEventUserMessage) e)
                .toList();
        assertEquals(1, userMsgs.size());
        var text = (ContentBlock.Text) userMsgs.getFirst().message().content().getFirst();
        assertEquals("rewritten", text.text(), "进入 step 的消息应为 hook 改写后的版本");
    }

    @Test
    void requestHookCanRewriteSystemPrompt() throws Exception {
        var session = new Session(new SessionId("request-test"));
        var seenSystems = new ArrayList<String>();
        // 记录型适配器：捕获实际到达适配器的 system prompt
        var llm = new LlmRuntime();
        llm.registerAdapter("mock-echo", new MockEchoAdapter() {
            @Override
            public void stream(GenerateOptions options, StreamCallback callback) {
                seenSystems.add(options.system());
                super.stream(options, callback);
            }
        });

        var hooks = AgentHooks.builder()
                .addRequestHook((ctx, next) -> {
                    var options = next.proceed();
                    return new GenerateOptions(options.provider(), options.model(),
                            options.messages(), "hook-injected-system", options.tools());
                })
                .build();
        var agent = new ReactLoopAgent(
                new SessionId("request-agent"),
                new AgentOptions("mock-echo", "mock-model", null, null, hooks),
                session, llm, new SystemPromptImpl("", false), new ToolRegistryImpl());

        agent.followup(userMessage("hello"));
        agent.whenIdle();

        assertEquals(List.of("hook-injected-system"), seenSystems,
                "适配器应收到 hook 改写后的 system prompt");
    }

    @Test
    void toolHookCanShortCircuitExecution() throws Exception {
        var session = new Session(new SessionId("tool-test"));
        var toolCalls = new AtomicInteger();

        var tools = new ToolRegistryImpl();
        tools.register(new ToolDefinition(
                "echo", "回显文本", Map.of(),
                ToolExecutor.of(args -> {
                    toolCalls.incrementAndGet();
                    return new ToolExecutionResult("tool-body");
                })));

        var hooks = AgentHooks.builder()
                .addToolHook((ctx, next) -> new ToolExecutionResult("blocked-by-hook"))
                .build();
        var llm = new LlmRuntime();
        llm.registerAdapter("mock-echo", new MockEchoAdapter());
        var agent = new ReactLoopAgent(
                new SessionId("tool-agent"),
                new AgentOptions("mock-echo", "mock-model", null, null, hooks),
                session, llm, new SystemPromptImpl("", false), tools);

        agent.followup(userMessage("echo payload"));
        agent.whenIdle();

        assertEquals(0, toolCalls.get(), "hook 短路时工具本体不应执行");
        var toolResults = session.events().stream()
                .filter(e -> e instanceof SessionEventToolResult)
                .map(e -> (SessionEventToolResult) e)
                .toList();
        assertEquals(1, toolResults.size());
        var toolBlock = (ContentBlock.ToolResult) toolResults.getFirst().message().content().getFirst();
        var resultText = (ContentBlock.Text) toolBlock.content().getFirst();
        assertEquals("blocked-by-hook", resultText.text(), "日志中的工具结果应为 hook 的替代结果");
        var turnEnd = (SessionEventTurnEnd) session.events().getLast();
        assertTrue(turnEnd.reason() instanceof TurnEndReason.Completed);
    }

    @Test
    void retryHookRecoversFromTransientFailure() throws Exception {
        var session = new Session(new SessionId("retry-test"));
        var hooks = AgentHooks.builder()
                .addRequestErrorHook(new LlmRetryHook(3, Duration.ofMillis(10), 2.0))
                .build();
        var agent = newAgent(session,
                new AgentOptions("flaky-echo", "mock-model", null, null, hooks));

        agent.followup(userMessage("hello"));
        agent.whenIdle();

        var turnEnd = (SessionEventTurnEnd) session.events().getLast();
        assertTrue(turnEnd.reason() instanceof TurnEndReason.Completed,
                "两次瞬时失败后重试应恢复并正常完成 turn");
        // FlakyAdapter(2)：前 2 次 503，第 3 次成功；用户消息只应入日志一次
        var userMessageCount = session.events().stream()
                .filter(e -> e instanceof SessionEventUserMessage)
                .count();
        assertEquals(1, userMessageCount, "重试不应重复写入用户消息");
    }

    /** 永远失败并计数调用次数的适配器。 */
    private static final class AlwaysFailAdapter extends MockEchoAdapter {

        private final AtomicInteger calls = new AtomicInteger();

        @Override
        public void stream(GenerateOptions options, StreamCallback callback) {
            calls.incrementAndGet();
            callback.onError(new LlmException("持续失败", 503));
        }
    }

    /** 失败后保留回调引用的适配器，用于模拟迟到的流式回调。 */
    private static final class StoringFailAdapter extends MockEchoAdapter {

        private volatile StreamCallback lastCallback;

        @Override
        public void stream(GenerateOptions options, StreamCallback callback) {
            lastCallback = callback;
            callback.onError(new LlmException("立即失败", 503));
        }
    }

    @Test
    void retryHardLimitStopsInfiniteRetryHook() throws Exception {
        var session = new Session(new SessionId("hard-limit-test"));
        var adapter = new AlwaysFailAdapter();
        var llm = new LlmRuntime();
        llm.registerAdapter("always-fail", adapter);
        // 无上限地返回 Retry 的 hook：循环层硬上限必须兜底
        var hooks = AgentHooks.builder()
                .addRequestErrorHook((ctx, next) -> new RequestErrorAction.Retry())
                .build();
        var agent = new ReactLoopAgent(
                new SessionId("hard-limit-agent"),
                new AgentOptions("always-fail", "mock-model", null, null, hooks),
                session, llm, new SystemPromptImpl("", false), new ToolRegistryImpl());

        agent.followup(userMessage("hello"));
        agent.whenIdle();

        var turnEnd = (SessionEventTurnEnd) session.events().getLast();
        assertTrue(turnEnd.reason() instanceof TurnEndReason.Error,
                "超过循环层硬上限后 turn 应以 Error 结束");
        assertEquals(10, adapter.calls.get(),
                "请求次数应被硬上限截断为 10（1 次初始 + 9 次重试）");
    }

    @Test
    void lateChunkAfterFailureDoesNotPolluteLog() throws Exception {
        var session = new Session(new SessionId("late-chunk-test"));
        var adapter = new StoringFailAdapter();
        var llm = new LlmRuntime();
        llm.registerAdapter("storing-fail", adapter);
        // 无恢复 hook：失败保持失败，turn 以 Error 结束
        var agent = new ReactLoopAgent(
                new SessionId("late-chunk-agent"),
                new AgentOptions("storing-fail", "mock-model", null, null, AgentHooks.empty()),
                session, llm, new SystemPromptImpl("", false), new ToolRegistryImpl());

        agent.followup(userMessage("hello"));
        agent.whenIdle();

        var chunksAfterFailure = session.events().stream()
                .filter(e -> e instanceof SessionEventAssistantChunk)
                .count();
        assertEquals(0, chunksAfterFailure, "失败的请求不应产生任何 chunk");

        // 模拟迟到的流式回调（如超时后仍在到达的网络数据）：必须被门闩丢弃
        adapter.lastCallback.onChunk(new StreamChunk.TextDelta(0, "late"));
        var chunksAfterLateArrival = session.events().stream()
                .filter(e -> e instanceof SessionEventAssistantChunk)
                .count();
        assertEquals(0, chunksAfterLateArrival, "迟到的 chunk 不得写入会话日志");
    }

    @Test
    void preStepHookFailureFailsTurnLoudly() throws Exception {
        var session = new Session(new SessionId("hook-fail-test"));
        var hooks = AgentHooks.builder()
                .addPreStepHook((ctx, next) -> {
                    throw new IllegalStateException("hook 内部错误");
                })
                .build();
        var agent = newAgent(session,
                new AgentOptions("mock-echo", "mock-model", null, null, hooks));

        agent.followup(userMessage("hello"));
        agent.whenIdle();

        var events = session.events();
        assertEquals(0, events.stream().filter(e -> e instanceof SessionEventStepStart).count(),
                "hook 异常的 turn 不应产生 step");
        var turnEnd = (SessionEventTurnEnd) events.getLast();
        assertTrue(turnEnd.reason() instanceof TurnEndReason.Error,
                "hook 异常必须导致 turn 以 Error 结束（fail loud）");
    }

    @Test
    void requestHookCanTakeoverWithoutProceed() throws Exception {
        var session = new Session(new SessionId("takeover-test"));
        var mainCalls = new AtomicInteger();
        var targetCalls = new AtomicInteger();
        var llm = new LlmRuntime();
        llm.registerAdapter("mock-echo", new MockEchoAdapter() {
            @Override
            public void stream(GenerateOptions options, StreamCallback callback) {
                mainCalls.incrementAndGet();
                super.stream(options, callback);
            }
        });
        llm.registerAdapter("takeover-target", new MockEchoAdapter() {
            @Override
            public void stream(GenerateOptions options, StreamCallback callback) {
                targetCalls.incrementAndGet();
                super.stream(options, callback);
            }
        });

        // 接管：不调 next，直接把请求改指到另一个提供方
        var hooks = AgentHooks.builder()
                .addRequestHook((ctx, next) -> new GenerateOptions(
                        "takeover-target", "any-model", session.deriveMessages(), null, null))
                .build();
        var agent = new ReactLoopAgent(
                new SessionId("takeover-agent"),
                new AgentOptions("mock-echo", "mock-model", null, null, hooks),
                session, llm, new SystemPromptImpl("", false), new ToolRegistryImpl());

        agent.followup(userMessage("hello"));
        agent.whenIdle();

        assertEquals(0, mainCalls.get(), "接管后原提供方不应被调用");
        assertTrue(targetCalls.get() >= 1, "接管目标提供方应收到请求");
        var turnEnd = (SessionEventTurnEnd) session.events().getLast();
        assertTrue(turnEnd.reason() instanceof TurnEndReason.Completed);
    }

    @Test
    void toolHookCanWrapAndRewriteResult() throws Exception {
        var session = new Session(new SessionId("wrap-tool-test"));
        var toolExecuted = new AtomicInteger();
        var tools = new ToolRegistryImpl();
        tools.register(new ToolDefinition(
                "echo", "回显文本", Map.of(),
                ToolExecutor.of(args -> {
                    toolExecuted.incrementAndGet();
                    return new ToolExecutionResult("secret-raw-value");
                })));

        // 放行执行，但把结果替换为脱敏版本（around 包装语义）
        var hooks = AgentHooks.builder()
                .addToolHook((ctx, next) -> {
                    next.proceed();
                    return new ToolExecutionResult("sanitized-by-hook");
                })
                .build();
        var llm = new LlmRuntime();
        llm.registerAdapter("mock-echo", new MockEchoAdapter());
        var agent = new ReactLoopAgent(
                new SessionId("wrap-tool-agent"),
                new AgentOptions("mock-echo", "mock-model", null, null, hooks),
                session, llm, new SystemPromptImpl("", false), tools);

        agent.followup(userMessage("echo secret"));
        agent.whenIdle();

        assertEquals(1, toolExecuted.get(), "包装语义下工具本体应执行恰好一次");
        var toolResults = session.events().stream()
                .filter(e -> e instanceof SessionEventToolResult)
                .map(e -> (SessionEventToolResult) e)
                .toList();
        var toolBlock = (ContentBlock.ToolResult) toolResults.getFirst().message().content().getFirst();
        var resultText = (ContentBlock.Text) toolBlock.content().getFirst();
        assertEquals("sanitized-by-hook", resultText.text(), "日志应记录 hook 改写后的结果");
    }

    @Test
    void failureIsKeptWhenNoHookRecovers() throws Exception {
        var session = new Session(new SessionId("keep-fail-test"));
        var agent = newAgent(session,
                new AgentOptions("flaky-echo", "mock-model", null, null, AgentHooks.empty()));

        agent.followup(userMessage("hello"));
        agent.whenIdle();

        var turnEnd = (SessionEventTurnEnd) session.events().getLast();
        assertTrue(turnEnd.reason() instanceof TurnEndReason.Error,
                "无恢复 hook 时失败必须保持失败（内置 Fail 行为）");
    }
}
