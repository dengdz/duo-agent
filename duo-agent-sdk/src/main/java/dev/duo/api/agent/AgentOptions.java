package dev.duo.api.agent;

import java.time.Duration;

/**
 * 可合并扩展的 Agent 创建选项。
 * <p>
 * 对应 TS 源码中的 {@code AgentOptions}。
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
        Duration llmTimeout
) {
    public AgentOptions() {
        this(null, null, null, null);
    }
    
    /**
     * 获取 LLM 超时时间，如果未设置则返回默认值 60 秒。
     *
     * @return LLM 超时时间
     */
    public Duration getLlmTimeoutOrDefault() {
        return llmTimeout != null ? llmTimeout : Duration.ofSeconds(60);
    }
}