package dev.dsh.api.llm;

import dev.dsh.model.llm.ToolDefinition;
import dev.dsh.model.llm.ToolExecutionResult;

import java.util.List;
import java.util.Map;

/**
 * 工具注册表服务。
 * <p>
 * 对应 TS 源码中的 {@code ctx.tools}。
 * </p>
 *
 * @author zhangyl
 * @date 2026-08-18
 */
public interface ToolRegistry {

    /**
     * 注册一个工具。
     * @param tool 工具定义
     * @return 处置器
     */
    AutoCloseable register(ToolDefinition tool);

    /**
     * 获取所有已注册的工具。
     * @return 工具定义列表
     */
    List<ToolDefinition> getAll();

    /**
     * 根据名称获取工具。
     * @param name 工具名称
     * @return 工具定义，或 null
     */
    ToolDefinition get(String name);

    /**
     * 执行一个工具。
     * @param name 工具名称
     * @param args 参数字典
     * @return 执行结果
     * @throws IllegalArgumentException 如果工具不存在
     */
    ToolExecutionResult execute(String name, Map<String, Object> args);
}