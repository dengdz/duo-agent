package dev.duo.api.agent;

import java.time.Duration;

/**
 * 可合并扩展的 Agent 创建选项。
 * <p>
 * 对应 TS 源码中的 {@code AgentOptions}。hook 集合在创建时组装、随 Agent 生命周期存活。
 * </p>
 *
 * @author zhangyl
 * @date 2026-08-18
 */
public record AgentOptions(
        /** 提供方路由（调用时必须有已注册的适配器）。 */
        String provider,
        /** 由所选提供方适配器解释的模型 id。 */
        String model,
        /** 每次对话模型请求的最大输出 token 数。 */
        Integer maxTokens,
        /** LLM 调用超时时间（默认 60 秒）。 */
        Duration llmTimeout,
        /** 扩展点集合（null 表示无 hook，所有分发直落内置行为）。 */
        AgentHooks hooks
) {
    public AgentOptions() {
        this(null, null, null, null, null);
    }

    public AgentOptions(String provider, String model, Integer maxTokens, Duration llmTimeout) {
        this(provider, model, maxTokens, llmTimeout, null);
    }

    /**
     * 获取 LLM 超时时间，如果未设置则返回默认值 60 秒。
     *
     * @return LLM 超时时间
     */
    public Duration getLlmTimeoutOrDefault() {
        return llmTimeout != null ? llmTimeout : Duration.ofSeconds(60);
    }

    /**
     * 获取 hook 集合，如果未设置则返回空集合。
     *
     * @return 非空 hook 集合
     */
    public AgentHooks getHooksOrDefault() {
        return hooks != null ? hooks : AgentHooks.empty();
    }
}
