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
        /** API 格式（"openai" 或 "anthropic"）。 */
        String apiFormat,
        /** 提供方路由（调用时必须有已注册的适配器）。 */
        String provider,
        /** 由所选提供方适配器解释的模型 id。 */
        String model,
        /** 模型支持的上下文窗口大小（输入 + 输出的总 token 数）。 */
        Integer contextWindow,
        /** 每次对话模型请求的最大输出 token 数。 */
        Integer maxOutputTokens,
        /** 是否启用模型的深度推理能力（如 DeepSeek-R1, OpenAI O1）。 */
        Boolean reasoningEnabled,
        /** 推理模式下的超时时间（reasoningEnabled=true 时生效，默认 5 分钟）。 */
        Duration reasoningTimeout,
        /** LLM 调用超时时间（默认 60 秒）。 */
        Duration llmTimeout,
        /** 扩展点集合（null 表示无 hook，所有分发直落内置行为）。 */
        AgentHooks hooks
) {
    /**
     * Compact constructor 验证所有参数。
     */
    public AgentOptions {
        // 验证 apiFormat
        if (apiFormat != null && !apiFormat.equals("openai") && !apiFormat.equals("anthropic")) {
            throw new IllegalArgumentException("apiFormat 必须是 'openai' 或 'anthropic'，当前值: " + apiFormat);
        }
        
        if (llmTimeout != null && (llmTimeout.isZero() || llmTimeout.isNegative())) {
            throw new IllegalArgumentException("llmTimeout 必须大于 0，当前值: " + llmTimeout);
        }
        if (reasoningTimeout != null && (reasoningTimeout.isZero() || reasoningTimeout.isNegative())) {
            throw new IllegalArgumentException("reasoningTimeout 必须大于 0，当前值: " + reasoningTimeout);
        }
        // 验证 contextWindow 和 maxOutputTokens 非负
        if (contextWindow != null && contextWindow <= 0) {
            throw new IllegalArgumentException("contextWindow 必须大于 0，当前值: " + contextWindow);
        }
        if (maxOutputTokens != null && maxOutputTokens <= 0) {
            throw new IllegalArgumentException("maxOutputTokens 必须大于 0，当前值: " + maxOutputTokens);
        }
    }

    public AgentOptions() {
        this(null, null, null, null, null, null, null, null, null);
    }

    /**
     * 兼容旧代码的构造器（provider, model, maxTokens, llmTimeout）。
     * <p>
     * Medium #5: 弃用构造器现在通过 compact constructor 验证，无需单独验证。
     * </p>
     *
     * @deprecated 使用完整构造器
     * @throws IllegalArgumentException 如果 llmTimeout 非 null 且为零或负数
     */
    @Deprecated
    public AgentOptions(String provider, String model, Integer maxTokens, Duration llmTimeout) {
        this(null, provider, model, null, maxTokens, null, null, llmTimeout, null);
    }

    /**
     * 兼容旧代码的构造器（provider, model, maxTokens, llmTimeout, hooks）。
     * <p>
     * Medium #5: 弃用构造器现在通过 compact constructor 验证，无需单独验证。
     * </p>
     *
     * @deprecated 使用完整构造器
     * @throws IllegalArgumentException 如果 llmTimeout 非 null 且为零或负数
     */
    @Deprecated
    public AgentOptions(String provider, String model, Integer maxTokens, Duration llmTimeout, AgentHooks hooks) {
        this(null, provider, model, null, maxTokens, null, null, llmTimeout, hooks);
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
     * 获取推理超时时间，如果未设置则返回默认值 5 分钟。
     * <p>
     * 仅在 {@link #isReasoningEnabled()} 为 true 时由 ReactLoopAgent 应用
     * （推理模型思考耗时长）；普通模式使用 {@link #getLlmTimeoutOrDefault()}。
     * </p>
     *
     * @return 推理超时时间
     */
    public Duration getReasoningTimeoutOrDefault() {
        return reasoningTimeout != null ? reasoningTimeout : Duration.ofMinutes(5);
    }

    /**
     * 判断是否启用了推理模式。
     *
     * @return true 如果启用推理
     */
    public boolean isReasoningEnabled() {
        return Boolean.TRUE.equals(reasoningEnabled);
    }

    /**
     * 获取 maxTokens（兼容旧代码）。
     *
     * @return maxOutputTokens 或 null
     * @deprecated 请直接使用 {@link #maxOutputTokens()}
     */
    @Deprecated(since = "0.2.0", forRemoval = true)
    public Integer maxTokens() {
        return maxOutputTokens;
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
