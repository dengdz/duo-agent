package dev.duo.api.agent;

import dev.duo.api.hook.PreStepHook;
import dev.duo.api.hook.RequestErrorHook;
import dev.duo.api.hook.RequestHook;
import dev.duo.api.hook.ToolExecutionHook;
import dev.duo.model.llm.GenerateOptions;
import dev.duo.model.llm.ToolExecutionResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * Agent 扩展点（hook）集合：创建时组装、随 Agent 生命周期存活。
 * <p>
 * 每类 hook 独立成链，注册序即链序：先注册者在最外层。
 * 分发语义对应 TS 源码中的 waterfall：调用 {@code chain.proceed()} 委托下游
 * （最终是循环内置行为），不调用即接管/否决；{@code proceed()} 仅可调用一次。
 * hook 抛出的异常会向上传播并导致所在 step 失败（fail loud），不做静默吞没。
 * </p>
 *
 * @author zhangyl
 * @date 2026-08-18
 */
public final class AgentHooks {

    /** 共享的空集合（不可变，可安全复用）。 */
    private static final AgentHooks EMPTY = new AgentHooks(List.of(), List.of(), List.of(), List.of());

    private final List<PreStepHook> preStepHooks;
    private final List<RequestHook> requestHooks;
    private final List<RequestErrorHook> requestErrorHooks;
    private final List<ToolExecutionHook> toolHooks;

    private AgentHooks(List<PreStepHook> preStepHooks, List<RequestHook> requestHooks,
                       List<RequestErrorHook> requestErrorHooks, List<ToolExecutionHook> toolHooks) {
        this.preStepHooks = preStepHooks;
        this.requestHooks = requestHooks;
        this.requestErrorHooks = requestErrorHooks;
        this.toolHooks = toolHooks;
    }

