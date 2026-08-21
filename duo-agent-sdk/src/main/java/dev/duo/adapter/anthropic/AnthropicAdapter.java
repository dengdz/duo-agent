package dev.duo.adapter.anthropic;

import dev.duo.api.agent.CancellationSignal;
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
 * Anthropic Messages 协议适配器。
 * <p>
 * 将 {@link GenerateOptions} 序列化为 Messages 协议请求，解析 SSE 事件流并翻译为
 * {@link dev.duo.model.llm.StreamChunk}。适配器只做单次请求并报告结构化失败
 * （{@link LlmException#status()} 携带 HTTP 状态码）；重试等恢复策略由外层
 * request-error hook 实现。
 * </p>
 * <p>
 * 覆盖 Anthropic 官方端点及兼容实现（智谱 GLM 的 Anthropic 兼容端点等）。
 * 鉴权沿用 Anthropic 标准（{@code x-api-key} + {@code anthropic-version} 请求头），
 * 智谱兼容端点同时接受 Bearer 方式，本实现走标准路径。
 * </p>
 *
 * @author zhangyl
 * @date 2026-08-21
 */
public final class AnthropicAdapter extends LlmAdapter {

    private static final Logger logger = LoggerFactory.getLogger(AnthropicAdapter.class);

    /** Anthropic 官方 API 端点。 */
    public static final String DEFAULT_BASE_URL = "https://api.anthropic.com";

    /** Anthropic 协议版本请求头。 */
    public static final String DEFAULT_ANTHROPIC_VERSION = "2023-06-01";

    /** Messages 端点路径（拼接在 baseUrl 之后）。 */
    private static final String MESSAGES_PATH = "/v1/messages";

    /** TCP 连接建立超时（仅管建连阶段，不影响响应体读取）。 */
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(60);

    /**
     * 全类共享的 HttpClient：JDK HttpClient 并发安全且无 close 语义，
     * 按实例各建会造成连接池/selector 线程随 Agent 组装次数累积。
     */
    private static final HttpClient SHARED_HTTP_CLIENT =
            HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();

    /**
     * 单次请求整体超时兜底（未显式指定时使用）。
     * <p>
     * <b>约束：必须始终大于应用层最大超时</b>（llmTimeout / reasoningTimeout 的较大者），
     * 否则会先于应用层 barrier 掐断流式回复。经由 {@code DuoAgentBuilder} 组装时会按
     * 应用层超时自动计算并显式传入，仅 Model 自用路径使用本兜底值。
     * </p>
     */
    private static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofMinutes(10);

    private static final int HTTP_OK = 200;

    private final String baseUrl;
    private final String apiKey;
    private final String anthropicVersion;
    private final Duration requestTimeout;
    private final Integer defaultMaxTokens;
    private final Long thinkingBudgetTokens;

    /**
     * 显式指定连接配置的构造（编程接入优先使用；密钥只经内存传递，不做日志输出）。
     *
     * @param apiKey API 密钥
     * @param baseUrl API 基地址（null 用官方默认；尾部斜杠会被去除）
     * @param requestTimeout 单次请求整体超时（null 用默认兜底）
     * @param anthropicVersion anthropic-version 请求头（null 用 {@link #DEFAULT_ANTHROPIC_VERSION}）
     * @param defaultMaxTokens GenerateOptions.maxTokens 未配置时的 max_tokens 兜底（协议必填）
     * @param thinkingBudgetTokens 扩展思考预算（null 表示不启用思考）
     */
    public AnthropicAdapter(String apiKey, String baseUrl, Duration requestTimeout,
                            String anthropicVersion, Integer defaultMaxTokens,
                            Long thinkingBudgetTokens) {
        this.apiKey = apiKey == null || apiKey.isBlank() ? null : apiKey;
        this.baseUrl = baseUrl == null || baseUrl.isBlank()
                ? DEFAULT_BASE_URL
                : (baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl);
        this.requestTimeout = requestTimeout == null ? DEFAULT_REQUEST_TIMEOUT : requestTimeout;
        if (this.requestTimeout.isZero() || this.requestTimeout.isNegative()) {
            throw new IllegalArgumentException("requestTimeout 必须大于 0");
        }
        this.anthropicVersion = anthropicVersion == null || anthropicVersion.isBlank()
                ? DEFAULT_ANTHROPIC_VERSION : anthropicVersion;
        this.defaultMaxTokens = defaultMaxTokens == null
                ? AnthropicRequestBuilder.DEFAULT_MAX_TOKENS : defaultMaxTokens;
        this.thinkingBudgetTokens = thinkingBudgetTokens;
    }

    @Override
    public void stream(GenerateOptions options, StreamCallback callback) {
        stream(options, callback, new CancellationSignal());
    }

    @Override
    public void stream(GenerateOptions options, StreamCallback callback, CancellationSignal cancellation) {
        Objects.requireNonNull(options, "options must not be null");
        Objects.requireNonNull(callback, "callback must not be null");
        Objects.requireNonNull(cancellation, "cancellation must not be null");

        if (apiKey == null) {
            callback.onError(new IllegalStateException("Anthropic 端点鉴权必须设置 apiKey"));
            return;
        }

        // 每次调用创建新的解析器实例，避免并发问题
        var parser = new AnthropicSseParser();

        try {
            var requestBody = AnthropicRequestBuilder.buildRequest(
                    options, defaultMaxTokens, thinkingBudgetTokens);
            logger.debug("Anthropic Messages API 请求体:\n{}", requestBody);
            var request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + MESSAGES_PATH))
                    .header("x-api-key", apiKey)
                    .header("anthropic-version", anthropicVersion)
                    .header("Content-Type", "application/json")
                    .header("Accept", "text/event-stream")
                    .timeout(requestTimeout)
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody, StandardCharsets.UTF_8))
                    .build();

            var future = SHARED_HTTP_CLIENT.sendAsync(request, HttpResponse.BodyHandlers.ofLines());
            // 断连监听：cancel(true) 中止底层 exchange 并关闭响应流（正在消费的
            // forEach 抛异常汇入 onError 路径，迟到回调由调用方 closed 标志挡住）。
            // 监听器注册时若已取消会立即触发；请求终结时经 whenComplete 摘除
            var unlisten = cancellation.addListener(() -> future.cancel(true));
            future.thenAccept(response -> {
                        if (response.statusCode() != HTTP_OK) {
                            var body = response.body().collect(Collectors.joining("\n"));
                            logger.error("Anthropic API returned {} for model {}\n响应体: {}",
                                    response.statusCode(), options.model(), body);
                            callback.onError(new LlmException(String.format(
                                    "Anthropic API 返回 %d (model: %s): %s",
                                    response.statusCode(), options.model(), body),
                                    response.statusCode()));
                            return;
                        }
                        try (var lines = response.body()) {
                            lines.forEach(line -> parser.parseLine(line, callback));
                            parser.onStreamComplete(callback);
                        } catch (Exception e) {
                            logger.error("Error parsing SSE stream for model: {}", options.model(), e);
                            callback.onError(e);
                        }
                    })
                    .exceptionally(error -> {
                        logger.error("HTTP request failed for model: {}", options.model(), error);
                        callback.onError(new LlmException(
                                "Anthropic 请求失败 (model: " + options.model() + "): "
                                        + error.getMessage(), error));
                        return null;
                    })
                    .whenComplete((ignored, error) -> {
                        try {
                            unlisten.close();
                        } catch (Exception e) {
                            logger.debug("摘除断连监听失败（忽略）: {}", e.toString());
                        }
                    });
        } catch (Exception e) {
            logger.error("Failed to send HTTP request for model: {}", options.model(), e);
            callback.onError(e);
        }
    }
}
