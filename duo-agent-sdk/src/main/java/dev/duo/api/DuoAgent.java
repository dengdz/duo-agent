package dev.duo.api;

import dev.duo.api.agent.Agent;
import dev.duo.core.session.Session;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;

/**
 * Duo Agent 简化 API - 开箱即用的 AI Agent 门面。
 * <p>
 * 这是推荐的高层 API，隐藏了底层复杂性。提供同步、异步和流式三种对话模式。
 * </p>
 * <p>
 * <b>快速开始示例：</b>
 * <pre>{@code
 * var agent = DuoAgent.builder()
 *     .apiFormat("openai")
 *     .baseUrl("https://api.deepseek.com")
 *     .apiKey(System.getenv("DEEPSEEK_API_KEY"))
 *     .model("deepseek-chat")
 *     .contextWindow(128000)
 *     // 可选：限制输出长度。推理模型（如 deepseek-reasoner）建议不设置，由模型决定
 *     // .maxOutputTokens(4096)
 *     .withFileTools()
 *     .build();
 *
 * String response = agent.chat("列出当前目录的 Java 文件");
 * System.out.println(response);
 * }</pre>
 * </p>
 *
 * @author zhangyl
 * @date 2026-08-19
 */
public interface DuoAgent {

    /**
     * 创建 Agent 构建器。
     * <p>
     * 这是创建 Agent 的推荐方式，使用 Fluent API 配置所有选项。
     * </p>
     *
     * @return Agent 构建器
     */
    static DuoAgentBuilder builder() {
        return new DuoAgentBuilder();
    }

    /**
     * 同步对话 - 发送消息并等待完整响应。
     * <p>
     * 这是最简单的使用方式，适合大多数场景。方法会阻塞直到 Agent 完成响应。
     * </p>
     * <p>
     * <b>示例：</b>
     * <pre>{@code
     * String response = agent.chat("帮我分析这段代码");
     * System.out.println(response);
     * }</pre>
     * </p>
     *
     * @param message 用户消息
     * @return Agent 的完整响应文本
     * @throws RuntimeException 如果对话过程中发生错误
     */
    String chat(String message);

    /**
     * 异步对话 - 非阻塞方式发送消息。
     * <p>
     * 适合在 UI 线程中避免阻塞的场景。
     * </p>
     * <p>
     * <b>注意：</b>同一 DuoAgent 实例共享底层 Session，不是线程安全的。
     * 请勿对同一实例并发调用 chat/chatAsync，如需并发处理多个请求，
     * 请为每个请求创建独立的 Agent 实例。
     * </p>
     * <p>
     * <b>示例：</b>
     * <pre>{@code
     * agent.chatAsync("帮我分析代码")
     *      .thenAccept(response -> System.out.println("响应: " + response))
     *      .exceptionally(error -> {
     *          System.err.println("错误: " + error.getMessage());
     *          return null;
     *      });
     * }</pre>
     * </p>
     *
     * @param message 用户消息
     * @return CompletableFuture，完成时包含 Agent 响应
     */
    CompletableFuture<String> chatAsync(String message);

    /**
     * 流式对话 - 返回文本增量的响应式流。
     * <p>
     * 基于 JDK 原生 {@link Flow.Publisher}（Reactive Streams 规范），
     * 零第三方依赖。Spring WebFlux 用户可用 {@code Flux.from(...)} 一行桥接，
     * RxJava 用户可用 {@code Flowable.fromPublisher(...)}。
     * </p>
     * <p>
     * <b>冷发布者：</b>调用本方法不发起任何请求，{@code subscribe} 订阅时才开始对话。
     * 文本增量实时推送（{@code onNext}），对话轮结束时发送 {@code onComplete}；
     * 执行失败时发送 {@code onError}。完整响应文本可由订阅者自行拼接增量获得。
     * </p>
     * <p>
     * <b>示例：</b>
     * <pre>{@code
     * agent.stream("写一个排序算法").subscribe(new Flow.Subscriber<>() {
     *     private Flow.Subscription subscription;
     *     public void onSubscribe(Flow.Subscription s) {
     *         subscription = s;
     *         s.request(Long.MAX_VALUE);  // 或按需分批 request(n)
     *     }
     *     public void onNext(String chunk) {
     *         System.out.print(chunk);  // 实时打印每个文本增量
     *     }
     *     public void onError(Throwable t) { t.printStackTrace(); }
     *     public void onComplete() { /* 对话轮结束 *&#47; }
     * });
     *
     * // Spring WebFlux 用户
     * Flux<String> flux = Flux.from(agent.stream("写一个排序算法"));
     * }</pre>
     * </p>
     * <p>
     * <b>行为说明：</b>
     * <ul>
     *   <li><b>仅推送文本增量</b> - 推理内容（{@code <think>} 思考过程）和
     *       工具调用参数不会推送给订阅者</li>
     *   <li><b>多轮工具调用</b> - Agent 使用工具后会再次调用模型，
     *       订阅者会收到多段连续的文本流</li>
     *   <li><b>背压</b> - 通过 {@code request(n)} 控制拉取节奏；
     *       消费慢于生产时增量在内部缓冲，不会丢失</li>
     *   <li><b>取消语义</b> - {@code cancel()} 停止推送并释放资源，
     *       但底层对话轮会继续执行完毕（不中断模型推理，仍消耗一次 API 调用）</li>
     *   <li><b>单订阅</b> - 每次调用返回的 Publisher 仅支持订阅一次，
     *       重复订阅将收到 {@code onError}</li>
     *   <li><b>线程模型</b> - {@code onNext} 等回调可能在驱动线程上执行，
     *       订阅者需保证自身处理的线程安全（如需固定线程请在订阅者内自行切换）</li>
     * </ul>
     * </p>
     *
     * @param message 用户消息
     * @return 文本增量流（冷发布者，订阅时才发起对话）
     * @throws IllegalArgumentException 如果 message 为空
     */
    Flow.Publisher<String> stream(String message);

    /**
     * 获取底层 Agent 实例（高级用户）。
     * <p>
     * 如果需要访问底层 API 进行更精细的控制，可以使用此方法。
     * 大多数用户不需要直接使用底层 Agent。
     * </p>
     *
     * @return 底层 Agent 实例
     */
    Agent getAgent();

    /**
     * 获取会话实例（高级用户）。
     * <p>
     * 如果需要访问会话历史、事件日志等，可以使用此方法。
     * </p>
     *
     * @return 会话实例
     */
    Session getSession();
}