    /** 空集合：所有分发直接落到内置行为。 */
    public static AgentHooks empty() {
        return EMPTY;
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * 分发 pre-step 决策链。
     *
     * @param context 不可变上下文
     * @param inner 内置行为：以已认领消息原样进入 step
     * @return 链上最外层 hook 的决策
     */
    public PreStepDecision dispatchPreStep(PreStepHook.PreStepContext context,
                                           Supplier<PreStepDecision> inner) throws Exception {
        if (preStepHooks.isEmpty()) {
            return inner.get();
        }
        return dispatchPreStepAt(0, context, inner);
    }

    private PreStepDecision dispatchPreStepAt(int index, PreStepHook.PreStepContext context,
                                              Supplier<PreStepDecision> inner) throws Exception {
        if (index >= preStepHooks.size()) {
            return inner.get();
        }
        var once = new AtomicBoolean();
        PreStepHook.Chain next = () -> {
            ensureFirstCall(once);
            return dispatchPreStepAt(index + 1, context, inner);
        };
        return preStepHooks.get(index).decide(context, next);
    }

    /**
     * 分发 request 构造链。
     *
     * @param context 不可变上下文
     * @param inner 内置行为：派生消息并组装默认请求
     * @return 最终请求选项
     */
    public GenerateOptions dispatchRequest(RequestHook.RequestContext context,
                                           Supplier<GenerateOptions> inner) throws Exception {
        if (requestHooks.isEmpty()) {
            return inner.get();
        }
        return dispatchRequestAt(0, context, inner);
    }

    private GenerateOptions dispatchRequestAt(int index, RequestHook.RequestContext context,
                                              Supplier<GenerateOptions> inner) throws Exception {
        if (index >= requestHooks.size()) {
            return inner.get();
        }
        var once = new AtomicBoolean();
        RequestHook.Chain next = () -> {
            ensureFirstCall(once);
            return dispatchRequestAt(index + 1, context, inner);
        };
        return requestHooks.get(index).onRequest(context, next);
    }

    /**
     * 分发 request-error 恢复决策链。
     *
     * @param context 不可变上下文
     * @param inner 内置行为：保持失败
     * @return 恢复动作
     */
    public RequestErrorAction dispatchRequestError(RequestErrorHook.RequestErrorContext context,
                                                   Supplier<RequestErrorAction> inner) throws Exception {
        if (requestErrorHooks.isEmpty()) {
            return inner.get();
        }
        return dispatchRequestErrorAt(0, context, inner);
    }

    private RequestErrorAction dispatchRequestErrorAt(int index, RequestErrorHook.RequestErrorContext context,
                                                      Supplier<RequestErrorAction> inner) throws Exception {
        if (index >= requestErrorHooks.size()) {
            return inner.get();
        }
        var once = new AtomicBoolean();
        RequestErrorHook.Chain next = () -> {
            ensureFirstCall(once);
            return dispatchRequestErrorAt(index + 1, context, inner);
        };
        return requestErrorHooks.get(index).onRequestError(context, next);
    }

    /**
     * 分发工具执行链。
     *
     * @param context 不可变上下文（含取消信号）
     * @param inner 内置行为：解析参数并执行工具
     * @return 最终工具结果
     * @throws dev.duo.api.agent.TurnCancelledException 内置行为被取消时原样穿透
     *         （由驱动循环转 sentinel，hook 不得将其转为重试或普通错误）
     */
    public ToolExecutionResult dispatchTool(ToolExecutionHook.ToolCallContext context,
                                            Callable<ToolExecutionResult> inner) throws Exception {
        if (toolHooks.isEmpty()) {
            return inner.call();
        }
        return dispatchToolAt(0, context, inner);
    }

    private ToolExecutionResult dispatchToolAt(int index, ToolExecutionHook.ToolCallContext context,
                                               Callable<ToolExecutionResult> inner) throws Exception {
        if (index >= toolHooks.size()) {
            return inner.call();
        }
        var once = new AtomicBoolean();
        ToolExecutionHook.Chain next = () -> {
            ensureFirstCall(once);
            return dispatchToolAt(index + 1, context, inner);
        };
        return toolHooks.get(index).around(context, next);
    }

    /** proceed 仅放行一次，重复调用视为编程错误。 */
    private static void ensureFirstCall(AtomicBoolean once) {
        if (!once.compareAndSet(false, true)) {
            throw new IllegalStateException("chain.proceed() 只能调用一次");
        }
    }

    /** AgentHooks 组装器。 */
    public static final class Builder {

        private final List<PreStepHook> preStepHooks = new ArrayList<>();
        private final List<RequestHook> requestHooks = new ArrayList<>();
        private final List<RequestErrorHook> requestErrorHooks = new ArrayList<>();
        private final List<ToolExecutionHook> toolHooks = new ArrayList<>();

        private Builder() {}

        /** 添加 pre-step hook（后添加者在链内侧）。 */
        public Builder addPreStepHook(PreStepHook hook) {
            preStepHooks.add(Objects.requireNonNull(hook, "hook must not be null"));
            return this;
        }

        /** 添加 request hook（后添加者在链内侧）。 */
        public Builder addRequestHook(RequestHook hook) {
            requestHooks.add(Objects.requireNonNull(hook, "hook must not be null"));
            return this;
        }

        /** 添加 request-error hook（后添加者在链内侧）。 */
        public Builder addRequestErrorHook(RequestErrorHook hook) {
            requestErrorHooks.add(Objects.requireNonNull(hook, "hook must not be null"));
            return this;
        }

        /** 添加 tool 执行 hook（后添加者在链内侧）。 */
        public Builder addToolHook(ToolExecutionHook hook) {
            toolHooks.add(Objects.requireNonNull(hook, "hook must not be null"));
            return this;
        }

        public AgentHooks build() {
            return new AgentHooks(List.copyOf(preStepHooks), List.copyOf(requestHooks),
                    List.copyOf(requestErrorHooks), List.copyOf(toolHooks));
        }
    }
}
