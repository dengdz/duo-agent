package dev.duo.core;

import dev.duo.api.agent.AgentCancelCause;
import dev.duo.api.agent.AgentHooks;
import dev.duo.api.agent.AgentOptions;
import dev.duo.api.agent.CancelOptions;
import dev.duo.api.agent.CancellationSignal;
import dev.duo.api.agent.RequestErrorAction;
import dev.duo.api.agent.TurnCancelledException;
import dev.duo.api.hook.RequestErrorHook;
import dev.duo.api.llm.LlmAdapter;
import dev.duo.api.llm.LlmRuntime;
import dev.duo.api.llm.StreamCallback;
import dev.duo.api.llm.SystemPrompt;
import dev.duo.core.agent.ReactLoopAgent;
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
import dev.duo.model.llm.ToolDefinition;
import dev.duo.model.llm.ToolExecution;
import dev.duo.model.llm.ToolExecutionResult;
import dev.duo.model.llm.ToolExecutor;
import dev.duo.model.session.SessionEvent;
import dev.duo.model.session.SessionEventToolResult;
import dev.duo.model.session.SessionEventTurnEnd;
import dev.duo.model.session.SessionId;
import dev.duo.model.session.SessionEventTypes;
import dev.duo.model.session.TurnEndCancelCause;
import dev.duo.model.session.TurnEndReason;
import dev.duo.util.CallId;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 取消打断的端到端测试（ADR_004）。
 * <p>
 * 覆盖：取消打断挂起的 LLM 流（含断连监听触发）、取消打断工具执行
 * （sentinel 两档 + tool_call/tool_result 配对完整 + 消息序列合法）、
 * 取消不被 request-error 重试吞、cancel 后立即 followup 不产生并发 turn、
 * keepInbox 保留待办在新 turn 消费、bash 长命令真进程秒级打断。
 * </p>
 *
 * @author zhangyl
 * @date 2026-08-21
 */
class AgentCancellationTest {

    private static final String SENTINEL_ABORTED = "Error: tool call aborted";
    private static final String SENTINEL_BEFORE_DISPATCH = "Error: tool call aborted before dispatch";

    /** 永不回调的适配器：模拟服务器长时间无响应（流挂起）。 */
    static final class HangingAdapter extends LlmAdapter {

        final AtomicBoolean disconnectListenerFired = new AtomicBoolean();

        @Override
        public void stream(GenerateOptions options, StreamCallback callback) {
            // 永不回调
        }

        @Override
        public void stream(GenerateOptions options, StreamCallback callback,
                           CancellationSignal cancellation) {
            // 注册断连监听后挂起；监听器持有的引用不关闭（测试生命周期内等待触发）
            cancellation.addListener(() -> disconnectListenerFired.set(true));
        }
    }

    /** 阻塞至被 interrupt 的工具：取消路径抛 TurnCancelledException，模拟慢工具。 */
    private static ToolDefinition slowTool(String name) {
        return new ToolDefinition(name, "慢工具（测试桩）", Map.of("type", "object"),
                execution -> {
                    try {
                        Thread.sleep(TimeUnit.SECONDS.toMillis(60));
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        if (execution.cancellation().isCancelled()) {
                            throw new TurnCancelledException(execution.cancellation().cause());
                        }
                        return new ToolExecutionResult("错误：意外中断");
                    }
                    return new ToolExecutionResult("不应到达：慢工具未被取消就完成了");
                });
    }

    private static ToolDefinition instantTool(String name, AtomicInteger counter) {
        return new ToolDefinition(name, "即时工具（测试桩）", Map.of("type", "object"),
                ToolExecutor.of(args -> {
                    counter.incrementAndGet();
                    return new ToolExecutionResult("ok");
                }));
    }

    /** 单个工具调用的完整 chunk 脚本。 */
    private static List<StreamChunk> toolCallScript(CallId... ids) {
        var script = new ArrayList<StreamChunk>();
        for (int i = 0; i < ids.length; i++) {
            script.add(new StreamChunk.BlockStart(i, SessionEventTypes.BLOCK_TOOL_CALL));
            script.add(new StreamChunk.ToolCallDelta(i, ids[i], "slow", "{}"));
            script.add(new StreamChunk.BlockEnd(i, new ContentBlock.ToolCall(ids[i], "slow", "{}")));
        }
        script.add(new StreamChunk.Finish(new FinishReason.ToolCalls()));
        return script;
    }

    private record Fixture(ReactLoopAgent agent, Session session) {}

