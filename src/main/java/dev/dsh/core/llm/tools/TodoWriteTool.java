package dev.dsh.core.llm.tools;

import dev.dsh.model.llm.ToolDefinition;
import dev.dsh.model.llm.ToolExecutionResult;
import dev.dsh.model.session.TodoItem;
import dev.dsh.model.session.TodoStatus;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * todo_write 工具：写入任务列表。
 * <p>
 * 对应原版 {@code packages/todo/}。
 * </p>
 *
 * @author zhangyl
 * @date 2026-08-18
 */
public class TodoWriteTool {

    private static final String ARG_TODOS = "todos";
    private static final String ARG_CONTENT = "content";
    private static final String ARG_STATUS = "status";

    private final List<TodoItem> todos = new ArrayList<>();

    public ToolDefinition getDefinition() {
        return new ToolDefinition(
                "todo_write",
                "写入任务列表。每次调用替换整个列表。",
                Map.of(
                        "type", "object",
                        "properties", Map.of(
                                ARG_TODOS, Map.of(
                                        "type", "array",
                                        "description", "任务列表",
                                        "items", Map.of(
                                                "type", "object",
                                                "properties", Map.of(
                                                        ARG_CONTENT, Map.of(
                                                                "type", "string",
                                                                "description", "任务描述"
                                                        ),
                                                        ARG_STATUS, Map.of(
                                                                "type", "string",
                                                                "enum", TodoStatus.protocolValues(),
                                                                "description", "任务状态"
                                                        )
                                                ),
                                                "required", List.of(ARG_CONTENT, ARG_STATUS)
                                        )
                                )
                        ),
                        "required", List.of(ARG_TODOS)
                ),
                this::execute
        );
    }

    private ToolExecutionResult execute(Map<String, Object> args) {
        @SuppressWarnings("unchecked")
        var items = (List<Map<String, Object>>) args.get(ARG_TODOS);
        if (items == null) {
            return new ToolExecutionResult("错误：缺少 todos 参数");
        }

        todos.clear();
        var skipped = 0;
        for (var item : items) {
            var content = item.get(ARG_CONTENT);
            var status = item.get(ARG_STATUS);
            if (content == null || content.toString().isBlank()) {
                skipped++;
                continue;
            }
            TodoStatus todoStatus = TodoStatus.PENDING;
            if (status != null && !status.toString().isBlank()) {
                try {
                    todoStatus = TodoStatus.fromProtocol(status.toString());
                } catch (IllegalArgumentException e) {
                    skipped++;
                    continue;
                }
            }
            todos.add(new TodoItem(content.toString(), todoStatus));
        }

        var summary = "已写入 " + todos.size() + " 个任务";
        if (skipped > 0) {
            summary += "（跳过 " + skipped + " 个无效条目）";
        }
        return new ToolExecutionResult(summary);
    }

    public List<TodoItem> getTodos() {
        return List.copyOf(todos);
    }
}