package dev.duo.api;

import dev.duo.api.agent.Agent;
import dev.duo.core.session.Session;
import dev.duo.model.session.SessionEvent;
import dev.duo.model.session.TurnEndReason;

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
     * 多事件流对话 - 返回完整 Session 事件日志的响应式流。
     * <p>
     * 与 {@link #stream(String)}（仅推送文本增量）不同，本方法<b>全量透传</b>
     * session 事件（{@link SessionEvent}，15 种类型）——订阅者可完整观察
     * Agent 的工作过程：思考推理（ReasoningDelta）、工具调用与结果、
     * step 边界、turn 结束原因等，适合渲染类似 IDE Agent 的工作过程界面。
     * 事件信封原样透传（含 {@code seq} 单调递增序号，未来断线重连的基础）。
     * </p>
     * <p>
     * <b>单 turn 事件时序：</b>
     * <pre>{@code
     * turn/start
     * └─ step/start
     *    user/message                       （本 step 进入的输入）
     *    assistant/chunk × N                （流式增量：文本/思考/工具参数）
     *    assistant/message                  （组装完成的完整消息，usage 随行，
     *                                        sourceEventSeqs 回链 chunk seq）
     *    (tool/call → tool/result) × K      （逐对交错：先发某工具的 call，
     *                                        执行完毕紧随其 result，再进入下一个工具）
     * └─ step/end                           （finally 必发）
     * turn/end                              （finally 必发，携带结束原因）
     * }</pre>
     * </p>
     * <p>
     * <b>完成信号：</b>{@code turn/end} 事件即整个对话轮的权威结束信号，
     * {@link TurnEndReason} 区分 6 种结束原因（completed / aborted / blocked /
     * error / max-tokens / interrupted）。
     * </p>
     * <p>
     * <b>示例：</b>
     * <pre>{@code
     * agent.chatEvents("分析当前目录的代码").subscribe(new Flow.Subscriber<>() {
     *     private Flow.Subscription subscription;
     *     public void onSubscribe(Flow.Subscription s) {
     *         subscription = s;
     *         s.request(Long.MAX_VALUE);
     *     }
     *     public void onNext(SessionEvent event) {
     *         switch (event) {
     *             case SessionEventAssistantChunk c when c.chunk() instanceof StreamChunk.ReasoningDelta r
     *                     -> showThinking(r.text());          // 思考过程
     *             case SessionEventAssistantChunk c when c.chunk() instanceof StreamChunk.TextDelta t
     *                     -> showAnswer(t.text());            // 回答文本
     *             case SessionEventToolCall call -> showTool(call.name(), call.arguments());
     *             case SessionEventToolResult result -> showResult(result.message());
     *             case SessionEventTurnEnd end -> finish(end.reason());
     *             default -> { /* 其余类型按需处理 *&#47; }
     *         }
     *     }
     *     public void onError(Throwable t) { ... }
     *     public void onComplete() { ... }
     * });
     * }</pre>
     * </p>
     * <p>
     * <b>行为说明：</b>与 {@link #stream(String)} 一致——冷发布者（订阅时才发起对话）、
     * 背压（{@code request(n)}）、取消（停止推送但对话轮继续执行完毕）、单订阅
     * （重复订阅收到 onError）。事件不做服务端过滤，订阅者按需 {@code instanceof}
     * 模式匹配自行取用。<b>慢消费者保护：</b>订阅者不及时 {@code request} 时事件在
     * 内部缓冲（上限 8192 个，推理模型的超长思考痕迹可能触达），溢出即以
     * {@code onError} 终止订阅。
     * todo/write、request/header、request/context 三类事件
     * 当前主流程尚无产生点，出现时按需处理即可。
     * </p>
     *
     * @param message 用户消息
     * @return Session 事件流（冷发布者，订阅时才发起对话）
     * @throws IllegalArgumentException 如果 message 为空
     * @see SessionEvent
     * @see TurnEndReason
     */
    Flow.Publisher<SessionEvent> chatEvents(String message);

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
