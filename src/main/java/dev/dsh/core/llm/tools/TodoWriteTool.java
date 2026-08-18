package dev.dsh.core.llm.tools;

import dev.dsh.model.llm.ContentBlock;
import dev.dsh.model.llm.ToolDefinition;
import dev.dsh.model.llm.ToolExecutionResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * todo_write 工具：写入任务列表。
 * <p>
 * 对应原版 {@code packages/todo/}。
 * </p>
 */
public class TodoWriteTool {

    private final List<TodoItem> todos = new ArrayList<>();

    public ToolDefinition getDefinition() {
        return new ToolDefinition(
                "todo_write",
                "写入任务列表。每次调用替换整个列表。",
                Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "todos", Map.of(
                                        "type", "array",
                                        "description", "任务列表",
                                        "items", Map.of(
                                                "type", "object",
                                                "properties", Map.of(
                                                        "content", Map.of(
                                                                "type", "string",
                                                                "description", "任务描述"
                                                        ),
                                                        "status", Map.of(
                                                                "type", "string",
                                                                "enum", List.of("pending", "in_progress", "completed"),
                                                                "description", "任务状态"
                                                        )
                                                ),
                                                "required", List.of("content", "status")
                                        )
                                )
                        ),
                        "required", List.of("todos")
                ),
                this::execute
        );
    }

    private ToolExecutionResult execute(Map<String, Object> args) {
        @SuppressWarnings("unchecked")
        var items = (List<Map<String, Object>>) args.get("todos");
        if (items == null) {
            return new ToolExecutionResult("错误：缺少 todos 参数");
        }

        todos.clear();
        for (var item : items) {
            var content = item.get("content");
            var status = item.get("status");
            todos.add(new TodoItem(
                    content != null ? content.toString() : "",
                    status != null ? status.toString() : "pending"
            ));
        }

        var summary = "已写入 " + todos.size() + " 个任务";
        return new ToolExecutionResult(summary);
    }

    public List<TodoItem> getTodos() {
        return List.copyOf(todos);
    }

    public record TodoItem(String content, String status) {}
}