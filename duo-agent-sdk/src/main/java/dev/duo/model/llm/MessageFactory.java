package dev.duo.model.llm;

import dev.duo.util.CallId;
import dev.duo.util.MessageId;

import java.util.List;
import java.util.Objects;

/**
 * 不可变消息的工厂方法。
 * <p>
 * 所有返回的消息都是不可变的（内容列表不可修改）。
 * </p>
 *
 * @author zhangyl
 * @date 2026-08-18
 */
public final class MessageFactory {

    private MessageFactory() {
    }

    /** 创建一条用户角色消息。 */
    public static Message.UserMessage createUserMessage(
            List<ContentBlock> content,
            MessageSource source
    ) {
        Objects.requireNonNull(content, "content 不能为 null");
        Objects.requireNonNull(source, "source 不能为 null");
        return new Message.UserMessage(
                MessageId.random(),
                List.copyOf(content),
                source
        );
    }

    /** 创建一条模型产出的助手消息。 */
    public static Message.AssistantMessage createAssistantMessage(
            List<ContentBlock> content,
            String provider,
            String model
    ) {
        Objects.requireNonNull(content, "content 不能为 null");
        Objects.requireNonNull(provider, "provider 不能为 null");
        Objects.requireNonNull(model, "model 不能为 null");
        return new Message.AssistantMessage(
                MessageId.random(),
                List.copyOf(content),
                new MessageSource.Model(provider, model)
        );
    }

    /** 创建一条工具结果消息。 */
    public static Message.ToolResultMessage createToolResultMessage(
            CallId callId,
            List<ContentBlock> content,
            boolean isError
    ) {
        Objects.requireNonNull(callId, "callId 不能为 null");
        Objects.requireNonNull(content, "content 不能为 null");
        var toolResultBlock = new ContentBlock.ToolResult(callId, List.copyOf(content), isError);
        return new Message.ToolResultMessage(
                MessageId.random(),
                List.of(toolResultBlock),
                new MessageSource.Tool(callId)
        );
    }
}