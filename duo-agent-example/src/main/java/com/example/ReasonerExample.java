package com.example;

import dev.duo.api.DuoAgent;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 推理模型（deepseek-reasoner / DeepSeek-R1）验证示例。
 * <p>
 * 验证点：
 * <ul>
 *   <li>enableReasoning(true) + reasoningTimeout 生效（思考耗时长不会被默认 60s 超时掐断）</li>
 *   <li>流式输出（思考过程被过滤，仅推送最终回答的文本增量）</li>
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
        var apiKey = System.getenv("DEEPSEEK_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            System.err.println("❌ 错误：未设置 DEEPSEEK_API_KEY 环境变量");
            System.exit(1);
        }

        var agent = DuoAgent.builder()
                .apiFormat("openai")
                .baseUrl("https://api.deepseek.com")
                .apiKey(apiKey)
                .model("deepseek-reasoner")          // DeepSeek-R1 推理模型
                .contextWindow(64000)
                .enableReasoning(true)               // 启用推理模式
                .reasoningTimeout(Duration.ofMinutes(8))  // 思考最长 8 分钟
                // 注意：不设置 maxOutputTokens——推理模型的 max_tokens 限额包含思考过程
                .build();

        System.out.println("=== 推理模型流式验证（deepseek-reasoner）===\n");

        var start = System.currentTimeMillis();
        var chunkCount = new AtomicInteger();
        var error = new AtomicReference<Throwable>();
        var subscriptionRef = new AtomicReference<Flow.Subscription>();
        var done = new CountDownLatch(1);

        agent.stream("一个笼子里有鸡和兔，共 35 个头、94 只脚，问鸡兔各几只？请推理。")
                .subscribe(new Flow.Subscriber<String>() {
                    @Override
                    public void onSubscribe(Flow.Subscription s) {
                        subscriptionRef.set(s);
                        s.request(Long.MAX_VALUE);
                    }

                    @Override
                    public void onNext(String chunk) {
                        chunkCount.incrementAndGet();
                        System.out.print(chunk);  // 最终回答的文本增量（思考过程已过滤）
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
        System.out.println("收到 " + chunkCount.get() + " 个文本增量，总耗时 " + elapsed + "ms");
        System.out.println("(思考过程未推送；如需展示推理过程，等待 chatEvents() 多事件流支持)");
    }
}
