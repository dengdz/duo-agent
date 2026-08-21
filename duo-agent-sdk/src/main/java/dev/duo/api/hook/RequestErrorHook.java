package dev.duo.api.hook;

import dev.duo.api.agent.RequestErrorAction;
import dev.duo.model.llm.LlmFailure;
import dev.duo.model.session.SessionId;

/**
 * 模型请求失败的恢复决策拦截点。
 * <p>
 * 内置行为是"保持失败"
 * （返回 {@link RequestErrorAction.Fail}，step 以失败结束）。
 * 拥有恢复权的监听器（如重试、上下文溢出压缩）直接返回 {@link RequestErrorAction.Retry}
 * 而不调用 {@code next()}；选择 Retry 时循环会重新派生消息并重新构造请求。
 * 消费示例：指数退避重试、context-overflow 时先剪枝再重试。
 * </p>
 *
 * <p>链语义：先注册的 hook 在最外层；{@code next.proceed()} 委托（内置为 Fail）；
 * 不调用即接管恢复权；{@code proceed()} 只能调用一次。</p>
 *
 * @author zhangyl
 * @date 2026-08-18
 */
@FunctionalInterface
public interface RequestErrorHook {

    /**
     * 对失败的模型请求给出恢复动作。
     *
     * @param context 不可变事实（agent、轮次、步骤、失败信息）
     * @param next 委托链
     * @return 恢复动作
     * @throws Exception hook 实现抛出的异常将向上传播并导致 step 失败
     */
    RequestErrorAction onRequestError(RequestErrorContext context, Chain next) throws Exception;

    /** request-error 委托链。 */
    @FunctionalInterface
    interface Chain {

        /** 执行下游 hook（最终是内置行为：保持失败）。重复调用抛 IllegalStateException。 */
        RequestErrorAction proceed() throws Exception;
    }

    /** request-error 的不可变上下文。 */
    record RequestErrorContext(
            SessionId agentId,
            int turn,
            int step,
            LlmFailure failure
    ) {}
}
