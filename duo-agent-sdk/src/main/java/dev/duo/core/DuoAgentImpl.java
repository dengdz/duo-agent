package dev.duo.core;

import dev.duo.api.DuoAgent;
import dev.duo.api.agent.Agent;
import dev.duo.core.session.Session;
import dev.duo.model.llm.ContentBlock;
import dev.duo.model.llm.Message;
import dev.duo.model.llm.MessageFactory;
import dev.duo.model.llm.MessageSource;
import dev.duo.model.llm.StreamChunk;
import dev.duo.model.session.SessionEvent;
import dev.duo.model.session.SessionEventAssistantChunk;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * DuoAgent 接口的默认实现。
 * <p>
 * 包装底层的 {@link Agent} 和 {@link Session}，提供简化的对话 API。
 * </p>
 *
 * @author zhangyl
 * @date 2026-08-19
 */
public final class DuoAgentImpl implements DuoAgent {

    private final Agent agent;
    private final Session session;

    /**
     * 构造器，由 {@link dev.duo.api.DuoAgentBuilder} 调用。
     * <p>
     * 添加 null 验证，fail-fast 避免晦涩的 NPE。
     * </p>
     *
     * @param agent   底层 Agent 实例
     * @param session Session 实例
     */
    public DuoAgentImpl(Agent agent, Session session) {
        this.agent = Objects.requireNonNull(agent, "agent 不能为 null");
        this.session = Objects.requireNonNull(session, "session 不能为 null");
    }

    @Override
    public String chat(String message) {
        if (message == null || message.isBlank()) {
            throw new IllegalArgumentException("消息内容不能为空");
        }

        // 记录发送前的消息计数，用于验证是否生成了新响应
        var messageCountBefore = session.deriveMessages().size();

        // 创建用户消息
        var userMessage = MessageFactory.createUserMessage(
                List.of(new ContentBlock.Text(message)),
                new MessageSource.User()
        );

        // 发送给 Agent
        agent.followup(userMessage);

        // 等待 Agent 完成
        try {
            agent.whenIdle();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Agent 执行被中断", e);
        }

        // 验证是否生成了新的 Assistant 消息
        var messageCountAfter = session.deriveMessages().size();
        if (messageCountAfter <= messageCountBefore + 1) {
            // 只有用户消息，没有新的 Assistant 响应
            throw new IllegalStateException(
                    "Agent 执行完成但未生成新的响应消息。" +
                    "这可能是 Agent 执行失败或 whenIdle() 内部异常被吞掉导致的。"
            );
        }

        // 提取最后一条 Assistant 消息
        return extractLastAssistantMessage();
    }

    @Override
    public CompletableFuture<String> chatAsync(String message) {
        // 使用 commonPool 执行阻塞 I/O 不是最佳实践，但对于简化 API 可以接受
        // 生产环境建议使用自定义线程池
        return CompletableFuture.supplyAsync(() -> chat(message));
    }

    @Override
    public Flow.Publisher<String> stream(String message) {
        if (message == null || message.isBlank()) {
            throw new IllegalArgumentException("消息内容不能为空");
        }
        return new ChatStreamPublisher(message);
    }

    /**
     * 冷发布者：subscribe 时才在虚拟线程上驱动 {@link #chat(String)}，
     * 流式真源是 session 事件广播（对应 DSH 的 session/event 订阅模式）——
     * adapter 的每个 TextDelta 已由 ReactLoopAgent 逐 chunk 写入 session，
     * 这里订阅广播并过滤出文本增量（ReasoningDelta 是思考过程噪音、
     * ToolCallDelta 是 JSON 参数碎片，均不推送）。
     */
    private final class ChatStreamPublisher implements Flow.Publisher<String> {

        private final String message;
        private final AtomicBoolean subscribedOnce = new AtomicBoolean();

        ChatStreamPublisher(String message) {
            this.message = message;
        }

        @Override
        public void subscribe(Flow.Subscriber<? super String> subscriber) {
            Objects.requireNonNull(subscriber, "subscriber 不能为 null");
            // 单订阅：重复订阅无法共享同一次对话轮
            if (!subscribedOnce.compareAndSet(false, true)) {
                subscriber.onSubscribe(new Flow.Subscription() {
                    @Override
                    public void request(long n) {
                        // no-op：即将进入终态
                    }

                    @Override
                    public void cancel() {
                        // no-op
                    }
                });
                subscriber.onError(new IllegalStateException(
                        "stream() 返回的 Publisher 仅支持订阅一次，请重新调用 stream(message)"));
                return;
            }
            new ChatStreamSubscription(subscriber).start();
        }

        /** 单个订阅的状态机：缓冲 + 背压 + 终态信号。 */
        private final class ChatStreamSubscription implements Flow.Subscription {

            /** 完成哨兵（正常终态）。 */
            private static final Object TERMINAL = new Object();

