package dev.duo.core.agent;

import dev.duo.api.agent.Agent;
import dev.duo.api.agent.AgentCancelCause;
import dev.duo.api.agent.AgentHooks;
import dev.duo.api.agent.AgentOptions;
import dev.duo.api.agent.AgentStatus;
import dev.duo.api.agent.CancellationSignal;
import dev.duo.api.agent.CancelOptions;
import dev.duo.api.agent.Inbox;
import dev.duo.api.agent.InboxTarget;
import dev.duo.api.agent.PreStepDecision;
import dev.duo.api.agent.RequestErrorAction;
import dev.duo.api.agent.TurnCancelledException;
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
import dev.duo.model.llm.ToolExecution;
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
import dev.duo.model.session.TurnEndCancelCause;
import dev.duo.model.session.TurnEndReason;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
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
            /** 当前 turn 的取消信号；每个 turn 开始时换新（cancel 只命中活动 turn）。 */
            CancellationSignal signal
    ) implements Phase {}

    /** sentinel 结果固定文案：模型凭文案区分「副作用可能已发生」与「确定未执行」。 */
    private static final String ABORTED_MESSAGE = "Error: tool call aborted";
    private static final String ABORTED_BEFORE_DISPATCH_MESSAGE =
            "Error: tool call aborted before dispatch";
    /** sentinel 档位错误码（事件层结构化标记，档位语义见 ADR_004 第 4 节）。 */
    private static final String ERROR_CODE_ABORTED = "ABORTED";
    private static final String ERROR_CODE_ABORTED_BEFORE_DISPATCH = "ABORTED_BEFORE_DISPATCH";

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
    /** 当前驱动线程：cancel 的 interrupt 通道目标（虚拟线程一次性，无需清理复用）。 */
    private volatile Thread driverThread;

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
        CancellationSignal signal;
        Thread driver;
        synchronized (phaseLock) {
            // 清待办必须先于发信号：turn 边界的取消靠"新 turn claim 空"终止链
            if (!opts.keepInbox()) inbox.clear();
            if (phase instanceof Running r) {
                signal = r.signal();
                driver = driverThread;
            } else {
                // 无活动 turn：取消是 no-op，不武装后续工作
                return;
            }
        }
        // 锁外执行：abort 同步触发监听器（断连/杀进程等耗时 IO），持 phaseLock
        // 会阻塞 send/wakeDriver/status 等全部竞争者
        signal.abort(cause);
        if (driver != null) {
            driver.interrupt();
        }
        // phase 保持 Running：由驱动线程收敛置 Idle（防双驱动竞态），
        // 收敛前新 send 落入 wakeLatch 等待
    }

    @Override
    public void whenIdle() throws InterruptedException {
        while (true) {
            CompletableFuture<Void> current = activity;
            try {
                current.get();
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
            // 跟随 turn 链重启：activity 已被替换说明收敛时有新工作唤醒，
            // 继续等新活动直至静止
            if (activity == current) {
                return;
            }
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
            phase = new Running(p.lastTurn + 1, 0, new CancellationSignal());

            driverExecutor.execute(() -> {
                driverThread = Thread.currentThread();
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
            while (phase instanceof Running) {
                boolean hasMore = turn();
                if (hasMore) {
                    // 链续：当前 turn 结束但 inbox 还有待办，递增 turn 号
                    synchronized (phaseLock) {
                        int nextTurn = lastTurnOf(phase) + 1;
                        phase = new Running(nextTurn, 0, new CancellationSignal());
                    }
                } else {
                    break;
                }
            }
        } finally {
            synchronized (phaseLock) {
                int last = lastTurnOf(phase);
                phase = new Idle(last);
                driverThread = null;
                if (wakeLatch && inbox.hasPending()) {
                    wakeLatch = false;
                    wakeDriver();
                }
            }
        }
    }

    /** 执行一轮对话。返回 true 表示还有待办需要继续。 */
    private boolean turn() throws AgentLoopException {
        int turn = lastTurnOf(phase);
        logger.debug("Agent {} starting turn {}", id, turn);
        // per-turn 取消信号：cancel 精确命中"正在跑的这个 turn"，
        // 正常结束后的链续 turn 不被上一个 turn 的取消污染
        var signal = new CancellationSignal();
        synchronized (phaseLock) {
            phase = new Running(turn, 0, signal);
        }
        // 消费上一 turn 遗留的中断位：取消路径（streamOnce/工具的 interrupt 处理）
        // 按惯例重设中断位后再抛 TurnCancelledException，若链继续（keepInbox）
        // 该残留会立即毒害新 turn 的 barrier.get；驱动线程的 interrupt 源只有
        // cancel，旧 signal 的中断语义已随 turn 收尾终结
        if (Thread.interrupted()) {
            logger.debug("Agent {} turn {} 开始前消费了残留中断位", id, turn);
        }
        session.append(new SessionEventTurnStart(session.seq(), turn));

        TurnEndReason reason = null;
        var target = InboxTarget.NEXT_TURN;
        int step = 0;

        try {
            while (true) {
                signal.checkPoint();
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

                phase = new Running(turn, step, signal);
                session.append(new SessionEventStepStart(session.seq(), turn, step));

                for (var msg : enteringMessages) {
                    session.append(new SessionEventUserMessage(
                            session.seq(), msg, new SurfaceOp.Append()
                    ));
                }

                try {
                    var stepEnd = executeStep(turn, step, signal);
                    if (!(reason instanceof TurnEndReason.MaxTokens)) reason = stepEnd;
                } finally {
                    session.append(new SessionEventStepEnd(session.seq(), turn, step));
                }

                if (reason != null && inbox.nextStep().isEmpty()) break;
                target = InboxTarget.NEXT_STEP;
            }
        } catch (TurnCancelledException e) {
            // 取消是终态语义：记 Aborted 收尾，不作为失败上抛；
            // 链是否续跑由 finally 后的 hasPending 决定（keepInbox 保留的待办续跑）
            reason = new TurnEndReason.Aborted(toTurnEndCause(e.cancelCause()));
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
    private TurnEndReason executeStep(int turn, int step, CancellationSignal signal)
            throws AgentLoopException, TurnCancelledException {
        var provider = options.provider() != null ? options.provider() : DEFAULT_PROVIDER;
        var model = options.model() != null ? options.model() : DEFAULT_MODEL;
        var requestCtx = new RequestHook.RequestContext(id, turn, step);

        StreamResult streamResult;
        int attempt = 0;
        while (true) {
            GenerateOptions request;
            try {
                request = hooks.dispatchRequest(requestCtx, () -> buildRequest(provider, model));
            } catch (TurnCancelledException e) {
                throw e;
            } catch (Exception e) {
                throw new AgentLoopException("构造模型请求失败", e);
            }

            try {
                streamResult = streamOnce(turn, step, request, signal);
                break;
            } catch (TurnCancelledException e) {
                // 取消先于 request-error 分发：取消是终态，不得被恢复链转为重试
                throw e;
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

        return finalizeStep(turn, step, provider, model,
                streamResult.assembler(), streamResult.chunkSeqs(), signal);
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

    /** 一次成功流式调用的产物：组装器 + 构成本次消息的全部 chunk 事件 seq（供回链）。 */
    private record StreamResult(BlockAssembler assembler, List<Integer> chunkSeqs) {
    }

    /** 执行一次流式调用并组装块；取消抛 TurnCancelledException，失败以 StepLlmException 携带结构化故障抛出。 */
    private StreamResult streamOnce(int turn, int step, GenerateOptions request,
                                     CancellationSignal signal)
            throws StepLlmException, TurnCancelledException {
        var assembler = new BlockAssembler();
        var barrier = new CompletableFuture<Void>();
        var errorRef = new AtomicReference<Throwable>();
        // 超时/失败后关闭回调入口：迟到的 chunk 不得再写入 session，
        // 否则重试场景下两个 attempt 的 chunk 会混入同一 turn/step 日志
        var closed = new AtomicBoolean();
        // 收集本 attempt 的 chunk seq：assistant/message 通过 sourceEventSeqs 回链它们
        var chunkSeqs = new ArrayList<Integer>();

        llmRuntime.stream(request, new StreamCallback() {
            @Override
            public void onChunk(StreamChunk chunk) {
                if (closed.get()) {
                    return;
                }
                int seq = session.seq();
                session.append(new SessionEventAssistantChunk(seq, turn, step, chunk));
                chunkSeqs.add(seq);
                assembler.push(chunk);
            }
            @Override
            public void onComplete() { barrier.complete(null); }
            @Override
            public void onError(Throwable err) { errorRef.set(err); barrier.completeExceptionally(err); }
        }, signal);

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
            // 双通道定性：interrupt 唤醒后查取消信号——已取消是终态语义；
            // 无取消原因的意外中断保持可恢复失败语义（request-error 可重试）
            if (signal.isCancelled()) {
                throw new TurnCancelledException(signal.cause());
            }
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
        return new StreamResult(assembler, chunkSeqs);
    }

    /** 写入 assistant 消息、判定结束原因并执行工具调用。 */
    private TurnEndReason finalizeStep(int turn, int step, String provider, String model,
                                       BlockAssembler assembler, List<Integer> chunkSeqs,
                                       CancellationSignal signal)
            throws AgentLoopException, TurnCancelledException {
        var assistantMsg = MessageFactory.createAssistantMessage(assembler.blocks(), provider, model);
        // sourceEventSeqs 回链：完整消息可追溯到拼出它的全部 chunk 事件（token 级回放保真）
        var sourceSeqs = chunkSeqs.stream().mapToInt(Integer::intValue).toArray();
        session.append(new SessionEventAssistantMessage(
                session.seq(), turn, step, assistantMsg,
                new SurfaceOp.Append(), sourceSeqs, assembler.usage().orElse(null)
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

        for (int i = 0; i < toolCallBlocks.size(); i++) {
            var tc = toolCallBlocks.get(i);
            logger.debug("Executing tool call: {} for agent {}", tc.name(), id);
            session.append(new SessionEventToolCall(
                    session.seq(), turn, step, tc.id(), tc.name(), tc.arguments()
            ));

            // dispatch 前检查点：未启动的调用档位为 BEFORE_DISPATCH（确定无副作用）
            try {
                signal.checkPoint();
            } catch (TurnCancelledException e) {
                abortRemainingToolCalls(turn, step, toolCallBlocks, i,
                        ERROR_CODE_ABORTED_BEFORE_DISPATCH, ABORTED_BEFORE_DISPATCH_MESSAGE);
                throw e;
            }

            ToolExecutionResult result;
            try {
                var args = parseJsonArgs(tc.arguments());
                result = hooks.dispatchTool(
                        new ToolExecutionHook.ToolCallContext(
                                id, turn, step, tc.id(), tc.name(), args, signal),
                        () -> executeToolSafely(tc.name(), new ToolExecution(args, signal)));
            } catch (TurnCancelledException e) {
                // dispatch 后打断：body 可能已启动，档位为 ABORTED（副作用可能已发生）
                abortRemainingToolCalls(turn, step, toolCallBlocks, i,
                        ERROR_CODE_ABORTED, ABORTED_MESSAGE);
                throw e;
            } catch (IllegalArgumentException e) {
                // 参数无效：不进入执行链，直接以错误结果回给模型
                logger.warn("Tool {} got invalid arguments for agent {}", tc.name(), id, e);
                result = new ToolExecutionResult(e);
            } catch (Exception e) {
                throw new AgentLoopException("工具执行 hook 失败: " + tc.name(), e);
            }

            // 取消取代成功：dispatch 正常返回但取消已到——防止"取消后还把
            // 成功结果写给模型"的竞态，模型不应基于已取消的执行继续
            if (result != null && !result.isError() && signal.isCancelled()) {
                result = sentinelResult(ABORTED_MESSAGE);
            }

            appendToolResult(turn, step, tc, result, null);
        }

        // 有工具调用时，返回 null 表示 step 继续
        return null;
    }

    /**
     * 取消打断后补齐 sentinel：第 {@code from} 个调用按给定档位写入，
     * 其后全部按 BEFORE_DISPATCH 写入。
     * <p>
     * 配对是协议硬约束——assistant 消息已携带 tool_call blocks，跳过配对的
     * tool result 会让下一请求被服务端 400；此处保证每个 tool_call 事件
     * 必有配对 tool_result 事件（含 surfaceOp 投影，下一 turn 模型可见）。
     * sentinel 不注入 inbox：turn 即将终止，无需驱动 step 循环。
     * </p>
     */
    private void abortRemainingToolCalls(int turn, int step, List<ContentBlock.ToolCall> calls,
                                         int from, String firstCode, String firstMessage) {
        for (int i = from; i < calls.size(); i++) {
            var tc = calls.get(i);
            if (i > from) {
                session.append(new SessionEventToolCall(
                        session.seq(), turn, step, tc.id(), tc.name(), tc.arguments()
                ));
            }
            var code = i == from ? firstCode : ERROR_CODE_ABORTED_BEFORE_DISPATCH;
            var message = i == from ? firstMessage : ABORTED_BEFORE_DISPATCH_MESSAGE;
            appendToolResult(turn, step, tc, sentinelResult(message), code);
        }
        logger.debug("Agent {} turn {} step {} 取消打断，已为 {} 个工具调用补 sentinel（自 {} 起）",
                id, turn, step, calls.size() - from, from);
    }

    /** 写入 tool_result 事件并注入 inbox 驱动下一 step（sentinel 路径 errorCode 非 null 且不注入）。 */
    private void appendToolResult(int turn, int step, ContentBlock.ToolCall tc,
                                  ToolExecutionResult result, String errorCode) {
        var toolResultMsg = MessageFactory.createToolResultMessage(tc.id(), result.content(), result.isError());
        session.append(new SessionEventToolResult(
                session.seq(), turn, step, toolResultMsg, new SurfaceOp.Append(), errorCode
        ));
        if (errorCode == null) {
            inbox.append(InboxTarget.NEXT_STEP, toolResultMsg);
        }
    }

    /** sentinel 构造：isError 必须为 true（模型将 sentinel 理解为执行失败）。 */
    private static ToolExecutionResult sentinelResult(String message) {
        return new ToolExecutionResult(true, List.of(new ContentBlock.Text(message)));
    }

    /** AgentCancelCause → TurnEndCancelCause 的持久化映射。 */
    private static TurnEndCancelCause toTurnEndCause(AgentCancelCause cause) {
        return switch (cause) {
            case AgentCancelCause.User u -> new TurnEndCancelCause.User();
            case AgentCancelCause.Parent p -> new TurnEndCancelCause.Parent();
            case AgentCancelCause.Hook h -> new TurnEndCancelCause.Hook(h.reason());
            case AgentCancelCause.Disposed d -> new TurnEndCancelCause.Disposed();
        };
    }

    /** 内置工具执行行为：执行工具并把非法参数转为错误结果。 */
    private ToolExecutionResult executeToolSafely(String name, ToolExecution execution)
            throws TurnCancelledException {
        try {
            return toolRegistry.execute(name, execution);
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