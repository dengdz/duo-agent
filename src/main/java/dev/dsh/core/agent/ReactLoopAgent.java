package dev.dsh.core.agent;

import dev.dsh.api.agent.Agent;
import dev.dsh.api.agent.AgentCancelCause;
import dev.dsh.api.agent.AgentOptions;
import dev.dsh.api.agent.AgentStatus;
import dev.dsh.api.agent.CancelOptions;
import dev.dsh.api.agent.Inbox;
import dev.dsh.api.agent.InboxTarget;
import dev.dsh.api.llm.LlmRuntime;
import dev.dsh.api.llm.StreamCallback;
import dev.dsh.api.llm.SystemPrompt;
import dev.dsh.api.llm.ToolRegistry;
import dev.dsh.core.llm.BlockAssembler;
import dev.dsh.core.llm.SystemPromptImpl;
import dev.dsh.core.session.Session;
import dev.dsh.exception.AgentLoopException;
import dev.dsh.model.llm.ContentBlock;
import dev.dsh.model.llm.FinishReason;
import dev.dsh.model.llm.GenerateOptions;
import dev.dsh.model.llm.Message;
import dev.dsh.model.llm.MessageFactory;
import dev.dsh.model.llm.StreamChunk;
import dev.dsh.model.llm.LlmFailure;
import dev.dsh.model.llm.ToolExecutionResult;
import dev.dsh.model.session.SessionEventAssistantChunk;
import dev.dsh.model.session.SessionEventAssistantMessage;
import dev.dsh.model.session.SessionEventStepEnd;
import dev.dsh.model.session.SessionEventStepStart;
import dev.dsh.model.session.SessionEventToolCall;
import dev.dsh.model.session.SessionEventToolResult;
import dev.dsh.model.session.SessionEventTurnEnd;
import dev.dsh.model.session.SessionEventTurnStart;
import dev.dsh.model.session.SessionEventUserMessage;
import dev.dsh.model.session.SessionId;
import dev.dsh.model.session.SurfaceOp;
import dev.dsh.model.session.TurnEndCancelCause;
import dev.dsh.model.session.TurnEndReason;
import dev.dsh.util.CallId;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Agent 驱动循环。
 * <p>
 * 实现核心 turn/step 状态机、session 日志、inbox 消息流转、LLM 调用和 system-prompt 组装。
 * 简化版跳过：agent/pre-step/request/turn-stopping 等拦截器、工具执行。
 * </p>
 *
 * @author zhangyl
 * @date 2026-08-18
 */
public class ReactLoopAgent implements Agent {

    private static final String DEFAULT_PROVIDER = "mock-echo";
    private static final String DEFAULT_MODEL = "mock-model";
    private static final long LLM_CALL_TIMEOUT_SECONDS = 60;

    // ---- 状态机 ----

    private sealed interface Phase permits Idle, Running {}

    private record Idle(int lastTurn) implements Phase {}

    private record Running(
            int turn,
            int step,
            boolean cancelled
    ) implements Phase {}

    // ---- 字段 ----

    private final SessionId id;
    private final AgentOptions options;
    private final Session session;
    private final Inbox inbox = new Inbox();
    private final LlmRuntime llmRuntime;
    private final SystemPrompt systemPrompt;
    private final ToolRegistry toolRegistry;
    private final Executor driverExecutor;

    private volatile Phase phase;
    private volatile CompletableFuture<Void> activity = CompletableFuture.completedFuture(null);
    private volatile boolean wakeLatch;

    // ---- 构造 ----

    public ReactLoopAgent(SessionId id, AgentOptions options, Session session,
                          LlmRuntime llmRuntime, SystemPrompt systemPrompt, ToolRegistry toolRegistry) {
        this.id = id;
        this.options = options;
        this.session = session;
        this.llmRuntime = llmRuntime;
        this.systemPrompt = systemPrompt;
        this.toolRegistry = toolRegistry;
        // 命名的虚拟线程执行器：统一驱动线程的创建与命名，便于排查
        this.driverExecutor = Executors.newThreadPerTaskExecutor(
                Thread.ofVirtual().name("agent-driver-", 0).factory());
        // 从 session 日志恢复最后 turn 号
        int lastTurn = 0;
        for (var e : session.events()) {
            if (e instanceof SessionEventTurnStart ts) lastTurn = ts.turn();
        }
        this.phase = new Idle(lastTurn);
    }

