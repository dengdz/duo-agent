package dev.duo.model.anthropic;

import dev.duo.adapter.anthropic.AnthropicAdapter;
import dev.duo.api.DuoModel;
import dev.duo.api.llm.LlmAdapter;
import dev.duo.core.model.AbstractDuoModel;

import java.time.Duration;

/**
 * Anthropic Messages 协议模型。
 * <p>
 * 覆盖 Anthropic 官方端点及兼容实现——智谱 GLM 的 Anthropic 兼容端点
 * （{@code https://open.bigmodel.cn/api/anthropic}）可直接使用现有 API Key 接入。
 * 协议差异（顶层 system、必填 max_tokens、thinking 预算、tool_result 回注结构）
 * 全部在适配器层处理，对 Agent 层透明。
 * </p>
 * <p>
 * <b>示例：</b>
 * <pre>{@code
 * DuoModel glm = AnthropicModel.builder()
 *     .baseUrl("https://open.bigmodel.cn/api/anthropic")
 *     .apiKey(System.getenv("ZHIPU_API_KEY"))
 *     .model("glm-4.6")
 *     .contextWindow(200000)
 *     .enableReasoning(true)
 *     .build();
 * }</pre>
 * </p>
 * <p>
 * <b>线程安全</b>：实例线程安全，可被多线程共享；底层适配器按工厂语义创建。
 * </p>
 *
 * @author zhangyl
 * @date 2026-08-21
 */
public final class AnthropicModel extends AbstractDuoModel {

    /** Anthropic 协议标识（同时作为适配器路由的 provider 键）。 */
    public static final String API_FORMAT = "anthropic";

    private static final String API_KEY_ENV = "ANTHROPIC_API_KEY";

    /** 扩展思考预算的默认 token 数（enableReasoning(true) 时生效）。 */
    private static final long DEFAULT_THINKING_BUDGET_TOKENS = 10240L;

    private final String apiKey;
    private final String baseUrl;
    private final String anthropicVersion;
    private final Long thinkingBudgetTokens;

    private AnthropicModel(Builder builder, String apiKey) {
        super(new Config(
                builder.modelName,
                builder.systemPrompt,
                builder.contextWindow,
                builder.maxOutputTokens,
                builder.temperature,
                builder.reasoningEnabled,
                builder.reasoningTimeout
        ));
        this.apiKey = apiKey;
        this.baseUrl = builder.baseUrl;
        this.anthropicVersion = builder.anthropicVersion;
        this.thinkingBudgetTokens = builder.reasoningEnabled
                ? builder.thinkingBudgetTokens
                : null;
    }

    /**
     * 创建 Anthropic 模型构建器。
     *
     * @return 构建器
     */
    public static Builder builder() {
        return new Builder();
    }

    @Override
    public String getApiFormat() {
        return API_FORMAT;
    }

    @Override
    protected LlmAdapter newAdapter(Duration httpTimeout) {
        return new AnthropicAdapter(apiKey, baseUrl, httpTimeout, anthropicVersion,
                null, thinkingBudgetTokens);
    }

    /**
     * Anthropic 模型构建器。
     * <p>
     * 必填项：model。baseUrl 默认 Anthropic 官方端点（接智谱等兼容端点时显式设置）；
     * apiKey 未显式设置时回落环境变量 {@code ANTHROPIC_API_KEY}。
     * </p>
     */
    public static final class Builder {

        private String apiKey;
        private String baseUrl = AnthropicAdapter.DEFAULT_BASE_URL;
        private String modelName;
        private String systemPrompt;
        private Integer contextWindow;
        private Integer maxOutputTokens;
        private Double temperature;
        private boolean reasoningEnabled = false;
        private Duration reasoningTimeout = Duration.ofMinutes(5);
        private String anthropicVersion = AnthropicAdapter.DEFAULT_ANTHROPIC_VERSION;
        private long thinkingBudgetTokens = DEFAULT_THINKING_BUDGET_TOKENS;

        private Builder() {
        }

        /**
         * 设置 API 密钥（未设置时回落环境变量 ANTHROPIC_API_KEY）。
         *
         * @param apiKey API 密钥
         * @return this
         */
        public Builder apiKey(String apiKey) {
            this.apiKey = apiKey;
            return this;
        }

        /**
         * 设置 API 端点（默认 Anthropic 官方；兼容端点如智谱
         * {@code https://open.bigmodel.cn/api/anthropic}；尾部斜杠会被去除）。
         *
         * @param baseUrl API 基础 URL
         * @return this
         */
        public Builder baseUrl(String baseUrl) {
            this.baseUrl = baseUrl == null || baseUrl.isBlank()
                    ? AnthropicAdapter.DEFAULT_BASE_URL
                    : (baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl);
            return this;
        }

        /**
         * 设置模型名称（必填）。
         *
         * @param modelName 如 "claude-sonnet-4-5"、智谱 "glm-4.6"
         * @return this
         */
        public Builder model(String modelName) {
            this.modelName = modelName;
            return this;
        }

