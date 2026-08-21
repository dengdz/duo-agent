package dev.duo.model.openai;

import dev.duo.adapter.openai.ResponsesAdapter;
import dev.duo.api.DuoModel;
import dev.duo.api.llm.LlmAdapter;
import dev.duo.core.model.AbstractDuoModel;

import java.time.Duration;
import java.util.Set;

/**
 * OpenAI Responses 协议模型。
 * <p>
 * 覆盖 OpenAI 官方端点——gpt-5 / o 系列等新模型能力（reasoning effort 等）
 * 只在 Responses API 提供，Chat Completions 已进入维护态。协议差异
 * （顶层 instructions、input items、平铺工具定义、reasoning effort）
 * 全部在适配器层处理，对 Agent 层透明。
 * </p>
 * <p>
 * <b>示例：</b>
 * <pre>{@code
 * DuoModel gpt = ResponsesModel.builder()
 *     .apiKey(System.getenv("OPENAI_API_KEY"))
 *     .model("gpt-5.2")
 *     .enableReasoning(true)
 *     .reasoningEffort("high")
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
public final class ResponsesModel extends AbstractDuoModel {

    /** Responses 协议标识（同时作为适配器路由的 provider 键）。 */
    public static final String API_FORMAT = "responses";

    private static final String API_KEY_ENV = "OPENAI_API_KEY";

    /** reasoning effort 的默认级别（enableReasoning(true) 时生效）。 */
    private static final String DEFAULT_REASONING_EFFORT = "medium";

    /** reasoning effort 的合法取值集（以 OpenAI 官方文档为准）。 */
    private static final Set<String> REASONING_EFFORTS = Set.of("minimal", "low", "medium", "high");

    private final String apiKey;
    private final String baseUrl;
    private final String reasoningEffort;

    private ResponsesModel(Builder builder, String apiKey) {
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
        this.reasoningEffort = builder.reasoningEnabled ? builder.reasoningEffort : null;
    }

    /**
     * 创建 Responses 模型构建器。
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
        return new ResponsesAdapter(apiKey, baseUrl, httpTimeout, reasoningEffort);
    }

    /**
     * Responses 模型构建器。
     * <p>
     * 必填项：model。baseUrl 默认 OpenAI 官方端点；apiKey 未显式设置时回落
     * 环境变量 {@code OPENAI_API_KEY}。
     * </p>
     */
    public static final class Builder {

        private String apiKey;
        private String baseUrl = ResponsesAdapter.DEFAULT_BASE_URL;
        private String modelName;
        private String systemPrompt;
        private Integer contextWindow;
        private Integer maxOutputTokens;
        private Double temperature;
        private boolean reasoningEnabled = false;
        private Duration reasoningTimeout = Duration.ofMinutes(5);
        private String reasoningEffort = DEFAULT_REASONING_EFFORT;

        private Builder() {
        }

        /**
         * 设置 API 密钥（未设置时回落环境变量 OPENAI_API_KEY）。
         *
         * @param apiKey API 密钥
         * @return this
         */
        public Builder apiKey(String apiKey) {
            this.apiKey = apiKey;
            return this;
        }

        /**
         * 设置 API 端点（默认 OpenAI 官方；兼容端点可覆盖；尾部斜杠会被去除）。
         *
         * @param baseUrl API 基础 URL
         * @return this
         */
        public Builder baseUrl(String baseUrl) {
            this.baseUrl = baseUrl == null || baseUrl.isBlank()
                    ? ResponsesAdapter.DEFAULT_BASE_URL
                    : (baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl);
            return this;
        }

        /**
         * 设置模型名称（必填，如 "gpt-5.2"、"o4-mini"）。
         *
         * @param modelName 模型名称
         * @return this
         */
        public Builder model(String modelName) {
            this.modelName = modelName;
            return this;
        }

        /**
         * 设置系统提示词（可选，空白视为未设置；经顶层 instructions 参数发送）。
         * <p>
         * Agent 组装时的优先级：Agent 显式 systemPrompt &gt; 本值 &gt; 内置默认。
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
         * 设置单次响应的最大输出 token 数（可选，映射 max_output_tokens）。
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
         * 设置采样温度（可选）。
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
         * 启用后请求携带 {@code reasoning: {effort}}（级别经
         * {@link #reasoningEffort(String)} 配置），思考摘要增量以 ReasoningDelta
         * 透出（依赖端点生成摘要）；LLM 调用超时切换为 {@link #reasoningTimeout(Duration)}。
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
         * 设置推理努力级别（仅在 enableReasoning(true) 时生效，默认 "medium"）。
         *
         * @param effort 级别（minimal / low / medium / high）
         * @return this
         * @throws IllegalArgumentException 如果取值非法
         */
        public Builder reasoningEffort(String effort) {
            if (effort == null || !REASONING_EFFORTS.contains(effort)) {
                throw new IllegalArgumentException(
                        "reasoningEffort 必须为 " + REASONING_EFFORTS + " 之一，当前: " + effort);
            }
            this.reasoningEffort = effort;
            return this;
        }

        /**
         * 构建 Responses 模型实例。
         *
         * @return 模型实例
         * @throws IllegalStateException 如果 model 未设置，或 apiKey 与
         *                               OPENAI_API_KEY 环境变量均缺失
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
            return new ResponsesModel(this, resolvedApiKey);
        }
    }
}
