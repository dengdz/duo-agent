# 自定义工具

用 `ToolDefinition` 为 Agent 添加你自己的能力——一个工具就是"名称 + 描述 + JSON Schema 参数 + 执行函数"。

## 最小示例

```java
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

    private static ToolExecutionResult execute(Map<String, Object> args) {
        var city = String.valueOf(args.get("city"));
        // ... 实际业务逻辑（调天气 API、查数据库……）
        return new ToolExecutionResult(city + "：晴，26℃");
    }
}
```

## 注册到 Agent

```java
var agent = DuoAgent.builder()
        .apiFormat("openai")
        /* ...基础配置... */
        .withFileTools()                          // 与预设混用
        .tool(WeatherTool.definition())           // 添加自定义工具
        .build();

agent.chat("北京今天天气怎么样？");
// 模型自动调用 get_weather(city="北京") 并组织回答
```

## ToolDefinition 四要素

| 要素 | 说明 |
|------|------|
| `name` | 工具名（不能为空）。与预设重名时按 last-wins 覆盖 |
| `description` | **写给模型看的**——描述越准确，模型调用越恰当 |
| `parameters` | JSON Schema 的 `Map` 形式，声明参数类型与必填项 |
| `executor` | `Function<Map<String, Object>, ToolExecutionResult>`，接收解析后的参数 |

> 💡 参数到达 executor 时已从 JSON 解析为 `Map<String, Object>`（字符串/数字/布尔/嵌套 Map/List）。

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

## 编写好工具的实践

1. **描述即文档**：把何时该用、何时不该用写进 description，模型靠它做决策
2. **参数校验前置**：executor 开头校验参数，非法时返回 `ToolExecutionResult(Throwable)` 而不是抛异常
3. **输出面向模型**：返回简洁、信息密度高的文本；超长输出自行截断（参考内置工具：grep 内联上限 250、bash 截断 100KB）
4. **幂等设计**：模型可能重复调用同一工具（尤其在崩溃恢复场景）
5. **副作用要谨慎**：写文件、发请求等操作考虑先查后写

## 带状态的复杂工具

executor 是普通函数，可以引用实例状态：

```java
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

    private ToolExecutionResult execute(Map<String, Object> args) {
        // this.connection 可用
    }
}
```

## 下一步

→ [Hook 扩展点](../03-advanced/hooks.md)：工具执行环绕（审批、审计、指标）
