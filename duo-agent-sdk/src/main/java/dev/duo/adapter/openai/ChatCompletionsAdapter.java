package dev.duo.adapter.openai;

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
 * OpenAI Chat Completions 协议适配器。
 * <p>
 * 将 {@link GenerateOptions} 序列化为 Chat Completions 请求，解析 SSE 响应并翻译为
 * {@link dev.duo.model.llm.StreamChunk} 协议。适配器只做单次请求并报告结构化失败
 * （{@link LlmException#status()} 携带 HTTP 状态码）；重试等恢复策略由外层的
 * request-error hook（如 {@code LlmRetryHook}）实现。
 * </p>
 * <p>
 * 泛化自 DeepSeek 协议实现，覆盖一切 Chat Completions 兼容端点
 * （DeepSeek、Kimi、通义、Ollama、vLLM 等）。协议变体差异：
 * apiKey 为 null 时不发送 Authorization 头（本地无鉴权部署）；
 * 流式思考字段名经 {@code reasoningContentField} 参数化（null 不解析）。
 * </p>
 *
 * @author zhangyl
 * @date 2026-08-21
 */
public final class ChatCompletionsAdapter extends LlmAdapter {

    private static final Logger logger = LoggerFactory.getLogger(ChatCompletionsAdapter.class);

    /** Chat Completions 端点路径（拼接在 baseUrl 之后）。 */
    private static final String CHAT_COMPLETIONS_PATH = "/chat/completions";

    /** TCP 连接建立超时（仅管建连阶段，不影响响应体读取）。 */
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(60);

    /**
     * 全类共享的 HttpClient：JDK HttpClient 并发安全且无 close 语义，
     * 按实例各建会造成连接池/selector 线程随 Agent 组装次数累积。
     */
    private static final HttpClient SHARED_HTTP_CLIENT =
            HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();

    /**
     * 单次请求整体超时兜底（未显式指定时使用）。JDK HttpClient 的 HttpRequest.timeout
     * 覆盖到响应体完成，对 SSE 流式响应即整段生成时长。
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
    private final Duration requestTimeout;
    private final String reasoningContentField;

    /**
     * 显式指定连接配置的构造（编程接入优先使用；密钥只经内存传递，不做日志输出）。
     *
     * @param apiKey API 密钥（null 表示端点无鉴权，不发送 Authorization 头）
     * @param baseUrl API 基地址（含版本前缀，如 {@code https://api.deepseek.com}、
     *                Ollama {@code http://localhost:11434/v1}；尾部斜杠会被去除）
     * @param requestTimeout 单次请求整体超时（null 用默认兜底）
     * @param reasoningContentField 流式思考的响应字段名（null 表示端点不透出流式思考）
     */
    public ChatCompletionsAdapter(String apiKey, String baseUrl, Duration requestTimeout,
                                  String reasoningContentField) {
        this.apiKey = apiKey == null || apiKey.isBlank() ? null : apiKey;
        this.baseUrl = baseUrl == null || baseUrl.isBlank()
                ? null
                : (baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl);
        this.requestTimeout = requestTimeout == null ? DEFAULT_REQUEST_TIMEOUT : requestTimeout;
        if (this.requestTimeout.isZero() || this.requestTimeout.isNegative()) {
            throw new IllegalArgumentException("requestTimeout 必须大于 0");
        }
        this.reasoningContentField = reasoningContentField;
    }

    @Override
    public void stream(GenerateOptions options, StreamCallback callback) {
        Objects.requireNonNull(options, "options must not be null");
        Objects.requireNonNull(callback, "callback must not be null");

        if (baseUrl == null) {
            callback.onError(new IllegalStateException("baseUrl 未设置（Chat Completions 端点无官方默认值）"));
            return;
        }

        // 每次调用创建新的解析器实例，避免并发问题
        var parser = OpenAiSseParser.create(reasoningContentField);

        try {
            var requestBody = OpenAiRequestBuilder.buildRequest(options);
            logger.debug("Chat Completions API 请求体:\n{}", requestBody);
            var builder = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + CHAT_COMPLETIONS_PATH))
                    .header("Content-Type", "application/json")
                    .header("Accept", "text/event-stream")
                    .timeout(requestTimeout)
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody, StandardCharsets.UTF_8));
            if (apiKey != null) {
                builder.header("Authorization", "Bearer " + apiKey);
            }
            var request = builder.build();

            SHARED_HTTP_CLIENT.sendAsync(request, HttpResponse.BodyHandlers.ofLines())
                    .thenAccept(response -> {
                        if (response.statusCode() != HTTP_OK) {
                            var body = response.body().collect(Collectors.joining("\n"));
                            logger.error("Chat Completions API returned {} for model {}\n响应体: {}",
                                    response.statusCode(), options.model(), body);
                            callback.onError(new LlmException(String.format(
                                    "Chat Completions API 返回 %d (model: %s): %s",
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
                                "Chat Completions 请求失败 (model: " + options.model() + "): "
                                        + error.getMessage(), error));
                        return null;
                    });
        } catch (Exception e) {
            logger.error("Failed to send HTTP request for model: {}", options.model(), e);
            callback.onError(e);
        }
    }
}
