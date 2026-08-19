package dev.duo.core.compaction;

import dev.duo.model.llm.ContentBlock;
import dev.duo.model.llm.Message;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 工具调用配对平衡检查：压缩切点两侧不得拆散 assistant 的 tool-call
 * 与对应的 tool/result（提供方会拒绝悬挂调用或孤儿结果）。
 * <p>
 * 对应 TS 源码中的 {@code toolPairingBalancedBefore/After}。
 * </p>
 *
 * @author zhangyl
 * @date 2026-08-19
 */
public final class ToolPairing {

    private ToolPairing() {}

    /**
     * 切点前侧是否平衡：{@code [0, splitIndex)} 内不残留未配对的 tool-call。
     * 不平衡时选区需向前（收缩压缩范围）扩展切点。
     *
     * @param messages 表面消息（模型可见顺序）
     * @param splitIndex 切点（保留区起始索引）
     * @return 前侧无悬挂 tool-call 时 true
     */
    public static boolean balancedBefore(List<Message> messages, int splitIndex) {
        Set<String> pendingCalls = new HashSet<>();
        for (int i = 0; i < splitIndex; i++) {
            collectAndResolve(messages.get(i), pendingCalls);
        }
        return pendingCalls.isEmpty();
    }

    /**
     * 切点后侧是否平衡：{@code [splitIndex, n)} 内不出现无对应调用的孤儿结果。
     * <p>已知调用集取全量消息（后侧自身的调用与结果自包含配对是平衡的；
     * 调用在前、结果在后的跨切点拆散由 {@link #balancedBefore} 检测）。</p>
     *
     * @param messages 表面消息（模型可见顺序）
     * @param splitIndex 切点（保留区起始索引）
     * @return 后侧无孤儿 tool/result 时 true
     */
    public static boolean balancedAfter(List<Message> messages, int splitIndex) {
        Set<String> knownCalls = new HashSet<>();
        for (var message : messages) {
            collectCalls(message, knownCalls);
        }
        for (int i = splitIndex; i < messages.size(); i++) {
            if (messages.get(i) instanceof Message.ToolResultMessage result) {
                var callId = result.source().callId().value();
                if (!knownCalls.contains(callId)) {
                    return false;
                }
            }
        }
        return true;
    }

    /** 收集消息中的 tool-call 声明并消除已出现的配对结果。 */
    private static void collectAndResolve(Message message, Set<String> pendingCalls) {
        switch (message) {
            case Message.AssistantMessage assistant -> {
                for (var block : assistant.content()) {
                    if (block instanceof ContentBlock.ToolCall call) {
                        pendingCalls.add(call.id().value());
                    }
                }
            }
            case Message.ToolResultMessage result -> pendingCalls.remove(result.source().callId().value());
            default -> { /* 用户消息不参与配对 */ }
        }
    }

    /** 只收集调用声明（不消除），用于后侧孤儿检测的已知集合。 */
    private static void collectCalls(Message message, Set<String> calls) {
        if (message instanceof Message.AssistantMessage assistant) {
            for (var block : assistant.content()) {
                if (block instanceof ContentBlock.ToolCall call) {
                    calls.add(call.id().value());
                }
            }
        }
    }
}