        /**
         * 设置系统提示词（可选，空白视为未设置）。
         * <p>
         * 经 Messages 协议的顶层 system 参数发送。Agent 组装时的优先级：
         * Agent 显式 systemPrompt &gt; 本值 &gt; 内置默认。
         * </p>
         *
         * @param systemPrompt 系统提示词
         * @return this
         */
        public Builder systemPrompt(String systemPrompt) {
            this.systemPrompt = (systemPrompt == null || systemPrompt.isBlank()) ? null : systemPrompt;
            return this;
        }

        /**
         * 设置上下文窗口大小（可选）。
         *
         * @param tokens 上下文窗口 token 数
         * @return this
         * @throws IllegalArgumentException 如果 tokens 非正
         */
        public Builder contextWindow(int tokens) {
            if (tokens <= 0) {
                throw new IllegalArgumentException("contextWindow 必须大于 0");
            }
            this.contextWindow = tokens;
            return this;
        }

        /**
         * 设置单次响应的最大输出 token 数（可选）。
         * <p>
         * Messages 协议 max_tokens 必填，未设置时适配器按协议兜底 8192。
         * </p>
         *
         * @param tokens 最大输出 token 数
         * @return this
         * @throws IllegalArgumentException 如果 tokens 非正
         */
        public Builder maxOutputTokens(int tokens) {
            if (tokens <= 0) {
                throw new IllegalArgumentException("maxOutputTokens 必须大于 0");
            }
            this.maxOutputTokens = tokens;
            return this;
        }

        /**
         * 设置采样温度（可选；启用思考时不发送——协议要求思考模式 temperature 固定）。
         *
         * @param temperature 采样温度，范围 [0.0, 2.0]
         * @return this
         * @throws IllegalArgumentException 如果超出范围
         */
        public Builder temperature(double temperature) {
            if (Double.isNaN(temperature) || temperature < 0.0 || temperature > 2.0) {
                throw new IllegalArgumentException("temperature 必须在 [0.0, 2.0] 内，当前: " + temperature);
            }
            this.temperature = temperature;
            return this;
        }

        /**
         * 启用深度推理模式（可选）。
         * <p>
         * 启用后请求携带 {@code thinking: {type: "enabled", budget_tokens: N}}
         * （预算经 {@link #thinkingBudgetTokens(long)} 配置），思考增量以
         * ReasoningDelta 透出；LLM 调用超时切换为 {@link #reasoningTimeout(Duration)}。
         * </p>
         *
         * @param enable true 启用（默认 false）
         * @return this
         */
        public Builder enableReasoning(boolean enable) {
            this.reasoningEnabled = enable;
            return this;
        }

        /**
         * 设置推理模式超时（仅在 enableReasoning(true) 时生效）。
         *
         * @param timeout 推理超时（默认 5 分钟）
         * @return this
         * @throws IllegalArgumentException 如果 timeout 非正
         */
        public Builder reasoningTimeout(Duration timeout) {
            if (timeout == null || timeout.isZero() || timeout.isNegative()) {
                throw new IllegalArgumentException("reasoningTimeout 必须大于 0");
            }
            this.reasoningTimeout = timeout;
            return this;
        }

        /**
         * 设置 anthropic-version 请求头（可选，默认 "2023-06-01"）。
         *
         * @param version 协议版本标识
         * @return this
         */
        public Builder anthropicVersion(String version) {
            this.anthropicVersion = version;
            return this;
        }

        /**
         * 设置扩展思考的 token 预算（仅在 enableReasoning(true) 时生效，默认 10240）。
         * <p>
         * token 预算与 {@link #reasoningTimeout(Duration)}（时间上限）是两个维度，
         * 分别约束思考的量与时长。
         * </p>
         *
         * @param tokens 思考预算 token 数
         * @return this
         * @throws IllegalArgumentException 如果 tokens 非正
         */
        public Builder thinkingBudgetTokens(long tokens) {
            if (tokens <= 0) {
                throw new IllegalArgumentException("thinkingBudgetTokens 必须大于 0");
            }
            this.thinkingBudgetTokens = tokens;
            return this;
        }

        /**
         * 构建 Anthropic 模型实例。
         *
         * @return 模型实例
         * @throws IllegalStateException 如果 model 未设置，或 apiKey 与
         *                               ANTHROPIC_API_KEY 环境变量均缺失
         */
        public DuoModel build() {
            if (modelName == null || modelName.isBlank()) {
                throw new IllegalStateException("未配置模型名称。请调用 .model(\"...\") 方法。");
            }
            // 局部变量解析，不回写 builder：避免凭据残留在 builder 对象上
            var resolvedApiKey = (apiKey == null || apiKey.isBlank())
                    ? System.getenv(API_KEY_ENV) : apiKey;
            if (resolvedApiKey == null || resolvedApiKey.isBlank()) {
                throw new IllegalStateException(
                        "未设置 API Key。请调用 .apiKey(\"your-api-key\") 或设置环境变量 " + API_KEY_ENV + "。");
            }
            return new AnthropicModel(this, resolvedApiKey);
        }
    }
}
