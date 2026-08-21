package dev.duo.api.llm;

import dev.duo.model.llm.ToolDefinition;
import dev.duo.model.llm.ToolExecution;
import dev.duo.model.llm.ToolExecutionResult;

import java.util.List;
import java.util.Map;

/**
 * 工具注册表服务。
 * <p>
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
     * @param execution 参数与取消信号
     * @return 执行结果
     * @throws IllegalArgumentException 如果工具不存在
     * @throws dev.duo.api.agent.TurnCancelledException 执行被取消
     *         （工具已清理后抛出，穿透本层不被转为错误结果）
     */
    ToolExecutionResult execute(String name, ToolExecution execution)
            throws dev.duo.api.agent.TurnCancelledException;
}