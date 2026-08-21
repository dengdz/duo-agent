package dev.duo.adapter;

import com.sun.net.httpserver.HttpServer;
import dev.duo.adapter.anthropic.AnthropicAdapter;
import dev.duo.adapter.openai.ChatCompletionsAdapter;
import dev.duo.adapter.openai.ResponsesAdapter;
import dev.duo.api.llm.StreamCallback;
import dev.duo.exception.LlmException;
import dev.duo.model.llm.ContentBlock;
import dev.duo.model.llm.GenerateOptions;
import dev.duo.model.llm.MessageFactory;
import dev.duo.model.llm.MessageSource;
import dev.duo.model.llm.StreamChunk;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 三协议适配器的 HTTP 层端到端测试（JDK 内置 HttpServer mock 端点）。
 * <p>
 * 验证协议层在真实 HTTP/SSE 通路上的行为：端点路径、鉴权头（有无 apiKey）、
 * SSE 流解析与非 200 错误映射。协议事件结构的细粒度断言见各协议 SseParser 测试。
 * </p>
 *
 * @author zhangyl
 * @date 2026-08-21
 */
class ProtocolHttpTest {

    private HttpServer server;
    private String baseUrl;

    /** handler 记录到的请求路径。 */
    private final AtomicReference<String> requestPath = new AtomicReference<>();
    /** handler 记录到的 Authorization 头（未发送时为 null）。 */
    private final AtomicReference<String> authHeader = new AtomicReference<>();
    /** handler 记录到的 x-api-key 头（未发送时为 null）。 */
    private final AtomicReference<String> apiKeyHeader = new AtomicReference<>();
    /** handler 产出的 SSE 行（每个 handler 自行填充）。 */
    private volatile List<String> sseLines = List.of();
    /** handler 响应的 HTTP 状态码（默认 200）。 */
    private volatile int status = 200;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var port = server.getAddress().getPort();
        baseUrl = "http://127.0.0.1:" + port;
        server.createContext("/", exchange -> {
            requestPath.set(exchange.getRequestURI().getPath());
            authHeader.set(exchange.getRequestHeaders().getFirst("Authorization"));
            apiKeyHeader.set(exchange.getRequestHeaders().getFirst("x-api-key"));
            // 消费请求体（Content-Length 必须读掉，否则连接复用异常）
            try (var ignored = exchange.getRequestBody()) {
                // ignore
            }
            if (status != 200) {
                var body = "{\"error\":{\"message\":\"mock failure\"}}"
                        .getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(status, body.length);
                try (var out = exchange.getResponseBody()) {
                    out.write(body);
                }
                return;
            }
            var payload = String.join("\n", sseLines).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, payload.length);
            try (var out = exchange.getResponseBody()) {
                out.write(payload);
            }
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private GenerateOptions simpleOptions() {
        var message = MessageFactory.createUserMessage(
                List.of(new ContentBlock.Text("hi")), new MessageSource.User());
        return new GenerateOptions("test", "test-model", List.of(message));
    }

    private static final class Result {
        final List<StreamChunk> chunks = new java.util.ArrayList<>();
        final CompletableFuture<Void> done = new CompletableFuture<>();
        final AtomicReference<Throwable> error = new AtomicReference<>();

        StreamCallback callback() {
            return new StreamCallback() {
                @Override
                public void onChunk(StreamChunk chunk) {
                    chunks.add(chunk);
                }

                @Override
                public void onComplete() {
                    done.complete(null);
                }

                @Override
                public void onError(Throwable t) {
                    error.set(t);
                    done.complete(null);
                }
            };
        }
    }

    @Test
    void chatCompletionsShouldPostToEndpointAndParseSse() throws Exception {
        sseLines = List.of(
                "data: {\"choices\":[{\"delta\":{\"content\":\"你好\"},\"finish_reason\":null}]}",
                "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}",
                "data: [DONE]");
        var adapter = new ChatCompletionsAdapter("key-1", baseUrl, null, null);

        var result = new Result();
        adapter.stream(simpleOptions(), result.callback());
        result.done.get(5, TimeUnit.SECONDS);

        assertNull(result.error.get(), "正常流不应报错: "
                + (result.error.get() == null ? "" : result.error.get().getMessage()));
        assertEquals("/chat/completions", requestPath.get(), "应请求 Chat Completions 路径");
        assertEquals("Bearer key-1", authHeader.get(), "应发送 Bearer 鉴权头");
        assertTrue(result.chunks.stream().anyMatch(c -> c instanceof StreamChunk.TextDelta t
                && "你好".equals(t.text())), "应解析出文本增量");
    }

    @Test
    void chatCompletionsShouldOmitAuthHeaderWithoutApiKey() throws Exception {
        sseLines = List.of("data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}");
        // 本地无鉴权端点（Ollama）：apiKey 为 null 时不发 Authorization 头
        var adapter = new ChatCompletionsAdapter(null, baseUrl, null, null);

        var result = new Result();
        adapter.stream(simpleOptions(), result.callback());
        result.done.get(5, TimeUnit.SECONDS);

        assertNull(authHeader.get(), "无 apiKey 时不应发送 Authorization 头");
        assertNull(result.error.get());
    }

    @Test
    void chatCompletionsShouldReportHttpErrorWithStatus() throws Exception {
        status = 429;
        var adapter = new ChatCompletionsAdapter("key-1", baseUrl, null, null);

        var result = new Result();
        adapter.stream(simpleOptions(), result.callback());
        result.done.get(5, TimeUnit.SECONDS);

        assertInstanceOf(LlmException.class, result.error.get(), "非 200 应报告 LlmException");
        assertEquals(429, ((LlmException) result.error.get()).status(), "应携带 HTTP 状态码");
    }

    @Test
    void anthropicShouldPostToMessagesWithProtocolHeaders() throws Exception {
        sseLines = List.of(
                "data: {\"type\":\"message_start\",\"message\":{\"usage\":{\"input_tokens\":3}}}",
                "data: {\"type\":\"content_block_start\",\"index\":0,"
                        + "\"content_block\":{\"type\":\"text\",\"text\":\"\"}}",
                "data: {\"type\":\"content_block_delta\",\"index\":0,"
                        + "\"delta\":{\"type\":\"text_delta\",\"text\":\"回答\"}}",
                "data: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\"},"
                        + "\"usage\":{\"output_tokens\":7}}");
        var adapter = new AnthropicAdapter("key-2", baseUrl, null, null, null, null);

        var result = new Result();
        adapter.stream(simpleOptions(), result.callback());
        result.done.get(5, TimeUnit.SECONDS);

        assertEquals("/v1/messages", requestPath.get(), "应请求 Messages 路径");
        assertEquals("key-2", apiKeyHeader.get(), "应发送 x-api-key 鉴权头");
        assertNull(authHeader.get(), "Anthropic 协议不应使用 Bearer 头");
        assertTrue(result.chunks.stream().anyMatch(c -> c instanceof StreamChunk.TextDelta t
                && "回答".equals(t.text())), "应解析出文本增量");
    }

    @Test
    void responsesShouldPostToResponsesEndpointWithBearer() throws Exception {
        sseLines = List.of(
                "data: {\"type\":\"response.output_item.added\",\"output_index\":0,"
                        + "\"item\":{\"type\":\"message\",\"role\":\"assistant\"}}",
                "data: {\"type\":\"response.output_text.delta\",\"output_index\":0,\"delta\":\"答案\"}",
                "data: {\"type\":\"response.completed\",\"response\":"
                        + "{\"usage\":{\"input_tokens\":5,\"output_tokens\":6}}}");
        var adapter = new ResponsesAdapter("key-3", baseUrl, null, null);

        var result = new Result();
        adapter.stream(simpleOptions(), result.callback());
        result.done.get(5, TimeUnit.SECONDS);

        assertEquals("/responses", requestPath.get(), "应请求 Responses 路径");
        assertEquals("Bearer key-3", authHeader.get(), "应发送 Bearer 鉴权头");
        assertTrue(result.chunks.stream().anyMatch(c -> c instanceof StreamChunk.TextDelta t
                && "答案".equals(t.text())), "应解析出文本增量");
        assertTrue(result.chunks.stream().anyMatch(c -> c instanceof StreamChunk.Finish),
                "completed 事件应产生 Finish");
    }

    @Test
    void anthropicAndResponsesShouldRejectMissingApiKey() {
        var anthropic = new AnthropicAdapter(null, baseUrl, null, null, null, null);
        var r1 = new Result();
        anthropic.stream(simpleOptions(), r1.callback());
        assertInstanceOf(IllegalStateException.class, r1.error.get(),
                "Anthropic 无 key 应即时报错");

        var responses = new ResponsesAdapter(null, baseUrl, null, null);
        var r2 = new Result();
        responses.stream(simpleOptions(), r2.callback());
        assertInstanceOf(IllegalStateException.class, r2.error.get(),
                "Responses 无 key 应即时报错");
    }
}
