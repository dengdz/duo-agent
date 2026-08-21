package com.example;

import dev.duo.api.DuoAgent;
import dev.duo.api.DuoModel;
import dev.duo.model.anthropic.AnthropicModel;
import dev.duo.model.llm.StreamChunk;
import dev.duo.model.session.SessionEvent;
import dev.duo.model.session.SessionEventAssistantChunk;

import java.util.concurrent.Flow;

/**
 * 内部中转站适配验证（Anthropic 协议）。
 * <p>
 * 验证点：
 * <ul>
 *   <li>Model.call() — 基础链路（鉴权、/v1/messages 端点、请求格式）</li>
 *   <li>Agent.stream() — 完整事件流（块解析、Finish/Usage）</li>
 *   <li>工具调用（可选）— 中转站透传 tools 定义与 tool_use 块</li>
 * </ul>
 * 使用：把 API_KEY 填入下方常量（或 .env 的 RELAY_API_KEY），IDEA 直接运行。
 * </p>
 *
 * @author zhangyl
 * @date 2026-08-21
 */
public class RelayAnthropicExample {

    // ==================== 在这里填配置 ====================
    /** 中转站地址（已填）。 */
    private static final String BASE_URL = "https://openrouter-test.huan.tv";

    /** 你的消费者 AK——填到这里，或写到 .env 的 RELAY_API_KEY= */
    private static final String API_KEY = "<在这里填入你的消费者AK>";

    /** 模型名——按中转站实际支持的模型改（如 claude-sonnet-4-5 / glm-4.6 等）。 */
    private static final String MODEL = "deepseek-v4-flash";
    // =====================================================

