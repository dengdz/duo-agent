package dev.duo.core.llm;

import dev.duo.api.llm.ToolRegistry;
import dev.duo.model.llm.ToolDefinition;
import dev.duo.model.llm.ToolExecutionResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ToolRegistry 的默认实现。
 *
 * @author zhangyl
 * @date 2026-08-18
 */
public class ToolRegistryImpl implements ToolRegistry {

    private static final Logger logger = LoggerFactory.getLogger(ToolRegistryImpl.class);
    
    private final Map<String, ToolDefinition> tools = new ConcurrentHashMap<>();

    @Override
    public AutoCloseable register(ToolDefinition tool) {
        Objects.requireNonNull(tool, "tool must not be null");
        
        var name = tool.name();
        var previous = tools.putIfAbsent(name, tool);
        if (previous != null) {
            throw new IllegalArgumentException("工具 \"" + name + "\" 已注册");
        }
        logger.debug("Tool registered: {}", name);
        return () -> {
            tools.remove(name);
            logger.debug("Tool unregistered: {}", name);
        };
    }

    @Override
    public List<ToolDefinition> getAll() {
        return List.copyOf(tools.values());
    }

    @Override
    public ToolDefinition get(String name) {
        Objects.requireNonNull(name, "name must not be null");
        return tools.get(name);
    }

    @Override
    public ToolExecutionResult execute(String name, Map<String, Object> args) {
        Objects.requireNonNull(name, "name must not be null");
        Objects.requireNonNull(args, "args must not be null");
        
        var tool = tools.get(name);
        if (tool == null) {
            throw new IllegalArgumentException("未知工具 \"" + name + "\"");
        }
        try {
            return tool.executor().apply(args);
        } catch (IllegalArgumentException | IllegalStateException e) {
            // 工具参数错误或状态异常，返回给模型让其重试
            logger.debug("Tool {} execution failed with expected error: {}", name, e.getMessage());
            return new ToolExecutionResult(e);
        } catch (Exception e) {
            // 非预期异常，记录详细信息
            logger.error("Unexpected error executing tool {}", name, e);
            return new ToolExecutionResult(new RuntimeException("工具执行失败: " + e.getClass().getSimpleName()));
        }
    }
}