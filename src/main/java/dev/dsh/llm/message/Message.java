package dev.dsh.llm.message;

import dev.dsh.llm.types.ContentBlock;
import dev.dsh.util.MessageId;

import java.util.List;

/**
 * 不可变消息表示，在传递、持久化历史和模型请求之间共享。
 * <p>
 * 对应 TS 源码中的 {@code Message}。
 * </p>
 */
public sealed interface Message {

    /** 跨所有表示边界保持稳定的标识。 */
    MessageId id();

    /** 提供方中立的对话角色。 */
    String role();

    /** 精确的模型面向内容块。 */
    List<ContentBlock> content();

    /** 生产者提供的来源字段。 */
    MessageSource source();

    /** 用户角色的消息。 */
    record UserMessage(
            MessageId id,
            List<ContentBlock> content,
            MessageSource source
    ) implements Message {
        @Override
        public String role() {
            return "user";
        }
    }

    /** 模型产出的助手消息。 */
    record AssistantMessage(
            MessageId id,
            List<ContentBlock> content,
            MessageSource.Model source
    ) implements Message {
        @Override
        public String role() {
            return "assistant";
        }
    }

    /** 工具结果消息，其模型面向块保留调用关联关系。 */
    record ToolResultMessage(
            MessageId id,
            List<ContentBlock> content,
            MessageSource.Tool source
    ) implements Message {
        @Override
        public String role() {
            return "user";
        }
    }
}