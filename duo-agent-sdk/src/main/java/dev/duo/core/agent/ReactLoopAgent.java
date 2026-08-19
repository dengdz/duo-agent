package dev.duo.core.agent;

import dev.duo.api.agent.Agent;
import dev.duo.api.agent.AgentCancelCause;
import dev.duo.api.agent.AgentHooks;
import dev.duo.api.agent.AgentOptions;
import dev.duo.api.agent.AgentStatus;
import dev.duo.api.agent.CancelOptions;
import dev.duo.api.agent.Inbox;
import dev.duo.api.agent.InboxTarget;
import dev.duo.api.agent.PreStepDecision;
import dev.duo.api.agent.RequestErrorAction;
import dev.duo.api.hook.PreStepHook;
import dev.duo.api.hook.RequestErrorHook;
import dev.duo.api.hook.RequestHook;
import dev.duo.api.hook.ToolExecutionHook;
import dev.duo.api.llm.LlmRuntime;
import dev.duo.api.llm.StreamCallback;
import dev.duo.api.llm.SystemPrompt;
import dev.duo.api.llm.ToolRegistry;
import dev.duo.core.llm.BlockAssembler;
import dev.duo.core.llm.SystemPromptImpl;
import dev.duo.core.session.Session;
import dev.duo.exception.AgentLoopException;
import dev.duo.exception.LlmException;
import dev.duo.model.llm.ContentBlock;
import dev.duo.model.llm.FinishReason;
import dev.duo.model.llm.GenerateOptions;
import dev.duo.model.llm.Message;
import dev.duo.model.llm.MessageFactory;
import dev.duo.model.llm.StreamChunk;
import dev.duo.model.llm.LlmFailure;
import dev.duo.model.llm.ToolExecutionResult;
import dev.duo.model.session.SessionEventAssistantChunk;
import dev.duo.model.session.SessionEventAssistantMessage;
import dev.duo.model.session.SessionEventStepEnd;
import dev.duo.model.session.SessionEventStepStart;
import dev.duo.model.session.SessionEventToolCall;
import dev.duo.model.session.SessionEventToolResult;
import dev.duo.model.session.SessionEventTurnEnd;
import dev.duo.model.session.SessionEventTurnStart;
import dev.duo.model.session.SessionEventUserMessage;
import dev.duo.model.session.SessionId;
import dev.duo.model.session.SurfaceOp;
import dev.duo.model.session.TurnEndReason;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Agent 驱动循环。
 * <p>
 * 实现核心 turn/step 状态机、session 日志、inbox 消息流转、LLM 调用和 system-prompt 组装。
 * 行为扩展通过 {@link AgentHooks} 的四个拦截点外挂（pre-step 决策、request 构造、
 * request-error 恢复、工具执行环绕），循环本身不因新能力而修改。
 * </p>
 *
 * @author zhangyl
 * @date 2026-08-18
 */
public class ReactLoopAgent implements Agent {

    private static final Logger logger = LoggerFactory.getLogger(ReactLoopAgent.class);
    
    private static final String DEFAULT_PROVIDER = "mock-echo";
    private static final String DEFAULT_MODEL = "mock-model";
    private static final String FAILURE_CODE_UNKNOWN = "UNKNOWN";
    /** 循环层的重试硬上限：防止无上限的 request-error hook 造成无限重试。 */
    private static final int MAX_REQUEST_ATTEMPTS_PER_STEP = 10;

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
    private final AgentHooks hooks;
    private final Executor driverExecutor;

