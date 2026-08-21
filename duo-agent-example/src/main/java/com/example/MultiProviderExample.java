package com.example;

import dev.duo.api.DuoAgent;
import dev.duo.api.DuoModel;
import dev.duo.model.anthropic.AnthropicModel;
import dev.duo.model.llm.StreamChunk;
import dev.duo.model.openai.ChatCompletionsModel;
import dev.duo.model.openai.ResponsesModel;
import dev.duo.model.session.SessionEvent;
import dev.duo.model.session.SessionEventAssistantChunk;

import java.util.concurrent.Flow;

/**
 * 多厂商协议验证示例（0.3.0 三协议）。
 * <p>
 * 按环境变量自动选择可验证的协议，缺失 key 的协议跳过：
 * <ul>
 *   <li>Chat Completions：DeepSeek 官方端点（DEEPSEEK_API_KEY），
 *       用 deepseek-reasoner 验证泛化协议层的流式思考解析</li>
 *   <li>Anthropic Messages：智谱兼容端点（ZHIPU_API_KEY），glm-4.6</li>
 *   <li>Responses：OpenAI 官方（OPENAI_API_KEY），gpt-5.2</li>
 * </ul>
 * 每个协议验证两步：Model 单次调用（call）与 Agent 流式事件（stream）。
 * </p>
 *
 * @author zhangyl
 * @date 2026-08-21
 */
public class MultiProviderExample {

    public static void main(String[] args) throws InterruptedException {
        System.out.println("=== 0.3.0 多厂商协议验证 ===\n");
//        verifyChatCompletions();
//        verifyAnthropic();
        verifyResponses();
        System.out.println("\n=== 验证完成 ===");
    }

    /** 协议 1：Chat Completions（DeepSeek，重点验证 reasoning_content 思考流）。 */
    private static void verifyChatCompletions() throws InterruptedException {
        var apiKey = EnvLoader.get("DEEPSEEK_API_KEY");
        if (skip("Chat Completions (DeepSeek)", apiKey)) {
            return;
        }
        // 泛化路径：ChatCompletionsModel 直连 DeepSeek 端点（不经 DeepSeekModel 预设），
        // 显式配置 reasoning_content 字段——验证协议泛化 + 思考流解析
        DuoModel model = ChatCompletionsModel.builder()
                .baseUrl("https://api.deepseek.com")
                .apiKey(apiKey)
                .model("deepseek-v4-flash")
                .contextWindow(64000)
                .enableReasoning(true)
                .reasoningContentField("reasoning_content")
                .build();

        System.out.println("【协议 1/3】Chat Completions — Model.call()（deepseek-reasoner）");
        String answer = model.call("一句话解释什么是协议适配器");
        System.out.println("回答: " + answer);

        System.out.println("\n【协议 1/3】Agent.stream()（思考与回答分别计数）");
        streamOnce(model, "用一句话说明事件溯源的核心思想");
    }

    /** 协议 2：Anthropic Messages（智谱优先，DeepSeek 兼容端点回退）。 */
    private static void verifyAnthropic() throws InterruptedException {
        var zhipuKey = EnvLoader.get("ZHIPU_API_KEY");
        var deepseekKey = EnvLoader.get("DEEPSEEK_API_KEY");
        String baseUrl;
        String apiKey;
        String modelName;
        boolean reasoning;
        if (zhipuKey != null && !zhipuKey.isBlank()) {
            baseUrl = "https://open.bigmodel.cn/api/anthropic";
            apiKey = zhipuKey;
            modelName = "glm-4.6";
            reasoning = true;
        } else if (deepseekKey != null && !deepseekKey.isBlank()) {
            // DeepSeek 的 Anthropic 兼容端点：一个 key 测全三协议。
            // deepseek-v4-flash 是混合推理模型：即使不发 thinking 参数也默认输出
            // thinking 块，必须按推理模式配置时间预算（reasoningTimeout 默认 5 分钟），
            // 否则普通模式的 60s 应用层超时会掐断思考中的响应
            baseUrl = "https://api.deepseek.com/anthropic";
            apiKey = deepseekKey;
            modelName = "deepseek-v4-flash";
            reasoning = true;
        } else {
            skip("Anthropic Messages", null);
            return;
        }
        DuoModel model = AnthropicModel.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .model(modelName)
                .enableReasoning(reasoning)
                .build();

        System.out.println("【协议 2/3】Anthropic Messages — Model.call()（" + modelName + " @ " + baseUrl + "）");
        System.out.println("回答: " + model.call("一句话解释什么是 Messages 协议"));

        System.out.println("\n【协议 2/3】Agent.stream()");
        streamOnce(model, "用一句话说明 thinking 与 text 块的区别");
    }

