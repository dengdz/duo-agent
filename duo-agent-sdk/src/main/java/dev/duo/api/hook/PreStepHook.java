package dev.duo.api.hook;

import dev.duo.api.agent.PreStepDecision;
import dev.duo.model.llm.Message;
import dev.duo.model.session.SessionId;

import java.util.List;

/**
 * step 进入前的决策拦截点。
 * <p>
 * 对应 TS 源码中的 {@code agent/pre-step} waterfall：内置行为是"以已认领的消息原样进入 step"。
 * 监听器可改写进入的消息（返回新的 {@link PreStepDecision.Enter}），或直接拒绝
 * （返回 {@link PreStepDecision.Reject}，被拒的 turn 以 Blocked 结束且不消耗模型调用）。
 * 消费示例：上下文压缩在压力超限时改写消息批次。
 * </p>
 *
 * <p>链语义：先注册的 hook 在最外层；调用 {@code next.proceed()} 委托下游，
 * 不调用即接管/否决（合法且被设计的短路权）；{@code proceed()} 只能调用一次。</p>
 *
 * @author zhangyl
 * @date 2026-08-18
 */
@FunctionalInterface
public interface PreStepHook {

    /**
     * 决定本 step 是否进入、以哪些消息进入。
     *
     * @param context 不可变事实（agent、轮次、步骤、已认领消息）
     * @param next 委托链
     * @return step 决策
     * @throws Exception hook 实现抛出的异常将向上传播并导致 step 失败
     */
    PreStepDecision decide(PreStepContext context, Chain next) throws Exception;

    /** pre-step 委托链。 */
    @FunctionalInterface
    interface Chain {

        /** 执行下游 hook（最终是内置行为：原样进入）。重复调用抛 IllegalStateException。 */
        PreStepDecision proceed() throws Exception;
    }

    /** pre-step 的不可变上下文。 */
    record PreStepContext(
            SessionId agentId,
            int turn,
            int step,
            List<Message.UserMessage> messages
    ) {
        public PreStepContext {
            messages = List.copyOf(messages);
        }
    }
}
