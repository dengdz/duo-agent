package dev.duo.model.deepseek;

import dev.duo.adapter.deepseek.DeepSeekAdapter;
import dev.duo.api.DuoModel;
import dev.duo.api.llm.LlmAdapter;
import dev.duo.core.model.AbstractDuoModel;

import java.time.Duration;
import java.util.Objects;

/**
 * DeepSeek 模型实现。
 * <p>
 * 使用 DeepSeek 官方 API（OpenAI 兼容格式）。既可独立用于单次推理
 * （{@link #call(String)} / {@link #stream(String)}），也可传入
 * {@code DuoAgent.builder().model(...)} 复用同一份模型配置创建多个 Agent。
 * </p>
 * <p>
 * <b>示例：</b>
 * <pre>{@code
 * DuoModel model = DeepSeekModel.builder()
 *     .apiKey(System.getenv("DEEPSEEK_API_KEY"))
 *     .model("deepseek-chat")
 *     .contextWindow(128000)
 *     .build();
 *
 * // 单次推理
 * String answer = model.call("解释什么是事件溯源");
 *
 * // 组装 Agent（复用配置）
 * DuoAgent agent = DuoAgent.builder()
 *     .model(model)
 *     .withCodeTools()
 *     .build();
 * }</pre>
 * </p>
 *
 * @author zhangyl
 * @date 2026-08-20
 */
public final class DeepSeekModel extends AbstractDuoModel {

    /** DeepSeek 官方 API 端点。 */
    public static final String DEFAULT_BASE_URL = "https://api.deepseek.com";

    private static final String API_KEY_ENV = "DEEPSEEK_API_KEY";
    private static final String API_FORMAT = "openai";

    private final String apiKey;
    private final String baseUrl;

    private DeepSeekModel(Builder builder, String apiKey) {
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
    }

    /**
     * 创建 DeepSeek 模型构建器。
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
        return new DeepSeekAdapter(apiKey, baseUrl, httpTimeout);
    }

    /**
     * DeepSeek 模型构建器。
     * <p>
     * 必填项：model。apiKey 未显式设置时回落到环境变量
     * {@code DEEPSEEK_API_KEY}（与适配器的无参构造一致），仍缺失则构建失败。
     * </p>
     */
    public static final class Builder {

        private String apiKey;
        private String baseUrl = DEFAULT_BASE_URL;
        private String modelName;
        private String systemPrompt;
        private Integer contextWindow;
        private Integer maxOutputTokens;
        private Double temperature;
        private boolean reasoningEnabled = false;
        private Duration reasoningTimeout = Duration.ofMinutes(5);

        private Builder() {
        }

        /**
         * 设置 API 密钥（未设置时回落到环境变量 DEEPSEEK_API_KEY）。
         *
         * @param apiKey API 密钥
         * @return this
         */
        public Builder apiKey(String apiKey) {
            this.apiKey = apiKey;
            return this;
        }

        /**
         * 设置 API 端点（默认官方端点，兼容代理/自部署场景）。
         *
         * @param baseUrl API 基础 URL（尾部斜杠会被去除，避免拼接出双斜杠地址）
         * @return this
         */
        public Builder baseUrl(String baseUrl) {
            Objects.requireNonNull(baseUrl, "baseUrl 不能为 null");
            this.baseUrl = baseUrl.endsWith("/")
                    ? baseUrl.substring(0, baseUrl.length() - 1)
                    : baseUrl;
            return this;
        }

        /**
         * 设置模型名称（必填）。
         *
         * @param modelName 如 "deepseek-chat" 或 "deepseek-reasoner"
         * @return this
         */
        public Builder model(String modelName) {
            this.modelName = Objects.requireNonNull(modelName, "model 不能为 null");
            return this;
        }

        /**
         * 设置系统提示词（可选，空白视为未设置——避免空串静默覆盖内置默认提示词）。
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
         * @param tokens 上下文窗口 token 数（deepseek-chat 为 128000）
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
         * 默认不设置，由模型决定输出长度，避免截断推理模型的长输出。
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
         * 设置采样温度（可选）。
         *
         * @param temperature 采样温度，范围 [0.0, 2.0]
         * @return this
         * @throws IllegalArgumentException 如果超出范围
         */
        public Builder temperature(double temperature) {
            if (temperature < 0.0 || temperature > 2.0) {
                throw new IllegalArgumentException("temperature 必须在 [0.0, 2.0] 内，当前: " + temperature);
            }
            this.temperature = temperature;
            return this;
        }

        /**
         * 启用深度推理模式（如 deepseek-reasoner）。
         * <p>
         * 启用后 LLM 调用超时切换为 {@link #reasoningTimeout(Duration)}，
         * 响应可能包含 ReasoningDelta 思考增量。
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
            Objects.requireNonNull(timeout, "reasoningTimeout 不能为 null");
            if (timeout.isZero() || timeout.isNegative()) {
                throw new IllegalArgumentException("reasoningTimeout 必须大于 0");
            }
            this.reasoningTimeout = timeout;
            return this;
        }

        /**
         * 构建 DeepSeek 模型实例。
         *
         * @return 模型实例
         * @throws IllegalStateException 如果 model 未设置，或 apiKey 与
         *                               DEEPSEEK_API_KEY 环境变量均缺失
         */
        public DuoModel build() {
            if (modelName == null || modelName.isBlank()) {
                throw new IllegalStateException("未配置模型名称。请调用 .model(\"deepseek-chat\") 方法。");
            }
            // 局部变量解析，不回写 builder：避免凭据残留在 builder 对象上
            //（调用方可能保留 builder 引用用于日志/复用）
            var resolvedApiKey = (apiKey == null || apiKey.isBlank())
                    ? System.getenv(API_KEY_ENV) : apiKey;
            if (resolvedApiKey == null || resolvedApiKey.isBlank()) {
                throw new IllegalStateException(
                        "未设置 API Key。请调用 .apiKey(\"your-api-key\") 或设置环境变量 " + API_KEY_ENV + "。");
            }
            return new DeepSeekModel(this, resolvedApiKey);
        }
    }
}
