package dev.duo.adapter.openai;

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
 * OpenAI Responses 协议适配器。
 * <p>
 * 将 {@link GenerateOptions} 序列化为 Responses 协议请求，解析 {@code response.*}
 * 事件流并翻译为 {@link dev.duo.model.llm.StreamChunk}。适配器只做单次请求并报告
 * 结构化失败（{@link LlmException#status()} 携带 HTTP 状态码）；重试等恢复策略由
 * 外层 request-error hook 实现。
 * </p>
 * <p>
 * 覆盖 OpenAI 官方端点（gpt-5/o 系列——新模型能力只在 Responses 提供）及兼容实现。
 * 仅无状态形态：不使用 previous_response_id 与托管内置工具。
 * </p>
 *
 * @author zhangyl
 * @date 2026-08-21
 */
public final class ResponsesAdapter extends LlmAdapter {

    private static final Logger logger = LoggerFactory.getLogger(ResponsesAdapter.class);

    /** OpenAI 官方 API 端点。 */
    public static final String DEFAULT_BASE_URL = "https://api.openai.com/v1";

    /** Responses 端点路径（拼接在 baseUrl 之后）。 */
    private static final String RESPONSES_PATH = "/responses";

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
     * <b>约束：必须始终大于应用层最大超时</b>，否则会先于应用层 barrier 掐断流式
     * 回复。经由 {@code DuoAgentBuilder} 组装时会按应用层超时自动计算并显式传入。
     * </p>
     */
    private static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofMinutes(10);

    private static final int HTTP_OK = 200;

    private final String baseUrl;
    private final String apiKey;
    private final Duration requestTimeout;
    private final String reasoningEffort;

    /**
     * 显式指定连接配置的构造（编程接入优先使用；密钥只经内存传递，不做日志输出）。
     *
     * @param apiKey API 密钥
     * @param baseUrl API 基地址（null 用官方默认；尾部斜杠会被去除）
     * @param requestTimeout 单次请求整体超时（null 用默认兜底）
     * @param reasoningEffort 推理努力级别（null 表示不启用推理参数）
     */
    public ResponsesAdapter(String apiKey, String baseUrl, Duration requestTimeout,
                            String reasoningEffort) {
        this.apiKey = apiKey == null || apiKey.isBlank() ? null : apiKey;
        this.baseUrl = baseUrl == null || baseUrl.isBlank()
                ? DEFAULT_BASE_URL
                : (baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl);
        this.requestTimeout = requestTimeout == null ? DEFAULT_REQUEST_TIMEOUT : requestTimeout;
        if (this.requestTimeout.isZero() || this.requestTimeout.isNegative()) {
            throw new IllegalArgumentException("requestTimeout 必须大于 0");
        }
        this.reasoningEffort = reasoningEffort;
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
            callback.onError(new IllegalStateException("Responses 端点鉴权必须设置 apiKey"));
            return;
        }

        // 每次调用创建新的解析器实例，避免并发问题
        var parser = new ResponsesSseParser();

        try {
            var requestBody = ResponsesRequestBuilder.buildRequest(options, reasoningEffort);
            logger.debug("Responses API 请求体:\n{}", requestBody);
            var request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + RESPONSES_PATH))
                    .header("Authorization", "Bearer " + apiKey)
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
                            logger.error("Responses API returned {} for model {}\n响应体: {}",
                                    response.statusCode(), options.model(), body);
                            callback.onError(new LlmException(String.format(
                                    "Responses API 返回 %d (model: %s): %s",
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
                                "Responses 请求失败 (model: " + options.model() + "): "
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
