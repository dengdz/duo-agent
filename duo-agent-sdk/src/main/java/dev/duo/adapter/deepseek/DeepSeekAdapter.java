package dev.duo.adapter.deepseek;

import dev.duo.api.llm.LlmAdapter;
import dev.duo.api.llm.StreamCallback;
import dev.duo.exception.LlmException;
import dev.duo.model.llm.GenerateOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * DeepSeek API 适配器。
 * <p>
 * 将 harness 的 {@link GenerateOptions} 序列化为 DeepSeek 聊天补全 API 请求，
 * 解析 SSE 响应并翻译为 {@link dev.duo.model.llm.StreamChunk} 协议。
 * 适配器只做单次请求并报告结构化失败（{@link LlmException#status()} 携带 HTTP 状态码）；
 * 重试等恢复策略由外层的 request-error hook（如 {@code LlmRetryHook}）实现，
 * 对应 TS 源码中"适配器暴露策略、恢复在循环扩展点执行"的分层。
 * </p>
 *
 * @author zhangyl
 * @date 2026-08-18
 */
public class DeepSeekAdapter extends LlmAdapter {

    private static final Logger logger = LoggerFactory.getLogger(DeepSeekAdapter.class);

    // HTTP 配置常量
    private static final String DEFAULT_BASE_URL = "https://api.deepseek.com";
    private static final String API_KEY_ENV = "DEEPSEEK_API_KEY";
    /** TCP 连接建立超时（仅管建连阶段，不影响响应体读取）。 */
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(60);
    /**
     * 单次请求整体超时兜底（未显式指定时使用）。JDK HttpClient 的 HttpRequest.timeout
     * 覆盖到响应体完成，对 SSE 流式响应即整段生成时长。
     * <p>
     * <b>约束：必须始终大于应用层最大超时</b>（llmTimeout / reasoningTimeout 的较大者），
     * 否则会先于应用层 barrier 掐断流式回复。经由 {@code DuoAgentBuilder} 组装时会按
     * 应用层超时自动计算并显式传入，仅默认构造路径使用本兜底值。
     * </p>
     */
    private static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofMinutes(10);
    private static final int HTTP_OK = 200;

    private final HttpClient httpClient;
    private final String baseUrl;
    private final String apiKey;
    private final Duration requestTimeout;

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
        this(apiKey, baseUrl, DEFAULT_REQUEST_TIMEOUT);
    }

    /**
     * 显式指定请求超时的构造。
     * <p>
     * 请求超时必须大于应用层最大超时（推理模式 reasoningTimeout 默认 5 分钟），
     * 否则长回复会被 HTTP 层中途掐断。由组装方按 {@code max(llmTimeout, reasoningTimeout)
     * + 余量} 计算后传入。
     * </p>
     *
     * @param apiKey DeepSeek API 密钥
     * @param baseUrl API 基地址（传 null 用官方默认）
     * @param requestTimeout 单次请求整体超时（防连接永久挂起的兜底）
     */
    public DeepSeekAdapter(String apiKey, String baseUrl, Duration requestTimeout) {
        this.apiKey = apiKey == null || apiKey.isBlank() ? null : apiKey;
        this.baseUrl = baseUrl == null || baseUrl.isBlank() ? DEFAULT_BASE_URL : baseUrl;
        this.requestTimeout = requestTimeout == null ? DEFAULT_REQUEST_TIMEOUT : requestTimeout;
        this.httpClient = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
    }

    @Override
    public void stream(GenerateOptions options, StreamCallback callback) {
        Objects.requireNonNull(options, "options must not be null");
        Objects.requireNonNull(callback, "callback must not be null");

        if (apiKey == null || apiKey.isBlank()) {
            callback.onError(new IllegalStateException(API_KEY_ENV + " 未设置"));
            return;
        }

        // 每次调用创建新的解析器实例，避免并发问题
        var parser = new DeepSeekSseParser();

        try {
            var requestBody = DeepSeekRequestBuilder.buildRequest(options);
            logger.debug("DeepSeek API 请求体:\n{}", requestBody);
            var request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/chat/completions"))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .header("Accept", "text/event-stream")
                    .timeout(requestTimeout)
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody, StandardCharsets.UTF_8))
                    .build();

            httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofLines())
                    .thenAccept(response -> {
                        if (response.statusCode() != HTTP_OK) {
                            var body = response.body().collect(Collectors.joining("\n"));
                            logger.error("DeepSeek API returned {} for model {}\n响应体: {}",
                                    response.statusCode(), options.model(), body);
                            callback.onError(new LlmException(String.format(
                                    "DeepSeek API 返回 %d (model: %s): %s",
                                    response.statusCode(), options.model(), body),
                                    response.statusCode()));
                            return;
                        }
                        try {
                            response.body().forEach(line -> parser.parseLine(line, callback));
                            parser.onStreamComplete(callback);
                        } catch (Exception e) {
                            logger.error("Error parsing SSE stream for model: {}", options.model(), e);
                            callback.onError(e);
                        }
                    })
                    .exceptionally(error -> {
                        logger.error("HTTP request failed for model: {}", options.model(), error);
                        callback.onError(new LlmException(
                                "DeepSeek 请求失败 (model: " + options.model() + "): "
                                        + error.getMessage(), error));
                        return null;
                    });
        } catch (Exception e) {
            logger.error("Failed to send HTTP request for model: {}", options.model(), e);
            callback.onError(e);
        }
    }

    private static String getEnvOrDefault(String key, String defaultValue) {
        var value = System.getenv(key);
        return value != null && !value.isBlank() ? value : defaultValue;
    }
}