    /** 协议 3：Responses（OpenAI 官方优先，DeepSeek 兼容端点回退）。 */
    private static void verifyResponses() throws InterruptedException {
        var openaiKey = EnvLoader.get("OPENAI_API_KEY");
        var deepseekKey = EnvLoader.get("DEEPSEEK_API_KEY");
        String baseUrl;
        String apiKey;
        String modelName;
        boolean reasoning = false;
        if (openaiKey != null && !openaiKey.isBlank()) {
            baseUrl = "https://api.openai.com/v1";
            apiKey = openaiKey;
            modelName = "gpt-5.2";
            reasoning = true;
        } else if (deepseekKey != null && !deepseekKey.isBlank()) {
            // DeepSeek 的 Responses 兼容端点（官方为 Codex 接入提供，无状态）。
            // v4-flash 混合推理模型按推理模式配置（时间预算 + 思考摘要请求）；
            // 端点不支持的能力（如 summary）按官方说明以固定值忽略，无害
            baseUrl = "https://api.deepseek.com";
            apiKey = deepseekKey;
            modelName = "deepseek-v4-flash";
            reasoning = true;
        } else {
            skip("Responses", null);
            return;
        }
        DuoModel model = ResponsesModel.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .model(modelName)
                .enableReasoning(true)
                .build();

        System.out.println("【协议 3/3】Responses — Model.call()（" + modelName + " @ " + baseUrl + "）");
        System.out.println("回答: " + model.call("一句话解释什么是 Responses API"));

        System.out.println("\n【协议 3/3】Agent.stream()");
        streamOnce(model, "用一句话说明 input items 与 messages 的区别");
    }

    /** Agent 流式链路验证：实时打印思考过程与回答，末尾分别计数。 */
    private static void streamOnce(DuoModel model, String question) throws InterruptedException {
        var agent = DuoAgent.builder().model(model).build();
        var done = new java.util.concurrent.CountDownLatch(1);
        var failure = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        var reasoningCount = new java.util.concurrent.atomic.AtomicInteger();
        var textCount = new java.util.concurrent.atomic.AtomicInteger();
        var answerStarted = new java.util.concurrent.atomic.AtomicBoolean(false);

        agent.stream(question).subscribe(new Flow.Subscriber<SessionEvent>() {
            private Flow.Subscription subscription;

            @Override
            public void onSubscribe(Flow.Subscription s) {
                subscription = s;
                s.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(SessionEvent event) {
                if (event instanceof SessionEventAssistantChunk c) {
                    if (c.chunk() instanceof StreamChunk.ReasoningDelta r) {
                        reasoningCount.incrementAndGet();
                        System.out.print(r.text());
                    } else if (c.chunk() instanceof StreamChunk.TextDelta d) {
                        // 首个回答增量前打印分隔（仅当存在思考过程时，区分边界）
                        if (answerStarted.compareAndSet(false, true) && reasoningCount.get() > 0) {
                            System.out.println("\n\n--- 以上为思考过程，以下为最终回答 ---\n");
                        }
                        textCount.incrementAndGet();
                        System.out.print(d.text());
                    }
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
            System.out.println("\n❌ 失败: " + failure.get().getMessage());
            failure.get().printStackTrace();
            return;
        }
        System.out.printf("%n✅ 思考增量 %d 个，文本增量 %d 个%n%n",
                reasoningCount.get(), textCount.get());
    }

    private static boolean skip(String protocol, String apiKey) {
        if (apiKey == null || apiKey.isBlank()) {
            System.out.println("⏭️  " + protocol + "：未设置对应 API Key，跳过\n");
            return true;
        }
        return false;
    }
}
