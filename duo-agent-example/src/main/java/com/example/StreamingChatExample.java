package com.example;

import dev.duo.api.DuoAgent;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 流式输出演示 - 直观感受 stream() 响应式流的逐字到达效果。
 * <p>
 * 订阅 {@link Flow.Publisher}（JDK 原生 Reactive Streams），
 * 文字实时逐段打印（而非等全部生成完一次性输出），结束时统计增量数量与耗时。
 * Spring WebFlux 用户可用 {@code Flux.from(agent.stream(...))} 一行桥接。
 * </p>
 *
 * @author zhangyl
 * @date 2026-08-19
 */
public class StreamingChatExample {

    public static void main(String[] args) throws InterruptedException {
        var apiKey = System.getenv("DEEPSEEK_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            System.err.println("❌ 错误：未设置 DEEPSEEK_API_KEY 环境变量");
            System.err.println("   IntelliJ 中可在 Run Configuration → Environment variables 里设置");
            System.exit(1);
        }

        var agent = DuoAgent.builder()
                .apiFormat("openai")
                .baseUrl("https://api.deepseek.com")
                .apiKey(apiKey)
                .model("deepseek-chat")
                .contextWindow(128000)
                .build();

        System.out.println("=== stream() 流式输出演示 ===");
        System.out.println("（文字会实时逐段打印，注意观察输出节奏）\n");

        var start = System.currentTimeMillis();
        // AtomicInteger 保证跨线程可见（onNext 在驱动线程回调）
        var chunkCount = new AtomicInteger();
        var done = new CountDownLatch(1);

        // 冷发布者：subscribe 时才发起对话
        agent.stream("用 3 段话介绍一下 Java 虚拟机的内存模型，每段 100 字左右。")
                .subscribe(new Flow.Subscriber<String>() {
                    private Flow.Subscription subscription;

                    @Override
                    public void onSubscribe(Flow.Subscription s) {
                        this.subscription = s;
                        s.request(Long.MAX_VALUE);  // 演示场景无限拉取
                    }

                    @Override
                    public void onNext(String chunk) {
                        chunkCount.incrementAndGet();
                        // 不换行直接打印，肉眼可见逐字到达
                        System.out.print(chunk);
                    }

                    @Override
                    public void onError(Throwable t) {
                        System.err.println("\n\n❌ 流式出错: " + t.getMessage());
                        done.countDown();
                    }

                    @Override
                    public void onComplete() {
                        done.countDown();
                    }
                });

        done.await();

        var elapsed = System.currentTimeMillis() - start;
        System.out.println("\n\n=== 完成 ===");
        System.out.println("收到 " + chunkCount.get() + " 个文本增量，耗时 " + elapsed + "ms");
    }
}
