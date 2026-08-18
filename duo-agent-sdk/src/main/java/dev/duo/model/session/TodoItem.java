package dev.duo.model.session;

/**
 * 一个 agent 的 todo 列表条目——{@code todo/write} 事件的整表快照单元。
 * <p>
 * 对应 TS 源码中的 {@code TodoItem}。
 * </p>
 *
 * @author zhangyl
 * @date 2026-08-18
 */
public record TodoItem(
        /** 任务描述。 */
        String content,
        /** 生命周期状态。 */
        TodoStatus status
) {
    public TodoItem {
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("content 不能为空");
        }
        if (status == null) {
            throw new IllegalArgumentException("status 不能为空");
        }
    }
}