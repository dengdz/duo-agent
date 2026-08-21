package dev.duo.api.agent;

import dev.duo.model.llm.Message;
import dev.duo.util.MessageId;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 耐久 Agent inbox 事件的增量投影。
 * <p>
 * 维护两个有序列表：{@code next-turn}（等待单个轮次的提示）和
 * {@code next-step}（等待下一个 step 边界的输入）。
 * 所有变更先记录到 Session 日志，再更新内存投影。
 * </p>
 *
 * @author zhangyl
 * @date 2026-08-18
 */
public class Inbox {

    private final Map<InboxTarget, List<Message>> state = new EnumMap<>(InboxTarget.class);

    public Inbox() {
        state.put(InboxTarget.NEXT_TURN, new ArrayList<>());
        state.put(InboxTarget.NEXT_STEP, new ArrayList<>());
    }

    /** 等待单个轮次的提示。 */
    public synchronized List<Message> nextTurn() {
        return Collections.unmodifiableList(state.get(InboxTarget.NEXT_TURN));
    }

    /** 等待下一个 step 边界的输入。 */
    public synchronized List<Message> nextStep() {
        return Collections.unmodifiableList(state.get(InboxTarget.NEXT_STEP));
    }

    /** 任一待处理消息列表是否包含工作。 */
    public synchronized boolean hasPending() {
        return !state.get(InboxTarget.NEXT_TURN).isEmpty()
                || !state.get(InboxTarget.NEXT_STEP).isEmpty();
    }

    /**
     * 耐久地取消所有待处理输入，先清除 next-step，再清除 next-turn。
     */
    public synchronized void clear() {
        state.get(InboxTarget.NEXT_STEP).clear();
        state.get(InboxTarget.NEXT_TURN).clear();
    }

    /**
     * 移除并返回为一个 step 提议的完整批次。
     * @param target 是否也消耗一个排队的轮次
     * @return next-step 输入，后跟排队的轮次（如果请求了）
     */
    public synchronized List<Message> claim(InboxTarget target) {
        var claimed = new ArrayList<>(state.get(InboxTarget.NEXT_STEP));
        state.get(InboxTarget.NEXT_STEP).clear();

        if (target == InboxTarget.NEXT_TURN) {
            var turnList = state.get(InboxTarget.NEXT_TURN);
            if (!turnList.isEmpty()) {
                claimed.add(turnList.removeFirst());
            }
        }

        return claimed;
    }

    /**
     * 将一条消息追加到待处理列表。
     * @param target 要扩展的待处理列表
     * @param message 要追加的消息
     */
    public synchronized void append(InboxTarget target, Message message) {
        state.get(target).addLast(message);
    }

    /**
     * 将一条消息插入到待处理列表开头。
     * @param target 要扩展的待处理列表
     * @param message 要插入的消息
     */
    public synchronized void prepend(InboxTarget target, Message message) {
        state.get(target).addFirst(message);
    }

    /**
     * 替换一条待处理消息。
     * @param messageId 要替换的待处理消息标识
     * @param newMessage 替换消息
     * @return 消息是否仍在待处理中
     */
    public synchronized boolean replace(MessageId messageId, Message newMessage) {
        var location = locate(messageId);
        if (location == null) {
            return false;
        }
        state.get(location.target).set(location.index, newMessage);
        return true;
    }

    /**
     * 移除一条待处理消息。
     * @param messageId 要移除的待处理消息标识
     * @return 消息是否仍在待处理中
     */
    public synchronized boolean remove(MessageId messageId) {
        var location = locate(messageId);
        if (location == null) {
            return false;
        }
        state.get(location.target).remove(location.index);
        return true;
    }

    private synchronized MessageLocation locate(MessageId messageId) {
        for (var target : List.of(InboxTarget.NEXT_TURN, InboxTarget.NEXT_STEP)) {
            var list = state.get(target);
            for (int i = 0; i < list.size(); i++) {
                if (list.get(i).id().equals(messageId)) {
                    return new MessageLocation(target, i, list.get(i));
                }
            }
        }
        return null;
    }

    private record MessageLocation(InboxTarget target, int index, Message message) {}
}