    public static void main(String[] args) throws InterruptedException {
        var apiKey = "<在这里填入你的消费者AK>".equals(API_KEY)
                ? EnvLoader.get("RELAY_API_KEY") : API_KEY;
        if (apiKey == null || apiKey.isBlank()) {
            System.err.println("❌ 未设置 API Key：填入代码中的 API_KEY 常量，或在 .env 中配置 RELAY_API_KEY=<你的AK>");
            System.exit(1);
        }

        DuoModel model = AnthropicModel.builder()
                .baseUrl(BASE_URL)
                .apiKey(apiKey)
                .model(MODEL)
                .build();

        System.out.println("=== 中转站适配验证（" + BASE_URL + " / " + MODEL + "）===\n");

        // 第 0 步：原始响应探测——打印中转站返回的原始 SSE 行，定位格式差异
        System.out.println("【0/3】原始响应探测（前 25 行）");
        rawProbe(apiKey);

        // 第 1 步：Model 单次调用——验证鉴权、端点、请求格式
        System.out.println("\n【1/3】Model.call()：基础链路");
        try {
            System.out.println("回答: " + model.call("用一句话回复：连接成功"));
        } catch (Exception e) {
            System.err.println("\n❌ 基础链路失败: " + e.getMessage());
            adapterProbe(apiKey);
            return;
        }

        // 第 2 步：Agent 流式——验证事件流解析
        System.out.println("\n【2/3】Agent.stream()：事件流");
        var done = new java.util.concurrent.CountDownLatch(1);
        var failure = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        var agent = DuoAgent.builder().model(model).build();
        agent.stream("数到 5，每个数字单独输出").subscribe(new Flow.Subscriber<SessionEvent>() {
            private Flow.Subscription subscription;

            @Override
            public void onSubscribe(Flow.Subscription s) {
                subscription = s;
                s.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(SessionEvent event) {
                if (event instanceof SessionEventAssistantChunk c
                        && c.chunk() instanceof StreamChunk.TextDelta d) {
                    System.out.print(d.text());
                }
            }

            @Override
            public void onError(Throwable t) {
                failure.set(t);
                done.countDown();
            }

            @Override
            public void onComplete() {
                done.countDown();
            }
        });
        done.await();
        if (failure.get() != null) {
            System.err.println("\n❌ 事件流失败: " + failure.get().getMessage());
            return;
        }
        System.out.println("\n✅ 事件流正常");

        // 第 3 步：工具调用——验证中转站透传 tools 定义与 tool_use 块
        System.out.println("\n【3/3】Agent 工具调用：bash + 文件工具");
        var toolAgent = DuoAgent.builder()
                .model(model)
                .withCodeTools()
                .build();
        String result = toolAgent.call("执行 echo relay-ok 并原样告诉我输出内容");
        System.out.println("回答: " + result);

        System.out.println("\n=== 验证完成：中转站适配正常 ===");
    }

    /**
     * 原始响应探测：手发最小 Anthropic 请求，打印响应状态与前 25 行原始内容。
     * 用于定位中转站与标准协议的格式差异（data 行空格、事件结构、错误体包装等）。
     */
    private static void rawProbe(String apiKey) {
        try {
            var body = "{\"model\": \"" + MODEL + "\", \"max_tokens\": 1024, \"stream\": true, "
                    + "\"messages\": [{\"role\": \"user\", \"content\": \"hi\"}]}";
            var request = java.net.http.HttpRequest.newBuilder()
                    .uri(java.net.URI.create(BASE_URL + "/v1/messages"))
                    .header("x-api-key", apiKey)
                    .header("anthropic-version", "2023-06-01")
                    .header("Content-Type", "application/json")
                    .header("Accept", "text/event-stream")
                    .timeout(java.time.Duration.ofSeconds(60))
                    .POST(java.net.http.HttpRequest.BodyPublishers.ofString(body))
                    .build();
            var client = java.net.http.HttpClient.newHttpClient();
            client.sendAsync(request, java.net.http.HttpResponse.BodyHandlers.ofLines())
                    .thenAccept(response -> {
                        System.out.println("HTTP 状态: " + response.statusCode());
                        System.out.println("Content-Type: "
                                + response.headers().firstValue("content-type").orElse("(无)"));
                        var lines = response.body().toList();
                        System.out.println("总行数: " + lines.size() + "，前 8 行：");
                        for (int i = 0; i < Math.min(8, lines.size()); i++) {
                            System.out.println("  头| " + lines.get(i));
                        }
                        System.out.println("末 12 行：");
                        for (int i = Math.max(0, lines.size() - 12); i < lines.size(); i++) {
                            System.out.println("  尾| " + lines.get(i));
                        }
                    })
                    .exceptionally(e -> {
                        System.err.println("探测失败: " + e.getMessage());
                        return null;
                    })
                    .join();
        } catch (Exception e) {
            System.err.println("探测失败: " + e.getMessage());
        }
    }

    /** 走 SDK 适配器的探测：打印 Adapter 实际收到的每个 chunk，定位解析层问题。 */
    private static void adapterProbe(String apiKey) throws InterruptedException {
        System.out.println("\n【补充探测】AnthropicAdapter 逐 chunk 输出：");
        var adapter = new dev.duo.adapter.anthropic.AnthropicAdapter(apiKey, BASE_URL, null, null, null, null);
        var message = dev.duo.model.llm.MessageFactory.createUserMessage(
                java.util.List.of(new dev.duo.model.llm.ContentBlock.Text("用一句话回复：连接成功")),
                new dev.duo.model.llm.MessageSource.User());
        var options = new dev.duo.model.llm.GenerateOptions("relay", MODEL, java.util.List.of(message));
        var done = new java.util.concurrent.CountDownLatch(1);
        var count = new int[]{0};
        adapter.stream(options, new dev.duo.api.llm.StreamCallback() {
            @Override
            public void onChunk(dev.duo.model.llm.StreamChunk chunk) {
                count[0]++;
                if (count[0] <= 20 || !(chunk instanceof dev.duo.model.llm.StreamChunk.ReasoningDelta)) {
                    System.out.println("  chunk: " + chunk.getClass().getSimpleName()
                            + " " + describeShort(chunk));
                }
            }

            @Override
            public void onComplete() {
                System.out.println("  onComplete（共 " + count[0] + " 个 chunk）");
                done.countDown();
            }

            @Override
            public void onError(Throwable t) {
                System.out.println("  onError: " + t.getMessage());
                done.countDown();
            }
        });
        done.await(90, java.util.concurrent.TimeUnit.SECONDS);
    }

    private static String describeShort(dev.duo.model.llm.StreamChunk chunk) {
        return switch (chunk) {
            case dev.duo.model.llm.StreamChunk.TextDelta t -> "\"" + t.text() + "\"";
            case dev.duo.model.llm.StreamChunk.ReasoningDelta r -> "(思考)";
            case dev.duo.model.llm.StreamChunk.Finish f -> String.valueOf(f.reason().getClass().getSimpleName());
            case dev.duo.model.llm.StreamChunk.Usage u -> String.valueOf(u.usage());
            default -> "";
        };
    }
}