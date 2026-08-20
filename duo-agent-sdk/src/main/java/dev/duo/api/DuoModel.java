package dev.duo.api;

import dev.duo.api.llm.LlmAdapter;
import dev.duo.model.llm.StreamChunk;

import java.time.Duration;
import java.util.concurrent.Flow;

/**
 * 底层模型能力单元 - 无状态的单次 LLM 推理。
 * <p>
 * 职责：
 * <ul>
 *   <li>单次调用（阻塞 / 流式）</li>
 *   <li>模型配置封装（凭证、端点、上下文窗口等）</li>
 *   <li>为 Agent 组装提供适配器工厂</li>
 * </ul>
 * </p>
 * <p>
 * <b>不支持</b>：工具执行、Hook、Session 管理、SessionEvent——这些是 Agent
 * 的编排职责。事件层次上，Model 层只吐模型原生响应单元（{@link StreamChunk}），
 * Agent 层才产生 {@code SessionEvent}。
 * </p>
 * <p>
 * <b>线程安全</b>：实现实例线程安全，可被多线程共享。每次 call/stream
 * 创建独立的底层调用上下文，并发调用互不干扰。
 * </p>
 * <p>
 * <b>异步封装</b>：本接口不提供 callAsync——需要异步时由调用方自行包装：
 * <pre>{@code
 * CompletableFuture.supplyAsync(() -> model.call("问题"), executor);
 * }</pre>
 * 这样调用方保留线程池的选择权，API 保持最小。
 * </p>
 * <p>
 * <b>示例：</b>
 * <pre>{@code
 * DuoModel model = DeepSeekModel.builder()
 *     .apiKey(System.getenv("DEEPSEEK_API_KEY"))
 *     .model("deepseek-chat")
 *     .contextWindow(128000)
 *     .build();
 *
 * String answer = model.call("解释什么是事件溯源");
 * }</pre>
 * </p>
 *
 * @author zhangyl
 * @date 2026-08-20
 * @see dev.duo.model.deepseek.DeepSeekModel
 * @see DuoAgent
 */
public interface DuoModel {

    /**
     * 同步阻塞调用 - 发送 prompt 并等待完整响应文本。
     * <p>
     * 无状态：每次调用独立，不保留历史。<br>
     * 无工具：纯模型推理，不执行工具。<br>
     * 无事件：不产生 SessionEvent，只返回最终文本。
     * </p>
     *
     * @param prompt 用户输入
     * @return 模型的完整响应文本
     * @throws IllegalArgumentException 如果 prompt 为空
     * @throws RuntimeException         如果调用失败或响应不含文本
     */
    String call(String prompt);

    /**
     * 流式调用 - 返回模型原生响应单元流。
     * <p>
     * 返回的是 LLM 底层协议的 {@link StreamChunk}：文本增量（TextDelta）、
     * 推理增量（ReasoningDelta）、块边界（BlockStart/BlockEnd）、用量（Usage）、
     * 终结原因（Finish）等。这不是 Agent 的 {@code SessionEvent}——
     * Agent 的事件流请使用 {@link DuoAgent#stream(String)}。
     * </p>
     * <p>
     * <b>行为契约</b>（与 {@link DuoAgent#stream(String)} 对齐）：
     * <ul>
     *   <li><b>冷发布者</b> - 调用本方法不发起请求，subscribe 时才开始调用</li>
     *   <li><b>背压</b> - 底层适配器为推式回调，{@code request(n)} 无法控制
     *       chunk 到达节奏；消费慢于生产时增量在内部缓冲，溢出以
     *       {@code onError} 终止订阅</li>
     *   <li><b>单订阅</b> - 每次调用返回的 Publisher 仅支持订阅一次，
     *       重复订阅收到 onError</li>
     *   <li><b>取消</b> - {@code cancel()} 停止推送，底层单次调用继续执行完毕</li>
     * </ul>
     * </p>
     *
     * @param prompt 用户输入
     * @return 模型原生响应单元流（冷发布者，订阅时才发起调用）
     * @throws IllegalArgumentException 如果 prompt 为空
     */
    Flow.Publisher<StreamChunk> stream(String prompt);

    /**
     * 创建 LlmAdapter（无参工厂，Model 自用）。
     * <p>
     * 供 {@link #call(String)} / {@link #stream(String)} 内部使用。
     * HTTP 兜底超时按 Model 自身配置计算（普通模式 60s 或推理模式
     * reasoningTimeout，加 1 分钟余量）。同一 Model 实例内部复用同一个
     * 适配器（HttpClient 连接池不随调用重建）。
     * </p>
     * <p>
     * <b>禁止在 Agent 组装路径使用本方法</b>——Agent 的 llmTimeout 可能大于
     * Model 自身假设的应用层超时，组装路径必须使用
     * {@link #createAdapter(Duration)} 传入组装方计算的兜底超时。
     * </p>
     *
     * @return LlmAdapter 实例
     */
    LlmAdapter createAdapter();

    /**
     * 创建 LlmAdapter（带参工厂，Agent 组装用）。
     * <p>
     * <b>为什么需要这个重载</b>：Model 在 build 时不知道自己会被装进什么
     * 超时配置的 Agent，「HTTP 兜底超时 ≥ 应用层最大超时」这条红线只能在
     * 组装时刻由组装方（DuoAgentBuilder）计算应用层最大超时后显式传入，
     * 不能由 Model 单方面决定。否则 Agent 设置更大的 llmTimeout 时，
     * HTTP 层会先于应用层 barrier 掐断 SSE 流。
     * </p>
     *
     * @param httpTimeout 组装方计算的 HTTP 兜底超时（应用层最大超时 + 1 分钟余量）
     * @return 新的 LlmAdapter 实例
     * @throws IllegalArgumentException 如果 httpTimeout 非正，或不大于
     *                                  Model 自身的应用层最大超时（红线校验）
     */
    LlmAdapter createAdapter(Duration httpTimeout);

    /**
     * 获取 API 格式（如 "openai"），同时作为适配器路由的 provider 键。
     *
     * @return API 格式标识
     */
    String getApiFormat();

    /**
     * 获取模型名称（如 "deepseek-chat"）。
     *
     * @return 模型名称
     */
    String getModelName();

    /**
     * 获取系统提示词（可选，未设置时为 null）。
     * <p>
     * Agent 组装时的优先级：Agent 显式 systemPrompt &gt; 本值 &gt; 内置默认。
     * </p>
     *
     * @return 系统提示词，未设置时为 null
     */
    String getSystemPrompt();

    /**
     * 获取上下文窗口大小（可选，未设置时为 null）。
     *
     * @return 上下文窗口 token 数，未设置时为 null
     */
    Integer getContextWindow();

    /**
     * 获取单次响应的最大输出 token 数（可选，未设置时为 null）。
     * <p>
     * 默认不设置，由模型决定输出长度，避免截断推理模型的长输出。
     * </p>
     *
     * @return 最大输出 token 数，未设置时为 null
     */
    Integer getMaxOutputTokens();

    /**
     * 是否启用深度推理模式（如 DeepSeek-R1）。
     *
     * @return true 表示启用
     */
    boolean isReasoningEnabled();

    /**
     * 获取推理模式超时时间（启用推理时生效，默认 5 分钟）。
     *
     * @return 推理超时时间，非 null
     */
    Duration getReasoningTimeout();
}
