package dev.duo.model.openai;

import dev.duo.adapter.openai.ChatCompletionsAdapter;
import dev.duo.api.DuoModel;
import dev.duo.api.llm.LlmAdapter;
import dev.duo.core.model.AbstractDuoModel;

import java.time.Duration;

/**
 * Chat Completions 协议通用模型。
 * <p>
 * 覆盖一切 OpenAI Chat Completions 兼容端点（DeepSeek、Kimi、通义、OpenAI、
 * Ollama、vLLM 等）。端点与鉴权均为配置值：{@code baseUrl} 必填；
 * {@code apiKey} 可选（null 时不发 Authorization 头，适配本地无鉴权部署）；
 * 流式思考字段名经 {@code reasoningContentField} 参数化（DeepSeek/Qwen 系为
 * {@code "reasoning_content"}，标准端点不设置即不解析思考流）。
 * </p>
 * <p>
 * <b>示例：</b>
 * <pre>{@code
 * // Ollama 本地部署（无鉴权）
 * DuoModel local = ChatCompletionsModel.builder()
 *     .baseUrl("http://localhost:11434/v1")
 *     .model("qwen3:32b")
 *     .build();
 *
 * // 接任意 OpenAI 兼容云端端点
 * DuoModel cloud = ChatCompletionsModel.builder()
 *     .baseUrl("https://api.moonshot.cn/v1")
 *     .apiKey(System.getenv("MOONSHOT_API_KEY"))
 *     .model("kimi-latest")
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
public final class ChatCompletionsModel extends AbstractDuoModel {

    /** Chat Completions 协议标识（同时作为适配器路由的 provider 键）。 */
    public static final String API_FORMAT = "openai";

    private final String apiKey;
    private final String baseUrl;
    private final String reasoningContentField;

    private ChatCompletionsModel(Builder builder, String apiKey) {
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
        this.reasoningContentField = builder.reasoningContentField;
    }

    /**
     * 创建通用模型构建器。
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
        return new ChatCompletionsAdapter(apiKey, baseUrl, httpTimeout, reasoningContentField);
    }

    /**
     * Chat Completions 通用模型构建器。
     * <p>
     * 必填项：baseUrl、model。apiKey 可选（本地无鉴权端点不设置）；
     * reasoningContentField 可选（端点透出流式思考时设为
     * {@code "reasoning_content"}，以各厂商文档为准）。
     * </p>
     */
    public static final class Builder {

        private String baseUrl;
        private String apiKey;
        private String modelName;
        private String reasoningContentField;
        private String systemPrompt;
        private Integer contextWindow;
        private Integer maxOutputTokens;
        private Double temperature;
        private boolean reasoningEnabled = false;
        private Duration reasoningTimeout = Duration.ofMinutes(5);

        private Builder() {
        }

        /**
         * 设置 API 端点（必填，含版本前缀）。
         * <p>
         * 如 {@code https://api.moonshot.cn/v1}、Ollama {@code http://localhost:11434/v1}；
         * 尾部斜杠会被去除。
         * </p>
         *
         * @param baseUrl API 基础 URL
         * @return this
         */
        public Builder baseUrl(String baseUrl) {
            this.baseUrl = baseUrl == null || baseUrl.isBlank()
                    ? null
                    : (baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl);
            return this;
        }

        /**
         * 设置 API 密钥（可选；null 时不发 Authorization 头，适配本地无鉴权部署）。
         *
         * @param apiKey API 密钥
         * @return this
         */
        public Builder apiKey(String apiKey) {
            this.apiKey = apiKey;
            return this;
        }

        /**
         * 设置模型名称（必填）。
         *
         * @param modelName 模型名称（由端点决定，如 "kimi-latest"、"qwen3:32b"）
         * @return this
         */
        public Builder model(String modelName) {
            this.modelName = modelName;
            return this;
        }

        /**
         * 设置流式思考的响应字段名（可选；null 表示端点不透出流式思考）。
         * <p>
         * DeepSeek/Qwen 系为 {@code "reasoning_content"}，设置后思考增量以
         * {@code ReasoningDelta} 透出；标准端点不设置即不解析。
         * 以各厂商文档为准。
         * </p>
         *
         * @param fieldName 思考增量的响应字段名
         * @return this
         */
        public Builder reasoningContentField(String fieldName) {
            this.reasoningContentField = fieldName;
            return this;
        }

        /**
         * 设置系统提示词（可选，空白视为未设置）。
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
         * 设置单次响应的最大输出 token 数（可选，默认由模型决定）。
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
         * 启用后 LLM 调用超时切换为 {@link #reasoningTimeout(Duration)}，
         * 响应可能包含 ReasoningDelta 思考增量（需端点配合
         * {@link #reasoningContentField(String)} 设置）。
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
         * 构建模型实例。
         *
         * @return 模型实例
         * @throws IllegalStateException 如果 baseUrl 或 model 未设置
         */
        public DuoModel build() {
            if (baseUrl == null || baseUrl.isBlank()) {
                throw new IllegalStateException(
                        "未设置 baseUrl。Chat Completions 兼容端点无官方默认值，请显式设置（如 http://localhost:11434/v1）。");
            }
            if (modelName == null || modelName.isBlank()) {
                throw new IllegalStateException("未配置模型名称。请调用 .model(\"...\") 方法。");
            }
            // apiKey 可选（本地无鉴权端点），不回落环境变量——通用端点无统一约定
            var resolvedApiKey = (apiKey == null || apiKey.isBlank()) ? null : apiKey;
            return new ChatCompletionsModel(this, resolvedApiKey);
        }
    }
}
