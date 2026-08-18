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
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(60);
    private static final int HTTP_OK = 200;

    private final HttpClient httpClient;
    private final String baseUrl;
    private final String apiKey;

    public DeepSeekAdapter() {
        this.httpClient = HttpClient.newBuilder().connectTimeout(DEFAULT_TIMEOUT).build();
        this.baseUrl = getEnvOrDefault("DEEPSEEK_BASE_URL", DEFAULT_BASE_URL);
        this.apiKey = System.getenv(API_KEY_ENV);
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
            var request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/chat/completions"))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .header("Accept", "text/event-stream")
                    .timeout(DEFAULT_TIMEOUT)
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody, StandardCharsets.UTF_8))
                    .build();

            httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofLines())
                    .thenAccept(response -> {
                        if (response.statusCode() != HTTP_OK) {
                            var body = response.body().collect(Collectors.joining());
                            logger.error("DeepSeek API returned {} for model {}",
                                    response.statusCode(), options.model());
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