    // ---- Agent 接口实现 ----

    @Override
    public SessionId id() {
        return id;
    }

    @Override
    public AgentOptions options() {
        return options;
    }

    @Override
    public Session session() {
        return session;
    }

    @Override
    public Inbox inbox() {
        return inbox;
    }

    @Override
    public AgentStatus status() {
        return phase instanceof Running ? AgentStatus.RUNNING : AgentStatus.IDLE;
    }

    @Override
    public void cancel(AgentCancelCause cause, CancelOptions opts) {
        if (!opts.keepInbox()) inbox.clear();
        phase = new Idle(lastTurnOf(phase));
    }

    @Override
    public void whenIdle() throws InterruptedException {
        try {
            activity.get();
        } catch (ExecutionException e) {
            // 忽略已完成的活动错误
        }
    }

    @Override
    public void send(Message msg, InboxTarget target, boolean wakeup) {
        inbox.append(target, msg);
        if (wakeup) wakeDriver();
    }

    @Override
    public void followup(Message msg) {
        send(msg, InboxTarget.NEXT_TURN, true);
    }

    @Override
    public void steer(Message msg) {
        send(msg, InboxTarget.NEXT_STEP, true);
    }

    @Override
    public void inject(Message msg) {
        send(msg, InboxTarget.NEXT_STEP, false);
    }

    // ---- 驱动 ----

    private void wakeDriver() {
        if (!(phase instanceof Idle)) {
            wakeLatch = true;
            return;
        }
        var p = (Idle) phase;
        var fut = new CompletableFuture<Void>();
        activity = fut;
        phase = new Running(p.lastTurn + 1, 0, false);

        driverExecutor.execute(() -> {
            try {
                runLoop();
                fut.complete(null);
            } catch (AgentLoopException e) {
                fut.completeExceptionally(e);
            }
        });
    }

    /** 驱动主循环：反复开 turn。 */
    private void runLoop() throws AgentLoopException {
        try {
            while (phase instanceof Running r && !r.cancelled) {
                if (!turn(r)) break;
            }
        } finally {
            int last = lastTurnOf(phase);
            phase = new Idle(last);
            if (wakeLatch && inbox.hasPending()) {
                wakeLatch = false;
                wakeDriver();
            }
        }
    }

    /** 执行一轮对话。返回 true 表示还有待办需要继续。 */
    private boolean turn(Running r) throws AgentLoopException {
        int turn = r.turn;
        session.append(new SessionEventTurnStart(session.seq(), turn));
        phase = new Running(turn, 0, false);

        TurnEndReason reason = null;
        var target = InboxTarget.NEXT_TURN;
        int step = 0;

        try {
            while (true) {
                step++;
                var claimed = inbox.claim(target);
                if (claimed.isEmpty()) { reason = new TurnEndReason.Completed(); break; }

                phase = new Running(turn, step, false);
                session.append(new SessionEventStepStart(session.seq(), turn, step));

                for (var msg : claimed) {
                    if (msg instanceof Message.UserMessage userMsg) {
                        session.append(new SessionEventUserMessage(
                                session.seq(), userMsg, new SurfaceOp.Append()
                        ));
                    }
                }

                try {
                    var stepEnd = executeStep(turn, step);
                    if (!(reason instanceof TurnEndReason.MaxTokens)) reason = stepEnd;
                } finally {
                    session.append(new SessionEventStepEnd(session.seq(), turn, step));
                }

                if (reason != null && inbox.nextStep().isEmpty()) break;
                target = InboxTarget.NEXT_STEP;
            }
        } catch (AgentLoopException e) {
            if (reason == null) {
                reason = new TurnEndReason.Error(new LlmFailure(e.getMessage(), "UNKNOWN"));
            }
            throw e;
        } finally {
            session.append(new SessionEventTurnEnd(
                    session.seq(), turn,
                    reason != null ? reason : new TurnEndReason.Completed()
            ));
        }

        return inbox.hasPending();
    }

