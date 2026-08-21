# 自定义工具

用 `ToolDefinition` 为 Agent 添加你自己的能力——一个工具就是"名称 + 描述 + JSON Schema 参数 + 执行函数"。

## 最小示例

```java
import dev.duo.model.llm.ToolDefinition;
import dev.duo.model.llm.ToolExecution;
import dev.duo.model.llm.ToolExecutionResult;
import java.util.List;
import java.util.Map;

public class WeatherTool {

    public static ToolDefinition definition() {
        return new ToolDefinition(
                "get_weather",                      // 名称
                "查询指定城市的当前天气",              // 描述（模型据此决定何时调用）
                Map.of(                             // 参数 JSON Schema
                        "type", "object",
                        "properties", Map.of(
                                "city", Map.of("type", "string", "description", "城市名")),
                        "required", List.of("city")),
                WeatherTool::execute                // 执行函数
        );
    }

    private static ToolExecutionResult execute(ToolExecution execution) {
        var city = String.valueOf(execution.arguments().get("city"));
        // ... 实际业务逻辑（调天气 API、查数据库……）
        return new ToolExecutionResult(city + "：晴，26℃");
    }
}
```

## 注册到 Agent

```java
DuoModel model = DeepSeekModel.builder()      // 模型配置（可复用给多个 Agent）
        .apiKey(System.getenv("DEEPSEEK_API_KEY"))
        .model("deepseek-chat")
        .build();

var agent = DuoAgent.builder()
        .model(model)                         // 必填：模型实例
        .withFileTools()                      // 与预设混用
        .tool(WeatherTool.definition())       // 添加自定义工具
        .build();

agent.call("北京今天天气怎么样？");
// 模型自动调用 get_weather(city="北京") 并组织回答
```

## ToolDefinition 四要素

| 要素 | 说明 |
|------|------|
| `name` | 工具名（不能为空）。与预设重名时按 last-wins 覆盖 |
| `description` | **写给模型看的**——描述越准确，模型调用越恰当 |
| `parameters` | JSON Schema 的 `Map` 形式，声明参数类型与必填项 |
| `executor` | `ToolExecutor`（函数式接口），接收 `ToolExecution` 包装的参数与取消信号 |

> 💡 参数到达 executor 时已从 JSON 解析为 `Map<String, Object>`（字符串/数字/布尔/嵌套 Map/List），包装在 `ToolExecution.arguments()` 中；`ToolExecution.cancellation()` 提供取消检查点。

## 返回结果

```java
// 成功：文本内容（自动包装为 Text 块）
return new ToolExecutionResult("查询结果：...");

// 成功：结构化内容块
return new ToolExecutionResult(false, List.of(
        new ContentBlock.Text("摘要"),
        new ContentBlock.Text("详情")));

// 失败：错误信息回传给模型（模型会看到并自行决策）
return new ToolExecutionResult(new RuntimeException("城市不存在"));
```

**失败不会中断对话**——错误信息作为工具结果回传，模型可以调整参数重试或改变策略。只有想让整轮失败时才应抛异常。

> ⚠️ executor 可抛 `TurnCancelledException`（检查式异常）响应用户取消请求，框架会为剩余 tool_call 配对 `ABORTED` 哨兵结果。自定义工具需自行检查 `execution.cancellation().checkPoint()` 或 `isCancelled()` 以支持可中断长时阻塞操作。

## 编写好工具的实践

1. **描述即文档**：把何时该用、何时不该用写进 description，模型靠它做决策
2. **参数校验前置**：executor 开头校验参数，非法时返回 `ToolExecutionResult(Throwable)` 而不是抛异常（除 `TurnCancelledException` 由框架处理）
3. **输出面向模型**：返回简洁、信息密度高的文本；超长输出自行截断（参考内置工具：grep 内联上限 250、bash 截断 100KB）
4. **幂等设计**：模型可能重复调用同一工具（尤其在崩溃恢复场景）
5. **副作用要谨慎**：写文件、发请求等操作考虑先查后写
6. **响应取消信号**：长时阻塞操作（网络请求、大文件 I/O）周期检查 `execution.cancellation().checkPoint()` 或在 catch 块捕获 `InterruptedException` 后抛 `TurnCancelledException`

## 带状态的复杂工具

executor 是普通函数，可以引用实例状态：

```java
import dev.duo.api.agent.TurnCancelledException;
import dev.duo.model.llm.ToolDefinition;
import dev.duo.model.llm.ToolExecution;
import dev.duo.model.llm.ToolExecutionResult;
import java.sql.Connection;

public class DatabaseQueryTool {

    private final Connection connection;   // 复用连接

    public DatabaseQueryTool(Connection connection) {
        this.connection = connection;
    }

    public ToolDefinition definition() {
        return new ToolDefinition(
                "query_db",
                "查询业务数据库（只读 SELECT）",
                buildSchema(),
                this::execute);            // 实例方法引用，持有 connection
    }

    private ToolExecutionResult execute(ToolExecution execution) throws TurnCancelledException {
        // execution.arguments() 可用，持有 connection
        var args = execution.arguments();
        // 长时查询周期检查取消
        try {
            execution.cancellation().checkPoint();
            // ... 执行查询
        } catch (TurnCancelledException e) {
            // 响应取消，抛出让框架生成哨兵
            throw e;
        }
        return new ToolExecutionResult("查询结果");
    }

    private static java.util.Map<String, Object> buildSchema() {
        return java.util.Map.of("type", "object");
    }
}
```

## 下一步

→ [Hook 扩展点](../03-advanced/hooks.md)：工具执行环绕（审批、审计、指标）
