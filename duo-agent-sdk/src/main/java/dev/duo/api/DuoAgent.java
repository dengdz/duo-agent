package dev.duo.api;

import dev.duo.api.agent.Agent;
import dev.duo.core.session.Session;

import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

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
     * 流式对话 - 实时接收响应片段。
     * <p>
     * <b>⚠️ 当前未实现：</b> 此方法目前总是抛出 {@link UnsupportedOperationException}。
     * duo-agent 的 session 事件是完整消息追加，不是增量 chunk。
     * 如需流式功能，请等待 DeepSeekAdapter SSE streaming 实现。
     * </p>
     * <p>
     * 未来支持时，将适合需要即时反馈的场景，如聊天界面。
     * 每当 Agent 生成新的文本片段时，onChunk 回调会被触发。
     * </p>
     * <p>
     * <b>预期示例（未来）：</b>
     * <pre>{@code
     * agent.chatStream("写一个排序算法", chunk -> {
     *     System.out.print(chunk);  // 实时打印每个片段
     * });
     * }</pre>
     * </p>
     *
     * @param message 用户消息
     * @param onChunk 回调函数，每个文本片段会触发一次
     * @throws UnsupportedOperationException 当前总是抛出，因为流式支持未实现
     */
    default void chatStream(String message, Consumer<String> onChunk) {
        throw new UnsupportedOperationException(
                "chatStream() 当前未实现真正的流式支持。" +
                "duo-agent 的 session 事件是完整消息追加，不是增量 chunk。" +
                "如需流式功能，请等待 DeepSeekAdapter SSE streaming 实现。"
        );
    }

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