    /** 执行一次模型调用。返回 step 结束原因。 */
    private TurnEndReason executeStep(int turn, int step) throws AgentLoopException {
        var provider = options.provider() != null ? options.provider() : DEFAULT_PROVIDER;
        var model = options.model() != null ? options.model() : DEFAULT_MODEL;
        var messages = session.deriveMessages();

        // 组装 system prompt
        var assembly = systemPrompt.assemble();
        var system = SystemPromptImpl.renderPrompt(assembly);
        var tools = assembly.tools().isEmpty() ? null : assembly.tools();

        var request = new GenerateOptions(provider, model, messages, system, tools);

        var assembler = new BlockAssembler();
        var chunkSeqs = new ArrayList<Integer>();
        var barrier = new CompletableFuture<Void>();
        var errorRef = new AtomicReference<Throwable>();

        llmRuntime.stream(request, new StreamCallback() {
            @Override
            public void onChunk(StreamChunk chunk) {
                int seq = session.seq();
                session.append(new SessionEventAssistantChunk(seq, turn, step, chunk));
                chunkSeqs.add(seq);
                assembler.push(chunk);
            }
            @Override
            public void onComplete() { barrier.complete(null); }
            @Override
            public void onError(Throwable err) { errorRef.set(err); barrier.completeExceptionally(err); }
        });

        try {
            barrier.get(LLM_CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AgentLoopException("LLM 调用被中断", e);
        } catch (ExecutionException e) {
            throw new AgentLoopException("LLM 调用失败", e.getCause());
        } catch (java.util.concurrent.TimeoutException e) {
            throw new AgentLoopException("LLM 调用超时", e);
        }
        if (errorRef.get() != null) throw new AgentLoopException("LLM 调用失败", errorRef.get());

        var finish = assembler.finish();
        if (finish instanceof FinishReason.Aborted || finish instanceof FinishReason.Error) {
            throw new AgentLoopException("LLM 异常结束");
        }

        var assistantMsg = MessageFactory.createAssistantMessage(assembler.blocks(), provider, model);
        session.append(new SessionEventAssistantMessage(
                session.seq(), turn, step, assistantMsg,
                new SurfaceOp.Append(), assembler.usage().orElse(null)
        ));

        if (finish instanceof FinishReason.MaxTokens) return new TurnEndReason.MaxTokens();

        // 检查是否有工具调用
        var toolCallBlocks = assembler.blocks().stream()
                .filter(b -> b instanceof ContentBlock.ToolCall)
                .map(b -> (ContentBlock.ToolCall) b)
                .toList();

        if (toolCallBlocks.isEmpty()) return new TurnEndReason.Completed();

        // 执行工具调用
        for (var tc : toolCallBlocks) {
            session.append(new SessionEventToolCall(
                    session.seq(), turn, step, tc.id(), tc.name(), tc.arguments()
            ));

            // 解析参数；失败时以错误结果返回给模型，避免工具在错误参数下静默执行
            ToolExecutionResult result;
            try {
                result = toolRegistry.execute(tc.name(), parseJsonArgs(tc.arguments()));
            } catch (IllegalArgumentException e) {
                result = new ToolExecutionResult(e);
            }

            var toolResultMsg = MessageFactory.createToolResultMessage(tc.id(), result.content(), result.isError());
            session.append(new SessionEventToolResult(
                    session.seq(), turn, step, toolResultMsg, new SurfaceOp.Append()
            ));

            // 将工具结果注入 inbox 作为 next-step 消息，驱动下一轮 step
            inbox.append(InboxTarget.NEXT_STEP, toolResultMsg);
        }

        // 有工具调用时，返回 null 表示 step 继续
        return null;
    }

    /** 解析 JSON 参数为嵌套结构；非法输入抛出 IllegalArgumentException。 */
    Map<String, Object> parseJsonArgs(String json) {
        if (json == null || json.isBlank()) return Map.of();
        var parsed = dev.dsh.util.JsonParser.parse(json);
        if (!(parsed instanceof Map<?, ?> m)) {
            throw new IllegalArgumentException("工具参数必须是 JSON 对象");
        }
        var result = new java.util.LinkedHashMap<String, Object>();
        for (var e : m.entrySet()) {
            result.put(String.valueOf(e.getKey()), e.getValue());
        }
        return result;
    }

    /** 从 phase 中提取最后 turn 号。 */
    private static int lastTurnOf(Phase p) {
        return switch (p) {
            case Idle i -> i.lastTurn;
            case Running r -> r.turn;
        };
    }
}