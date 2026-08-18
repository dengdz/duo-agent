package dev.dsh.agent;

/**
 * 可合并扩展的 Agent 创建选项。
 * <p>
 * 对应 TS 源码中的 {@code AgentOptions}。
 * </p>
 */
public record AgentOptions(
        /** 提供方路由（调用时必须有已注册的适配器）。 */
        String provider,
        /** 由所选提供方适配器解释的模型 id。 */
        String model,
        /** 每次对话模型请求的最大输出 token 数。 */
        Integer maxTokens
) {
    public AgentOptions() {
        this(null, null, null);
    }
}