    private final Object phaseLock = new Object();
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
        this.hooks = options.getHooksOrDefault();
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
        synchronized (phaseLock) {
            if (!opts.keepInbox()) inbox.clear();
            phase = new Idle(lastTurnOf(phase));
        }
    }

    @Override
    public void whenIdle() throws InterruptedException {
        try {
            activity.get();
        } catch (ExecutionException e) {
            // 活动已完成但有异常，记录异常但不阻塞 whenIdle 返回。
            // 吞异常处必须留痕：WARN 单行摘要保证任何级别可见，堆栈留在 DEBUG 层。
            // 注意：cause 必须显式 toString()——SLF4J 会把尾随 Throwable 参数
            // 特殊处理为异常对象而不填充占位符
            var cause = e.getCause() != null ? e.getCause() : e;
            logger.warn("Agent {} 活动以异常结束（whenIdle 忽略并正常返回）: {}",
                    id, cause.toString());
            logger.debug("Agent {} 活动异常堆栈", id, e);
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
        synchronized (phaseLock) {
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
    }

    /** 驱动主循环：反复开 turn。 */
    private void runLoop() throws AgentLoopException {
        try {
            while (phase instanceof Running r && !r.cancelled) {
                if (!turn(r)) break;
            }
        } finally {
            synchronized (phaseLock) {
                int last = lastTurnOf(phase);
                phase = new Idle(last);
                if (wakeLatch && inbox.hasPending()) {
                    wakeLatch = false;
                    wakeDriver();
                }
            }
        }
    }

    /** 执行一轮对话。返回 true 表示还有待办需要继续。 */
    private boolean turn(Running r) throws AgentLoopException {
        int turn = r.turn;
        logger.debug("Agent {} starting turn {}", id, turn);
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

                // pre-step 决策链：内置行为 = 以已认领的用户消息原样进入
                var entering = userMessagesOf(claimed);
                PreStepDecision decision;
                try {
                    decision = hooks.dispatchPreStep(
                            new PreStepHook.PreStepContext(id, turn, step, entering, session),
                            () -> new PreStepDecision.Enter(entering));
                } catch (Exception e) {
                    throw new AgentLoopException("pre-step hook 失败", e);
                }
                if (decision instanceof PreStepDecision.Reject) {
                    // 被拒的 turn 以 Blocked 结束：不写 step/start，不消耗模型调用
                    reason = new TurnEndReason.Blocked();
                    break;
                }
                var enteringMessages = ((PreStepDecision.Enter) decision).messages();

                phase = new Running(turn, step, false);
                session.append(new SessionEventStepStart(session.seq(), turn, step));

                for (var msg : enteringMessages) {
                    session.append(new SessionEventUserMessage(
                            session.seq(), msg, new SurfaceOp.Append()
                    ));
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
                reason = new TurnEndReason.Error(new LlmFailure(e.getMessage(), FAILURE_CODE_UNKNOWN));
            }
            throw e;
        } catch (RuntimeException e) {
            // 非预期异常也必须落 Error 结束原因，否则 finally 会错记为 Completed
            if (reason == null) {
                var message = e.getMessage() != null ? e.getMessage() : e.toString();
                reason = new TurnEndReason.Error(new LlmFailure(message, FAILURE_CODE_UNKNOWN));
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
        var requestCtx = new RequestHook.RequestContext(id, turn, step);

        BlockAssembler assembler;
        int attempt = 0;
        while (true) {
            GenerateOptions request;
            try {
                request = hooks.dispatchRequest(requestCtx, () -> buildRequest(provider, model));
            } catch (Exception e) {
                throw new AgentLoopException("构造模型请求失败", e);
            }

            try {
                assembler = streamOnce(turn, step, request);
                break;
            } catch (StepLlmException e) {
                var action = dispatchRequestError(turn, step, e.failure());
                if (action instanceof RequestErrorAction.Retry) {
                    if (++attempt >= MAX_REQUEST_ATTEMPTS_PER_STEP) {
                        throw new AgentLoopException(
                                "step 请求重试超过循环层上限 " + MAX_REQUEST_ATTEMPTS_PER_STEP, e);
                    }
                    logger.warn("Agent {} turn {} step {} 请求失败（{}），第 {} 次重试",
                            id, turn, step, e.failure().code(), attempt);
                    continue;
                }
                throw new AgentLoopException("LLM 调用失败: " + e.failure().message(), e);
            }
        }

        return finalizeStep(turn, step, provider, model, assembler);
    }

    /** 内置 request 行为：从 session 日志派生消息并组装默认请求。 */
    private GenerateOptions buildRequest(String provider, String model) {
        var messages = session.deriveMessages();
        var assembly = systemPrompt.assemble();
        var system = SystemPromptImpl.renderPrompt(assembly);
        var tools = assembly.tools().isEmpty() ? null : assembly.tools();
        
        // 从 AgentOptions 获取推理配置
        var reasoningEnabled = options.isReasoningEnabled();
        
        return new GenerateOptions(
                provider, 
                model, 
                messages, 
                system, 
                tools,
                null,  // temperature
                // 保持 null 语义，由模型决定默认输出长度，避免截断推理模型长输出
                options.maxOutputTokens(),  // maxTokens - 可能为 null
                null,  // stop
                null,  // purpose
                reasoningEnabled  // reasoningEnabled
        );
    }

    /** request-error 决策链：无人接管恢复权时保持失败。 */
    private RequestErrorAction dispatchRequestError(int turn, int step, LlmFailure failure)
            throws AgentLoopException {
        try {
            return hooks.dispatchRequestError(
                    new RequestErrorHook.RequestErrorContext(id, turn, step, failure),
                    () -> new RequestErrorAction.Fail());
        } catch (Exception e) {
            throw new AgentLoopException("request-error hook 失败", e);
        }
    }

    /** 执行一次流式调用并组装块；失败以 StepLlmException 携带结构化故障抛出。 */
    private BlockAssembler streamOnce(int turn, int step, GenerateOptions request)
            throws StepLlmException {
        var assembler = new BlockAssembler();
        var barrier = new CompletableFuture<Void>();
        var errorRef = new AtomicReference<Throwable>();
        // 超时/失败后关闭回调入口：迟到的 chunk 不得再写入 session，
        // 否则重试场景下两个 attempt 的 chunk 会混入同一 turn/step 日志
        var closed = new AtomicBoolean();

        llmRuntime.stream(request, new StreamCallback() {
            @Override
            public void onChunk(StreamChunk chunk) {
                if (closed.get()) {
                    return;
                }
                int seq = session.seq();
                session.append(new SessionEventAssistantChunk(seq, turn, step, chunk));
                assembler.push(chunk);
            }
            @Override
            public void onComplete() { barrier.complete(null); }
            @Override
            public void onError(Throwable err) { errorRef.set(err); barrier.completeExceptionally(err); }
        });

        // 推理模式（DeepSeek-R1 等）思考耗时长，应用 reasoningTimeout（默认 5 分钟）；
        // 普通模式用 llmTimeout（默认 60 秒）。
        // 用毫秒精度：toSeconds() 会把亚秒配置截断为 0 导致立即超时
        var timeoutMillis = (options.isReasoningEnabled()
                ? options.getReasoningTimeoutOrDefault()
                : options.getLlmTimeoutOrDefault()).toMillis();
        try {
            barrier.get(timeoutMillis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            closed.set(true);
            throw new StepLlmException(new LlmFailure("LLM 调用被中断", "INTERRUPTED"), e);
        } catch (ExecutionException e) {
            closed.set(true);
            throw StepLlmException.of(errorRef.get() != null ? errorRef.get() : e.getCause());
        } catch (TimeoutException e) {
            closed.set(true);
            var mode = options.isReasoningEnabled() ? "推理" : "LLM";
            throw new StepLlmException(
                    new LlmFailure(mode + "调用超时（" + timeoutMillis + "ms）", "TIMEOUT"), e);
        }
        if (errorRef.get() != null) {
            closed.set(true);
            throw StepLlmException.of(errorRef.get());
        }

        var finish = assembler.finish();
        if (finish instanceof FinishReason.Aborted) {
            throw new StepLlmException(new LlmFailure("LLM 流被中止", "ABORTED"));
        }
        if (finish instanceof FinishReason.Error) {
            throw new StepLlmException(new LlmFailure("LLM 异常结束", "STREAM_ERROR"));
        }
        return assembler;
    }

    /** 写入 assistant 消息、判定结束原因并执行工具调用。 */
    private TurnEndReason finalizeStep(int turn, int step, String provider, String model,
                                       BlockAssembler assembler) throws AgentLoopException {
        var assistantMsg = MessageFactory.createAssistantMessage(assembler.blocks(), provider, model);
        session.append(new SessionEventAssistantMessage(
                session.seq(), turn, step, assistantMsg,
                new SurfaceOp.Append(), assembler.usage().orElse(null)
        ));

        if (assembler.finish() instanceof FinishReason.MaxTokens) {
            return new TurnEndReason.MaxTokens();
        }

        // 检查是否有工具调用
        var toolCallBlocks = assembler.blocks().stream()
                .filter(b -> b instanceof ContentBlock.ToolCall)
                .map(b -> (ContentBlock.ToolCall) b)
                .toList();

        if (toolCallBlocks.isEmpty()) {
            return new TurnEndReason.Completed();
        }

        for (var tc : toolCallBlocks) {
            logger.debug("Executing tool call: {} for agent {}", tc.name(), id);
            session.append(new SessionEventToolCall(
                    session.seq(), turn, step, tc.id(), tc.name(), tc.arguments()
            ));

            ToolExecutionResult result;
            try {
                var args = parseJsonArgs(tc.arguments());
                result = hooks.dispatchTool(
                        new ToolExecutionHook.ToolCallContext(id, turn, step, tc.id(), tc.name(), args),
                        () -> executeToolSafely(tc.name(), args));
            } catch (IllegalArgumentException e) {
                // 参数无效：不进入执行链，直接以错误结果回给模型
                logger.warn("Tool {} got invalid arguments for agent {}", tc.name(), id, e);
                result = new ToolExecutionResult(e);
            } catch (Exception e) {
                throw new AgentLoopException("工具执行 hook 失败: " + tc.name(), e);
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

    /** 内置工具执行行为：执行工具并把非法参数转为错误结果。 */
    private ToolExecutionResult executeToolSafely(String name, Map<String, Object> args) {
        try {
            return toolRegistry.execute(name, args);
        } catch (IllegalArgumentException e) {
            logger.warn("Tool {} execution failed for agent {}", name, id, e);
            return new ToolExecutionResult(e);
        }
    }

    /** 从认领批次中提取用户消息。 */
    private static List<Message.UserMessage> userMessagesOf(List<Message> messages) {
        return messages.stream()
                .filter(m -> m instanceof Message.UserMessage)
                .map(m -> (Message.UserMessage) m)
                .toList();
    }

    /** 携带结构化故障的 step 内部异常，用于 request-error 恢复决策。 */
    private static final class StepLlmException extends Exception {

        private final LlmFailure failure;

        StepLlmException(LlmFailure failure, Throwable cause) {
            super(failure.message(), cause);
            this.failure = failure;
        }

        StepLlmException(LlmFailure failure) {
            this(failure, null);
        }

        LlmFailure failure() {
            return failure;
        }

        /** 从底层异常提取结构化故障：LlmException 携带 HTTP 状态码，其余按传输失败处理。 */
        static StepLlmException of(Throwable cause) {
            if (cause instanceof LlmException le) {
                var code = le.status() != null ? "HTTP_" + le.status() : "TRANSPORT";
                return new StepLlmException(
                        new LlmFailure(le.getMessage(), code, le.status(), null, null), le);
            }
            if (cause == null) {
                return new StepLlmException(new LlmFailure("LLM 调用失败（未知原因）", "TRANSPORT"));
            }
            var message = cause.getMessage() != null ? cause.getMessage() : cause.toString();
            return new StepLlmException(new LlmFailure(message, "TRANSPORT"), cause);
        }
    }

    /** 解析 JSON 参数为嵌套结构；非法输入抛出 IllegalArgumentException。 */
    Map<String, Object> parseJsonArgs(String json) {
        return parseJsonArgs(json, 0);
    }

    private Map<String, Object> parseJsonArgs(String json, int depth) {
        if (json == null || json.isBlank()) return Map.of();
        var parsed = dev.duo.util.JsonParser.parse(json);
        if (!(parsed instanceof Map<?, ?> m)) {
            throw new IllegalArgumentException("工具参数必须是 JSON 对象");
        }
        
        // 检测多重包装：{"arguments": "{...}"}
        if (m.size() == 1 && m.containsKey("arguments") 
                && m.get("arguments") instanceof String nested) {
            var nextDepth = depth + 1;
            logger.warn("检测到 {} 层嵌套 arguments 字符串，继续解析", nextDepth);
            if (nextDepth > 5) {
                throw new IllegalArgumentException("arguments 嵌套层数超过安全上限（5 层），请检查工具调用格式");
            }
            return parseJsonArgs(nested, nextDepth);
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