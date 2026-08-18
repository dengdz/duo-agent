package dev.duo.tool;

import dev.duo.model.session.TodoStatus;
import dev.duo.util.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link TodoWriteTool} 的测试。
 *
 * @author zhangyl
 * @date 2026-08-18
 */
class TodoWriteToolTest {

    @Test
    void testExecute_withNestedArgs_thenRecordsTodos() {
        var tool = new TodoWriteTool();
        var json = "{\"todos\": [{\"content\": \"买牛奶\", \"status\": \"pending\"}, "
                + "{\"content\": \"写报告\", \"status\": \"in_progress\"}]}";

        @SuppressWarnings("unchecked")
        var args = (Map<String, Object>) JsonParser.parse(json);
        var result = tool.getDefinition().executor().apply(args);
        printResult("write nested todos", result);

        assertFalse(result.isError(), result.content().getFirst().toString());
        var todos = tool.getTodos();
        assertEquals(2, todos.size());
        assertEquals("买牛奶", todos.get(0).content());
        assertEquals(TodoStatus.PENDING, todos.get(0).status());
        assertEquals("写报告", todos.get(1).content());
        assertEquals(TodoStatus.IN_PROGRESS, todos.get(1).status());
    }

    @Test
    void testExecute_whenTodosMissing_thenReturnsHint() {
        var tool = new TodoWriteTool();
        var result = tool.getDefinition().executor().apply(Map.of());
        printResult("missing todos", result);
        // 业务性提示（非系统异常），isError 为 false，但内容应说明原因
        assertFalse(result.isError());
        assertTrue(result.content().getFirst().toString().contains("缺少 todos"));
    }

    @Test
    void testExecute_whenCalledAgain_thenReplacesList() {
        var tool = new TodoWriteTool();
        @SuppressWarnings("unchecked")
        var args1 = (Map<String, Object>) JsonParser.parse(
                "{\"todos\":[{\"content\":\"a\",\"status\":\"pending\"}]}");
        tool.getDefinition().executor().apply(args1);

        @SuppressWarnings("unchecked")
        var args2 = (Map<String, Object>) JsonParser.parse(
                "{\"todos\":[{\"content\":\"b\",\"status\":\"pending\"},"
                        + "{\"content\":\"c\",\"status\":\"pending\"}]}" );
        var result = tool.getDefinition().executor().apply(args2);
        printResult("replace todo list", result);

        var todos = tool.getTodos();
        assertEquals(2, todos.size());
        assertEquals("b", todos.get(0).content());
        assertEquals("c", todos.get(1).content());
    }

    @Test
    void testExecute_withPipelineGeneratedArgs_thenRecordsAllTodos() {
        // 回归：模拟 DeepSeek 实际传回的 arguments 字符串（曾是参数解析 bug 导致记 0 条的场景）
        var tool = new TodoWriteTool();
        var parsed = JsonParser.parse("{\"todos\":[{\"content\":\"买牛奶\",\"status\":\"pending\"},"
                + "{\"content\":\"写报告\",\"status\":\"pending\"}]}");
        assertTrue(parsed instanceof Map, "应解析出 Map");
        assertTrue(((Map<?, ?>) parsed).containsKey("todos"), "应包含 todos 键");

        @SuppressWarnings("unchecked")
        var args = (Map<String, Object>) parsed;
        var result = tool.getDefinition().executor().apply(args);
        printResult("pipeline generated todos", result);

        assertFalse(result.isError(), result.content().getFirst().toString());
        var todos = tool.getTodos();
        assertEquals(2, todos.size(), "两条任务都应被记录");
        assertEquals("买牛奶", todos.get(0).content());
        assertEquals("写报告", todos.get(1).content());
    }

    @Test
    void testExecute_withInvalidStatus_thenSkipsItem() {
        var tool = new TodoWriteTool();
        @SuppressWarnings("unchecked")
        var args = (Map<String, Object>) JsonParser.parse(
                "{\"todos\":[{\"content\":\"有效\",\"status\":\"pending\"},"
                        + "{\"content\":\"无效状态\",\"status\":\"done\"}]}");
        var result = tool.getDefinition().executor().apply(args);
        printResult("invalid status", result);

        assertFalse(result.isError());
        var todos = tool.getTodos();
        assertEquals(1, todos.size(), "非法状态条目应被跳过");
        assertEquals("有效", todos.get(0).content());
    }

    private static void printResult(String scenario, dev.duo.model.llm.ToolExecutionResult result) {
        var text = ((dev.duo.model.llm.ContentBlock.Text) result.content().getFirst()).text();
        System.out.printf("[TodoWriteTool][%s] %s%n", scenario, text);
    }
}