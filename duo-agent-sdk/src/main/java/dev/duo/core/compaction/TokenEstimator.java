package dev.duo.core.compaction;

import dev.duo.model.llm.ContentBlock;
import dev.duo.model.llm.Message;

import java.util.List;

/**
 * 基于字符数的 token 估算器（约 4 字符 = 1 token）。
 * <p>
 * 对应 TS 源码中 {@code token-meter} 的 v1 简化：不做回放精确计价，
 * 只为压缩的阈值与选区提供一致的相对度量。
 * </p>
 *
 * @author zhangyl
 * @date 2026-08-19
 */
public final class TokenEstimator {

    /** 平均每 token 的字符数。 */
    private static final int CHARS_PER_TOKEN = 4;
    /** 每条消息的固定开销（估算）：角色与分界。 */
    private static final int PER_MESSAGE_OVERHEAD_TOKENS = 4;

    private TokenEstimator() {}

    /** 估算一条消息的 token 数。 */
    public static int estimate(Message message) {
        int chars = 0;
        for (var block : contentOf(message)) {
            chars += switch (block) {
                case ContentBlock.Text t -> t.text().length();
                case ContentBlock.Reasoning r -> r.text().length();
                case ContentBlock.ToolCall c -> c.name().length() + c.arguments().length();
                case ContentBlock.ToolResult r -> contentChars(r.content());
            };
        }
        return chars / CHARS_PER_TOKEN + PER_MESSAGE_OVERHEAD_TOKENS;
    }

    /** 估算一组消息的 token 总数。 */
    public static int estimateAll(List<Message> messages) {
        int total = 0;
        for (var message : messages) {
            total += estimate(message);
        }
        return total;
    }

    /** 逐条消息的估算值（选区算法的输入）。 */
    public static int[] estimateEach(List<Message> messages) {
        var result = new int[messages.size()];
        for (int i = 0; i < messages.size(); i++) {
            result[i] = estimate(messages.get(i));
        }
        return result;
    }

    private static int contentChars(List<ContentBlock> blocks) {
        int chars = 0;
        for (var block : blocks) {
            if (block instanceof ContentBlock.Text text) {
                chars += text.text().length();
            } else if (block instanceof ContentBlock.Reasoning reasoning) {
                chars += reasoning.text().length();
            }
        }
        return chars;
    }

    private static List<ContentBlock> contentOf(Message message) {
        return switch (message) {
            case Message.UserMessage m -> m.content();
            case Message.AssistantMessage m -> m.content();
            case Message.ToolResultMessage m -> m.content();
        };
    }
}
