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
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * DeepSeek API 适配器。
 * <p>
 * 将 harness 的 {@link GenerateOptions} 序列化为 DeepSeek 聊天补全 API 请求，
 * 解析 SSE 响应并翻译为 {@link StreamChunk} 协议。
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
    
    // 重试配置常量
    private static final int MAX_RETRY_ATTEMPTS = 3;
    private static final Duration INITIAL_RETRY_DELAY = Duration.ofMillis(500);
    private static final double RETRY_BACKOFF_MULTIPLIER = 2.0;
    
    // 重试调度器（共享，用于延迟重试任务）
    private static final ScheduledExecutorService RETRY_SCHEDULER = 
            Executors.newScheduledThreadPool(2, runnable -> {
                var thread = new Thread(runnable);
                thread.setName("deepseek-retry-" + thread.threadId());
                thread.setDaemon(true);
                return thread;
            });

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
        
        // 带重试的请求执行
        streamWithRetry(options, callback, parser, 0);
    }

    /**
     * 带重试机制的流式请求。
     *
     * @param options 生成选项
     * @param callback 流回调
     * @param parser SSE 解析器
     * @param attemptCount 当前重试次数
     */
    private void streamWithRetry(GenerateOptions options, StreamCallback callback, 
                                  DeepSeekSseParser parser, int attemptCount) {
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
                        if (response.statusCode() != 200) {
                            var body = response.body().collect(Collectors.joining());
                            var error = new LlmException(
                                    String.format("DeepSeek API 返回 %d (model: %s, attempt: %d/%d): %s",
                                            response.statusCode(), options.model(), 
                                            attemptCount + 1, MAX_RETRY_ATTEMPTS, body));
                            
                            // 判断是否可重试（5xx 服务端错误、429 限流）
                            if (shouldRetry(response.statusCode(), attemptCount)) {
                                logger.warn("HTTP request failed with status {}, model: {}, attempt {}/{}, will retry", 
                                        response.statusCode(), options.model(), 
                                        attemptCount + 1, MAX_RETRY_ATTEMPTS);
                                retryAfterDelay(options, callback, parser, attemptCount);
                            } else {
                                logger.error("HTTP request failed with status {}, model: {}, attempt {}/{}", 
                                        response.statusCode(), options.model(), 
                                        attemptCount + 1, MAX_RETRY_ATTEMPTS);
                                callback.onError(error);
                            }
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
                        // 网络异常、超时等，尝试重试
                        if (shouldRetry(0, attemptCount)) {
                            logger.warn("HTTP request failed, model: {}, attempt {}/{}, will retry: {}", 
                                    options.model(), attemptCount + 1, MAX_RETRY_ATTEMPTS, error.getMessage());
                            retryAfterDelay(options, callback, parser, attemptCount);
                        } else {
                            logger.error("HTTP request failed for model: {}, attempt {}/{}", 
                                    options.model(), attemptCount + 1, MAX_RETRY_ATTEMPTS, error);
                            callback.onError(error);
                        }
                        return null;
                    });
        } catch (Exception e) {
            logger.error("Failed to send HTTP request for model: {}", options.model(), e);
            callback.onError(e);
        }
    }

    /**
     * 判断是否应该重试。
     *
     * @param statusCode HTTP 状态码（0 表示网络异常）
     * @param attemptCount 当前重试次数
     * @return 是否应该重试
     */
    private boolean shouldRetry(int statusCode, int attemptCount) {
        if (attemptCount >= MAX_RETRY_ATTEMPTS - 1) {
            return false;
        }
        // 5xx 服务端错误、429 限流、0 网络异常
        return statusCode >= 500 || statusCode == 429 || statusCode == 0;
    }

    /**
     * 延迟后重试。
     *
     * @param options 生成选项
     * @param callback 流回调
     * @param parser SSE 解析器
     * @param attemptCount 当前重试次数
     */
    private void retryAfterDelay(GenerateOptions options, StreamCallback callback,
                                  DeepSeekSseParser parser, int attemptCount) {
        var delay = calculateRetryDelay(attemptCount);
        parser.reset();
        RETRY_SCHEDULER.schedule(
                () -> streamWithRetry(options, callback, parser, attemptCount + 1),
                delay.toMillis(),
                TimeUnit.MILLISECONDS
        );
    }

    /**
     * 计算重试延迟（指数退避）。
     *
     * @param attemptCount 当前重试次数
     * @return 延迟时间
     */
    private Duration calculateRetryDelay(int attemptCount) {
        var delayMillis = (long) (INITIAL_RETRY_DELAY.toMillis() 
                * Math.pow(RETRY_BACKOFF_MULTIPLIER, attemptCount));
        return Duration.ofMillis(delayMillis);
    }

    private static String getEnvOrDefault(String key, String defaultValue) {
        var value = System.getenv(key);
        return value != null && !value.isBlank() ? value : defaultValue;
    }
}
