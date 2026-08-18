package dev.dsh.core.llm;

import dev.dsh.api.llm.ToolRegistry;
import dev.dsh.model.llm.ToolDefinition;
import dev.dsh.model.llm.ToolExecutionResult;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ToolRegistry 的默认实现。
 */
public class ToolRegistryImpl implements ToolRegistry {

    private final Map<String, ToolDefinition> tools = new ConcurrentHashMap<>();

    @Override
    public AutoCloseable register(ToolDefinition tool) {
        var name = tool.name();
        if (tools.containsKey(name)) {
            throw new IllegalArgumentException("工具 \"" + name + "\" 已注册");
        }
        tools.put(name, tool);
        return () -> tools.remove(name);
    }

    @Override
    public List<ToolDefinition> getAll() {
        return List.copyOf(tools.values());
    }

    @Override
    public ToolDefinition get(String name) {
        return tools.get(name);
    }

    @Override
    public ToolExecutionResult execute(String name, Map<String, Object> args) {
        var tool = tools.get(name);
        if (tool == null) {
            throw new IllegalArgumentException("未知工具 \"" + name + "\"");
        }
        try {
            return tool.executor().apply(args);
        } catch (Exception e) {
            return new ToolExecutionResult(e);
        }
    }
}