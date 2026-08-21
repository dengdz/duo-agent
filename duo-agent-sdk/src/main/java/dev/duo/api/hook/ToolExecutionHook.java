package dev.duo.api.hook;

import dev.duo.model.llm.ToolExecutionResult;
import dev.duo.model.session.SessionId;
import dev.duo.util.CallId;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 工具执行的环绕拦截点。
 * <p>
 * 内置行为是"解析参数并调用注册的工具"。监听器可实现超时、审批、审计、指标等策略：
 * 调用 {@code next.proceed()} 得到工具结果后可替换（如超时结构化错误、审批拒绝），
 * 也可直接短路返回而不执行工具。
 * </p>
 *
 * <p>链语义：先注册的 hook 在最外层；不调用 {@code next} 即拒绝执行该工具；
 * {@code proceed()} 只能调用一次。</p>
 *
 * @author zhangyl
 * @date 2026-08-18
 */
@FunctionalInterface
public interface ToolExecutionHook {

    /**
     * 环绕一次工具执行。
     *
     * @param context 不可变事实（agent、轮次、步骤、工具调用 id/名称/参数）
     * @param next 委托链
     * @return 最终写入会话日志的工具结果
     * @throws Exception hook 实现抛出的异常将向上传播并导致 step 失败
     */
    ToolExecutionResult around(ToolCallContext context, Chain next) throws Exception;

    /** tool 委托链。 */
    @FunctionalInterface
    interface Chain {

        /** 执行下游 hook（最终是内置行为：解析参数并执行工具）。重复调用抛 IllegalStateException。 */
        ToolExecutionResult proceed() throws Exception;
    }

    /** 工具调用的不可变上下文。 */
    record ToolCallContext(
            SessionId agentId,
            int turn,
            int step,
            CallId callId,
            String toolName,
            Map<String, Object> arguments,
            /** 本 turn 的取消信号：审批等等待类 hook 用它与用户应答 race。 */
            dev.duo.api.agent.CancellationSignal cancellation
    ) {
        public ToolCallContext {
            // 不用 Map.copyOf：JSON 参数可含 null 值，copyOf 会抛 NPE；
            // LinkedHashMap 保留解析顺序且容忍 null 值
            arguments = Collections.unmodifiableMap(new LinkedHashMap<>(arguments));
            if (cancellation == null) {
                throw new IllegalArgumentException("cancellation 不能为 null");
            }
        }
    }
}
