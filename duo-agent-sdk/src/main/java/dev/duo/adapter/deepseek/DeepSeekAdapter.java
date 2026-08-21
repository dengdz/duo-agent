package dev.duo.adapter.deepseek;

import dev.duo.adapter.openai.ChatCompletionsAdapter;
import dev.duo.api.llm.LlmAdapter;
import dev.duo.api.llm.StreamCallback;
import dev.duo.model.llm.GenerateOptions;

import java.time.Duration;

/**
 * DeepSeek API 适配器。
 * <p>
 * 0.3.0 起为 {@link ChatCompletionsAdapter} 的 DeepSeek 预设薄壳：
 * DeepSeek 端点即 Chat Completions 协议，仅多出流式思考字段
 * {@code reasoning_content} 与官方默认端点。协议实现与解析在
 * {@code dev.duo.adapter.openai} 包维护，本类保留 0.2.0 公开构造与语义不变。
 * 适配器只做单次请求并报告结构化失败；重试等恢复策略由外层
 * request-error hook（如 {@code LlmRetryHook}）实现。
 * </p>
 *
 * @author zhangyl
 * @date 2026-08-18
 */
public class DeepSeekAdapter extends LlmAdapter {

    /** DeepSeek 官方 API 端点。 */
    private static final String DEFAULT_BASE_URL = "https://api.deepseek.com";

    private static final String API_KEY_ENV = "DEEPSEEK_API_KEY";

    /** DeepSeek 系模型流式思考的响应字段名。 */
    private static final String REASONING_CONTENT_FIELD = "reasoning_content";

    /** 委托的协议实现（承载全部请求/解析逻辑）。 */
    private final ChatCompletionsAdapter delegate;

    /** 从 DEEPSEEK_API_KEY 环境变量读取密钥的便捷构造。 */
    public DeepSeekAdapter() {
        this(System.getenv(API_KEY_ENV), getEnvOrDefault("DEEPSEEK_BASE_URL", DEFAULT_BASE_URL));
    }

    /**
     * 显式指定密钥的构造（编程接入优先使用；密钥只经内存传递，不做日志输出）。
     *
     * @param apiKey DeepSeek API 密钥
     * @param baseUrl API 基地址（传 null 用官方默认）
     */
    public DeepSeekAdapter(String apiKey, String baseUrl) {
        this(apiKey, baseUrl, null);
    }

    /**
     * 显式指定请求超时的构造。
     * <p>
     * 请求超时必须大于应用层最大超时（推理模式 reasoningTimeout 默认 5 分钟），
     * 否则长回复会被 HTTP 层中途掐断。由组装方按 {@code max(llmTimeout, reasoningTimeout)
     * + 余量} 计算后传入；null 时由协议层使用兜底默认。
     * </p>
     *
     * @param apiKey DeepSeek API 密钥
     * @param baseUrl API 基地址（传 null 用官方默认）
     * @param requestTimeout 单次请求整体超时（防连接永久挂起的兜底；null 用协议层默认）
     */
    public DeepSeekAdapter(String apiKey, String baseUrl, Duration requestTimeout) {
        var resolvedBaseUrl = baseUrl == null || baseUrl.isBlank() ? DEFAULT_BASE_URL : baseUrl;
        this.delegate = new ChatCompletionsAdapter(apiKey, resolvedBaseUrl, requestTimeout,
                REASONING_CONTENT_FIELD);
    }

    @Override
    public void stream(GenerateOptions options, StreamCallback callback) {
        delegate.stream(options, callback);
    }

    private static String getEnvOrDefault(String key, String defaultValue) {
        var value = System.getenv(key);
        return value != null && !value.isBlank() ? value : defaultValue;
    }
}