    private static Fixture newFixture(LlmAdapter adapter, String provider,
                                      ToolDefinition... tools) {
        var llm = new LlmRuntime();
        llm.registerAdapter(provider, adapter);
        var registry = new ToolRegistryImpl();
        for (var tool : tools) {
            registry.register(tool);
        }
        var session = new Session(new SessionId("cancel-test"));
        var agent = new ReactLoopAgent(
                new SessionId("cancel-test"),
                new AgentOptions(provider, "mock-model", null, Duration.ofSeconds(30)),
                session, llm, new SystemPromptImpl("", false), registry);
        return new Fixture(agent, session);
    }

    /** 轮询等待某类型事件出现（容忍快照并发重建，超时失败）。 */
    private static void awaitEvent(Session session, String type) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (System.nanoTime() < deadline) {
            try {
                if (session.events().stream().anyMatch(e -> e.type().equals(type))) {
                    return;
                }
            } catch (RuntimeException e) {
                // 驱动线程 append 与快照重建的瞬时竞态：重试即可
            }
            Thread.sleep(20);
        }
        fail("等待事件 " + type + " 超时");
    }

    private static Message userMessage(String text) {
        return MessageFactory.createUserMessage(
                List.of(new ContentBlock.Text(text)), new MessageSource.User());
    }

    // ---- 测试 ----

    @Test
    void cancelDuringHangingLlmStream_endsTurnAbortedAndFiresDisconnect() throws Exception {
        var adapter = new HangingAdapter();
        var fix = newFixture(adapter, "hanging");

        fix.agent().followup(userMessage("hi"));
        awaitEvent(fix.session(), SessionEventTypes.TURN_START);

        assertTimeoutPreemptively(Duration.ofSeconds(10), () -> {
            fix.agent().cancel(new AgentCancelCause.User());
            fix.agent().whenIdle();
        }, "取消后驱动应秒级收敛，而不是等到 llmTimeout 兜底");

        assertTrue(adapter.disconnectListenerFired.get(), "适配器的断连监听器应被触发");

        var turnEnds = eventsOf(fix.session(), SessionEventTurnEnd.class);
        assertEquals(1, turnEnds.size());
        var reason = turnEnds.getFirst().reason();
        assertInstanceOf(TurnEndReason.Aborted.class, reason);
        assertInstanceOf(TurnEndCancelCause.User.class,
                ((TurnEndReason.Aborted) reason).reason());
    }

    @Test
    void cancelDuringToolExecution_writesTwoTierSentinelsAndKeepsPairing() throws Exception {
        var adapter = new ScriptedStreamAdapter(
                toolCallScript(new CallId("call-1"), new CallId("call-2")));
        var instantCalls = new AtomicInteger();
        var fix = newFixture(adapter, "scripted",
                slowTool("slow"), instantTool("instant", instantCalls));

        fix.agent().followup(userMessage("run tools"));
        awaitEvent(fix.session(), SessionEventTypes.TOOL_CALL);

        assertTimeoutPreemptively(Duration.ofSeconds(10), () -> {
            fix.agent().cancel(new AgentCancelCause.User());
            fix.agent().whenIdle();
        }, "慢工具执行中的取消应秒级收敛");

        // 每个被跳过的 tool_call 必有配对 tool_result（含事件档位错误码）
        var toolCalls = eventsOf(fix.session(), dev.duo.model.session.SessionEventToolCall.class);
        var toolResults = eventsOf(fix.session(), SessionEventToolResult.class);
        assertEquals(2, toolCalls.size(), "两个 tool_call 事件都应写入");
        assertEquals(2, toolResults.size(), "每个 tool_call 必有配对 tool_result");

        assertEquals("ABORTED", toolResults.get(0).errorCode(),
                "执行中被打断的调用档位为 ABORTED");
        assertEquals("ABORTED_BEFORE_DISPATCH", toolResults.get(1).errorCode(),
                "未启动的调用档位为 ABORTED_BEFORE_DISPATCH");
        var firstBlock = (ContentBlock.ToolResult) toolResults.get(0).message().content().getFirst();
        assertTrue(firstBlock.isError(), "sentinel 必须是错误结果");
        var innerText = (ContentBlock.Text) firstBlock.content().getFirst();
        assertEquals(SENTINEL_ABORTED, innerText.text(), "sentinel 固定文案回填模型");
        assertEquals(0, instantCalls.get(), "后续工具不应被执行");

        var turnEnd = eventsOf(fix.session(), SessionEventTurnEnd.class).getFirst();
        assertInstanceOf(TurnEndReason.Aborted.class, turnEnd.reason());

        // 消息序列合法：派生的下一请求中每个 tool_call 都有配对 tool result
        // （否则三协议服务端直接 400）
        var derived = fix.session().deriveMessages();
        long toolCallBlocks = derived.stream()
                .filter(m -> m instanceof Message.AssistantMessage)
                .flatMap(m -> ((Message.AssistantMessage) m).content().stream())
                .filter(b -> b instanceof ContentBlock.ToolCall)
                .count();
        long toolResultMsgs = derived.stream()
                .filter(m -> m instanceof Message.ToolResultMessage)
                .count();
        assertEquals(toolCallBlocks, toolResultMsgs, "派生消息必须满足 tool_call/tool_result 配对");
    }

    @Test
    void cancelIsNotSwallowedByRequestErrorRetry() throws Exception {
        // request-error hook 恒定 Retry：若取消被当作可重试失败，驱动会反复重试
        var hooks = AgentHooks.builder()
                .addRequestErrorHook((RequestErrorHook.RequestErrorContext ctx,
                                      dev.duo.api.hook.RequestErrorHook.Chain next) ->
                        new RequestErrorAction.Retry())
                .build();
        var adapter = new HangingAdapter();
        var llm = new LlmRuntime();
        llm.registerAdapter("hanging-retry", adapter);
        var session = new Session(new SessionId("cancel-retry-test"));
        var agentWithHooks = new ReactLoopAgent(
                new SessionId("cancel-retry-test"),
                new AgentOptions("hanging-retry", "mock-model", null,
                        Duration.ofSeconds(30), hooks),
                session, llm, new SystemPromptImpl("", false), new ToolRegistryImpl());

        agentWithHooks.followup(userMessage("hi"));
        awaitEvent(session, SessionEventTypes.TURN_START);

        assertTimeoutPreemptively(Duration.ofSeconds(10), () -> {
            agentWithHooks.cancel(new AgentCancelCause.User());
            agentWithHooks.whenIdle();
        }, "取消必须先于 request-error 分发，不得进入重试循环");

        var turnEnd = eventsOf(session, SessionEventTurnEnd.class).getFirst();
        assertInstanceOf(TurnEndReason.Aborted.class, turnEnd.reason(),
                "取消收尾为 Aborted 而非 Error/重试");
    }

    @Test
    void cancelThenImmediateFollowup_producesNoConcurrentTurns() throws Exception {
        // 慢速首调（5s）保证 turn1 稳态挂起被打断；后续调用快速回放，
        // 第二个 turn 消费 followup 消息后正常完成，whenIdle 应秒级静止
        var adapter = new ScriptedStreamAdapter(
                ScriptedStreamAdapter.textReply("done."), null, 5_000);
        var fix = newFixture(adapter, "scripted-race");

        fix.agent().followup(userMessage("first"));
        awaitEvent(fix.session(), SessionEventTypes.TURN_START);

        // cancel 与 followup 紧邻发出：收敛前的新消息必须 latch 等待而非立即开新 turn
        fix.agent().cancel(new AgentCancelCause.User());
        fix.agent().followup(userMessage("second"));

        assertTimeoutPreemptively(Duration.ofSeconds(10), fix.agent()::whenIdle);

        var turnEnds = eventsOf(fix.session(), SessionEventTurnEnd.class);
        assertEquals(2, turnEnds.size(), "两个 turn 先后结束");
        // 事件序列按 turn 分段无交错：第二个 turn 的 turn/start 必须出现在
        // 第一个 turn/end 之后（两条挂起流不会并发写日志）
        var types = fix.session().events().stream().map(SessionEvent::type).toList();
        int firstEnd = types.indexOf(SessionEventTypes.TURN_END);
        int secondStart = -1;
        for (int i = firstEnd + 1; i < types.size(); i++) {
            if (types.get(i).equals(SessionEventTypes.TURN_START)) {
                secondStart = i;
                break;
            }
        }
        assertTrue(secondStart > firstEnd, "第二个 turn 必须在第一个 turn 结束后才开始");
    }

    @Test
    void cancelWithKeepInbox_preservesPendingForNextTurn() throws Exception {
        // 慢速首调（5s 延迟）：保证 cancel 落在 turn 稳态的流等待中，
        // 避开 turn 边界的 interrupt 残留窗口（ADR_004 已知限制）
        var adapter = new ScriptedStreamAdapter(
                ScriptedStreamAdapter.textReply("done."), null, 5_000);
        var fix = newFixture(adapter, "scripted-keep");

        fix.agent().followup(userMessage("first"));
        fix.agent().followup(userMessage("queued"));
        awaitEvent(fix.session(), SessionEventTypes.TURN_START);

        // keepInbox：中止当前 turn，保留排队的 "queued"
        fix.agent().cancel(new AgentCancelCause.User(), new CancelOptions(true));
        assertTimeoutPreemptively(Duration.ofSeconds(10), fix.agent()::whenIdle);

        var turnEnds = eventsOf(fix.session(), SessionEventTurnEnd.class);
        assertEquals(2, turnEnds.size(), "保留的待办应驱动新 turn");
        assertInstanceOf(TurnEndReason.Aborted.class, turnEnds.get(0).reason());
        assertInstanceOf(TurnEndReason.Completed.class, turnEnds.get(1).reason(),
                "新 turn 正常完成（ScriptedAdapter 后续回复固定文本）");
    }

    @Test
    void cancelInterruptsRealBashProcessWithinSeconds() throws Exception {
        List<StreamChunk> bashScript = List.of(
                new StreamChunk.BlockStart(0, SessionEventTypes.BLOCK_TOOL_CALL),
                new StreamChunk.ToolCallDelta(0, new CallId("bash-call"), "bash",
                        "{\"command\":\"sleep 30\",\"timeout\":60}"),
                new StreamChunk.BlockEnd(0, new ContentBlock.ToolCall(new CallId("bash-call"),
                        "bash", "{\"command\":\"sleep 30\",\"timeout\":60}")),
                new StreamChunk.Finish(new FinishReason.ToolCalls())
        );
        var bash = new dev.duo.tool.BashTool().getDefinition();
        var fix = newFixture(new ScriptedStreamAdapter(bashScript), "scripted-bash", bash);

        fix.agent().followup(userMessage("run bash"));
        awaitEvent(fix.session(), SessionEventTypes.TOOL_CALL);

        var startedAt = System.nanoTime();
        assertTimeoutPreemptively(Duration.ofSeconds(15), () -> {
            fix.agent().cancel(new AgentCancelCause.User());
            fix.agent().whenIdle();
        }, "bash sleep 30 的取消应秒级打断，而不是等命令超时");
        var elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
        assertTrue(elapsedMs < TimeUnit.SECONDS.toMillis(20), "取消应在远小于 30s 内完成");

        var result = eventsOf(fix.session(), SessionEventToolResult.class).getFirst();
        assertEquals("ABORTED", result.errorCode());
    }

    @Test
    void facadeCancel_interruptsHangingTurnAndUnblocksCall() throws Exception {
        // 门面 cancel 的"停止生成"场景：call() 阻塞中，另一线程走 DuoAgent.cancel()
        var fix = newFixture(new HangingAdapter(), "hanging-facade");
        var duo = new DuoAgentImpl(fix.agent(), fix.session());

        fix.agent().followup(userMessage("hi"));
        awaitEvent(fix.session(), SessionEventTypes.TURN_START);

        var callResult = new AtomicReference<String>();
        var callError = new AtomicReference<RuntimeException>();
        var caller = new Thread(() -> {
            try {
                callResult.set(duo.call("again"));
            } catch (RuntimeException e) {
                callError.set(e);
            }
        });
        caller.start();

        assertTimeoutPreemptively(Duration.ofSeconds(10), () -> {
            // 等 caller 进入阻塞（followup 已入队或尚未入队，两种时序结果一致：
            // turn1 都以 Aborted 收尾，caller 的 whenIdle 返回）
            Thread.sleep(200);
            duo.cancel(new AgentCancelCause.User());
            caller.join(TimeUnit.SECONDS.toMillis(9));
        }, "门面取消应中断挂起 turn 并秒级解除 call 阻塞");

        assertNull(callError.get(), "取消后的 call 不应抛异常");
        assertEquals("(Agent 已取消)", callResult.get(),
                "被取消轮上的阻塞 call 返回提示文本");

        var turnEnd = eventsOf(fix.session(), SessionEventTurnEnd.class).getFirst();
        assertInstanceOf(TurnEndReason.Aborted.class, turnEnd.reason(),
                "门面 cancel 与底层 cancel 语义一致");
    }

    // ---- 辅助 ----

    private static <T extends SessionEvent> List<T> eventsOf(Session session, Class<T> type) {
        return session.events().stream()
                .filter(type::isInstance)
                .map(type::cast)
                .toList();
    }
}
