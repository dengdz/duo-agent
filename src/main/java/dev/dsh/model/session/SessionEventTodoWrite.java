package dev.dsh.model.session;

import java.util.List;

/**
 * todo 列表写入事件。
 *
 * @author zhangyl
 * @date 2026-08-18
 */
public record SessionEventTodoWrite(
        int seq, long time, boolean ignorable,
        SurfaceOp surfaceOp, int[] sourceEventSeqs,
        List<TodoItem> todos
) implements SessionEvent {
    public SessionEventTodoWrite(int seq, List<TodoItem> todos) {
        this(seq, System.currentTimeMillis(), false, null, null, todos);
    }
    @Override
    public String type() {
        return SessionEventTypes.TODO_WRITE;
    }
}
