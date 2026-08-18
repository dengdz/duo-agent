package dev.duo.api.hook;

import dev.duo.model.llm.GenerateOptions;
import dev.duo.model.session.SessionId;

/**
 * 模型请求构造的环绕拦截点。
 * <p>
 * 对应 TS 源码中的 {@code agent/request} waterfall：内置行为是"从 session 日志派生消息、
 * 组装 system prompt 与工具 schema，构造默认 {@link GenerateOptions}"。
 * 监听器不能改历史消息（模型可见即已记录），只能通过返回改写后的
 * {@link GenerateOptions} 调整模型、maxTokens 等请求参数。
 * 消费示例：按会话状态切换模型、注入额外的请求级参数。
 * </p>
 *
 * <p>链语义：先注册的 hook 在最外层；{@code next.proceed()} 返回内置构造的请求，
 * 包装后返回；不调用即接管（自行构造请求）；{@code proceed()} 只能调用一次。</p>
 *
 * @author zhangyl
 * @date 2026-08-18
 */
@FunctionalInterface
public interface RequestHook {

    /**
     * 环绕模型请求构造。
     *
     * @param context 不可变事实（agent、轮次、步骤）
     * @param next 委托链
     * @return 最终用于本次模型调用的请求选项
     * @throws Exception hook 实现抛出的异常将向上传播并导致 step 失败
     */
    GenerateOptions onRequest(RequestContext context, Chain next) throws Exception;

    /** request 委托链。 */
    @FunctionalInterface
    interface Chain {

        /** 执行下游 hook（最终是内置行为：组装默认请求）。重复调用抛 IllegalStateException。 */
        GenerateOptions proceed() throws Exception;
    }

    /** request 的不可变上下文。 */
    record RequestContext(
            SessionId agentId,
            int turn,
            int step
    ) {}
}
