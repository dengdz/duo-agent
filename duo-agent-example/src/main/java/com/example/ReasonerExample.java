package com.example;

import dev.duo.api.DuoAgent;
import dev.duo.api.DuoModel;
import dev.duo.model.deepseek.DeepSeekModel;
import dev.duo.model.llm.StreamChunk;
import dev.duo.model.session.SessionEvent;
import dev.duo.model.session.SessionEventAssistantChunk;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 推理模型（deepseek-reasoner）流式验证示例。
 * <p>
 * 验证点：
 * <ul>
 *   <li>enableReasoning(true) + reasoningTimeout 生效（思考耗时长不会被默认 60s 超时掐断）</li>
 *   <li>stream() 事件流中思考（ReasoningDelta）与回答（TextDelta）分别可见</li>
 *   <li>maxOutputTokens 不设置，由模型决定（推理模型的 max_tokens 含思考部分，
 *       限制过小会导致回答被截断）</li>
 * </ul>
 * </p>
 *
 * @author zhangyl
 * @date 2026-08-19
 */
public class ReasonerExample {

    public static void main(String[] args) throws InterruptedException {
        var apiKey = EnvLoader.get("DEEPSEEK_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            System.err.println("❌ 错误：未设置 DEEPSEEK_API_KEY");
            System.exit(1);
        }

        // 推理配置属于模型本身，全部在 DeepSeekModel 上设置
        DuoModel model = DeepSeekModel.builder()
                .apiKey(apiKey)
                .model("deepseek-reasoner")          // DeepSeek-R1 推理模型
                .contextWindow(64000)
                .enableReasoning(true)               // 启用推理模式
                .reasoningTimeout(Duration.ofMinutes(8))  // 思考最长 8 分钟
                // 注意：不设置 maxOutputTokens——推理模型的 max_tokens 限额包含思考过程
                .build();
        var agent = DuoAgent.builder()
                .model(model)
                .build();

        System.out.println("=== 推理模型流式验证（deepseek-reasoner）===\n");

        var start = System.currentTimeMillis();
        var textCount = new AtomicInteger();
        var reasoningCount = new AtomicInteger();
        var answerStarted = new AtomicBoolean(false);
        var error = new AtomicReference<Throwable>();
        var subscriptionRef = new AtomicReference<Flow.Subscription>();
        var done = new CountDownLatch(1);

        agent.stream("一个笼子里有鸡和兔，共 35 个头、94 只脚，问鸡兔各几只？请推理。")
                .subscribe(new Flow.Subscriber<SessionEvent>() {
                    @Override
                    public void onSubscribe(Flow.Subscription s) {
                        subscriptionRef.set(s);
                        s.request(Long.MAX_VALUE);
                    }

                    @Override
                    public void onNext(SessionEvent event) {
                        if (event instanceof SessionEventAssistantChunk c
                                && c.chunk() instanceof StreamChunk.ReasoningDelta r) {
                            reasoningCount.incrementAndGet();
                            System.out.print(r.text());  // 思考过程实时可见
                        } else if (event instanceof SessionEventAssistantChunk c
                                && c.chunk() instanceof StreamChunk.TextDelta d) {
                            // 首个回答增量前打印分隔，控制台上区分思考与回答的边界
                            if (answerStarted.compareAndSet(false, true)) {
                                System.out.println("\n\n--- 以上为思考过程，以下为最终回答 ---\n");
                            }
                            textCount.incrementAndGet();
                            System.out.print(d.text());  // 最终回答的文本增量
                        }
                    }

                    @Override
                    public void onError(Throwable t) {
                        error.set(t);
                        done.countDown();
                    }

                    @Override
                    public void onComplete() {
                        done.countDown();
                    }
                });

        if (!done.await(10, TimeUnit.MINUTES)) {
            // 取消未完成的流：释放底层 SSE 连接（服务场景必须如此，否则泄漏）
            var subscription = subscriptionRef.get();
            if (subscription != null) {
                subscription.cancel();
            }
            System.err.println("\n❌ 超过 10 分钟未完成，已取消订阅");
            return;
        }

        var elapsed = System.currentTimeMillis() - start;
        if (error.get() != null) {
            System.err.println("\n\n❌ 失败: " + error.get().getMessage());
            error.get().printStackTrace();
            return;
        }

        System.out.println("\n\n=== 完成 ===");
        System.out.println("收到 " + reasoningCount.get() + " 个思考增量、"
                + textCount.get() + " 个回答增量，总耗时 " + elapsed + "ms");
    }
}