            private final Flow.Subscriber<? super String> subscriber;
            /** 元素为 String（文本增量）、TERMINAL（完成）或 StreamError（失败）。 */
            private final BlockingQueue<Object> buffer = new LinkedBlockingQueue<>();
            private final AtomicLong demand = new AtomicLong();
            private final AtomicBoolean cancelled = new AtomicBoolean();
            /** 终态（onComplete/onError）是否已发出，保证恰好一次。 */
            private volatile boolean terminated;

            ChatStreamSubscription(Flow.Subscriber<? super String> subscriber) {
                this.subscriber = subscriber;
            }

            void start() {
                subscriber.onSubscribe(this);
                // 虚拟线程驱动对话轮（与 ReactLoopAgent 内部驱动方式一致）
                Thread.ofVirtual().name("duo-chat-stream").start(() -> {
                    AutoCloseable unsubscriber = session.onAppend(event -> {
                        if (event instanceof SessionEventAssistantChunk chunkEvent
                                && chunkEvent.chunk() instanceof StreamChunk.TextDelta delta) {
                            emit(delta.text());
                        }
                    });
                    try {
                        chat(message);
                        emit(TERMINAL);
                    } catch (Throwable error) {
                        emit(new StreamError(error));
                    } finally {
                        try {
                            unsubscriber.close();
                        } catch (Exception ignored) {
                            // 取消订阅失败不影响主流程
                        }
                    }
                });
            }

            private void emit(Object item) {
                if (cancelled.get()) {
                    return;
                }
                buffer.add(item);
                drain();
            }

            /** 串行派发：synchronized 保证 onNext/onComplete/onError 不重入、不并发。 */
            private void drain() {
                synchronized (this) {
                    if (terminated || cancelled.get()) {
                        buffer.clear();
                        return;
                    }
                    while (true) {
                        var item = demand.get() > 0 ? buffer.poll() : peekTerminal();
                        if (item == null) {
                            return;
                        }
                        if (item == TERMINAL) {
                            terminated = true;
                            subscriber.onComplete();
                            return;
                        }
                        if (item instanceof StreamError error) {
                            terminated = true;
                            subscriber.onError(error.cause());
                            return;
                        }
                        demand.decrementAndGet();
                        subscriber.onNext((String) item);
                    }
                }
            }

            /** 无 demand 时终态信号（完成/错误）不受背压限制，仍需立即派发（Reactive Streams 3.5 精神）。 */
            private Object peekTerminal() {
                var head = buffer.peek();
                return head == TERMINAL || head instanceof StreamError ? buffer.poll() : null;
            }

            @Override
            public void request(long n) {
                if (n <= 0) {
                    // Reactive Streams 规范 3.9：非正数 request 是协议违规
                    subscriber.onError(new IllegalArgumentException(
                            "request 参数必须为正数（Reactive Streams 3.9），当前: " + n));
                    cancel();
                    return;
                }
                // 溢出时饱和为 Long.MAX_VALUE（视为无限需求）
                demand.accumulateAndGet(n, (current, add) ->
                        current > Long.MAX_VALUE - add ? Long.MAX_VALUE : current + add);
                drain();
            }

            @Override
            public void cancel() {
                cancelled.set(true);
                synchronized (this) {
                    buffer.clear();
                }
            }
        }
    }

    /** 流内失败信号载体。 */
    private record StreamError(Throwable cause) {
    }

    @Override
    public Agent getAgent() {
        return agent;
    }

    @Override
    public Session getSession() {
        return session;
    }

    // ==================== 私有辅助方法 ====================

    /**
     * 从 session 中提取最后一条 Assistant 纯文本消息。
     *
     * @throws IllegalStateException 如果没有找到 Assistant 消息
     */
    private String extractLastAssistantMessage() {
        var messages = session.deriveMessages();

        // 从后往前查找第一条 AssistantMessage
        for (int i = messages.size() - 1; i >= 0; i--) {
            var msg = messages.get(i);
            if (msg instanceof Message.AssistantMessage assistantMsg) {
                return extractTextFromContent(assistantMsg.content());
            }
        }

        // 失败时抛异常而不是静默返回空字符串
        throw new IllegalStateException(
                "Agent 执行完成但未生成 Assistant 消息。" +
                "这可能是 Agent 执行失败或配置错误导致的。"
        );
    }

    /**
     * 从 ContentBlock 列表中提取纯文本。
     * <p>
     * 多个 Text block 用换行分隔，空提取抛异常。
     * </p>
     */
    private String extractTextFromContent(List<ContentBlock> content) {
        var textParts = new java.util.ArrayList<String>();
        for (var block : content) {
            if (block instanceof ContentBlock.Text textBlock) {
                textParts.add(textBlock.text());
            }
        }
        
        // 空提取时抛异常（纯 ToolCall 或 Reasoning 响应）
        if (textParts.isEmpty()) {
            throw new IllegalStateException(
                    "Assistant 消息不包含文本内容（可能是纯 ToolCall 或 Reasoning 响应）。" +
                    "当前 chat() API 仅支持提取文本响应。"
            );
        }
        
        // 多个 Text block 用换行分隔，避免单词边界合并
        return String.join("\n", textParts);
    }

}
