package dev.duo.core;

import dev.duo.api.DuoAgent;
import dev.duo.api.agent.Agent;
import dev.duo.core.flow.BufferedPublisher;
import dev.duo.core.session.Session;
import dev.duo.model.llm.ContentBlock;
import dev.duo.model.llm.Message;
import dev.duo.model.llm.MessageFactory;
import dev.duo.model.llm.MessageSource;
import dev.duo.model.session.SessionEvent;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.Flow;

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
    public String call(String message) {
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
    public Flow.Publisher<SessionEvent> stream(String message) {
        if (message == null || message.isBlank()) {
            throw new IllegalArgumentException("消息内容不能为空");
        }
        // 冷发布者：session 事件广播全量透传（对应 DSH 的 session/event 订阅模式）。
        // 流式真源是 session 事件广播——adapter 的每个增量已由 ReactLoopAgent
        // 逐 chunk 写入 session，这里订阅广播并透传给订阅者；对话轮由 call() 驱动，
        // 驱动线程正常返回即补发完成信号、抛出异常即转为失败信号
        return new BufferedPublisher<>("duo-agent-stream", emitter -> {
            AutoCloseable unsubscriber = session.onAppend(emitter::emit);
            try {
                call(message);
                emitter.complete();
            } finally {
                try {
                    unsubscriber.close();
                } catch (Exception ignored) {
                    // 取消订阅失败不影响主流程
                }
            }
        });
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
                    "当前 call() API 仅支持提取文本响应。"
            );
        }

        // 多个 Text block 用换行分隔，避免单词边界合并
        return String.join("\n", textParts);
    }

}
