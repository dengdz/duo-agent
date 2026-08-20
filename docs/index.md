# duo-agent 文档

**零依赖的 Java 21 AI Agent SDK** —— 用几行代码创建能调用工具、流式输出、完整可观测的智能体。

```java
var agent = DuoAgent.builder()
        .apiFormat("openai")
        .baseUrl("https://api.deepseek.com")
        .apiKey(System.getenv("DEEPSEEK_API_KEY"))
        .model("deepseek-chat")
        .contextWindow(128000)
        .withCodeTools()
        .build();

String response = agent.chat("列出当前目录的 Java 文件");
```

## 文档导航

### 入门

| 文档 | 内容 |
|------|------|
| [简介](01-getting-started/introduction.md) | duo-agent 是什么、核心特性、适用场景 |
| [快速开始](01-getting-started/quick-start.md) | 5 分钟从依赖到第一次对话 |

### 指南

| 文档 | 内容 |
|------|------|
| [对话 API](02-guide/chat-api.md) | 四种对话模式：chat / chatAsync / stream / chatEvents |
| [流式输出](02-guide/streaming.md) | 响应式流深入：背压、取消、冷发布者语义 |
| [内置工具](02-guide/tools-builtin.md) | bash / 文件 / 搜索 / 编辑 / todo 工具能力详解 |
| [自定义工具](02-guide/tools-custom.md) | 用 ToolDefinition 扩展 Agent 能力 |
| [推理模型](02-guide/reasoning-models.md) | DeepSeek-R1 等深度推理模型支持 |
| [Spring Boot SSE 桥接](02-guide/spring-sse.md) | 把流式输出接入前端（MVC / WebFlux / 前端 JS） |

### 高级

| 文档 | 内容 |
|------|------|
| [Hook 扩展点](03-advanced/hooks.md) | 4 类 Hook：消息改写、请求定制、失败恢复、工具环绕 |
| [LLM 自动重试](03-advanced/retry.md) | LlmRetryHook：指数退避 + 尊重 Retry-After |
| [上下文压缩](03-advanced/compaction.md) | 长对话自动摘要，防止上下文溢出 |
| [会话持久化](03-advanced/session-persistence.md) | JSONL 落盘、崩溃恢复、跨进程续聊 |
| [Skill 系统](03-advanced/skills.md) | SKILL.md 技能加载与注入 |

### 架构

| 文档 | 内容 |
|------|------|
| [架构总览](04-architecture/overview.md) | 分层设计：门面 → Agent 循环 → 适配层 |
| [事件溯源](04-architecture/event-sourcing.md) | Session 日志：唯一事实源的设计 |
| [ReAct 循环](04-architecture/react-loop.md) | turn / step 生命周期与事件时序 |

### 参考

| 文档 | 内容 |
|------|------|
| [事件类型参考](05-reference/events.md) | 15 种 SessionEvent 完整字段表 |
| [已知限制与路线图](05-reference/limitations.md) | 未接线功能、规划中的能力 |

## 更多资源

- [项目 README](../README.md) —— 特性亮点与快速印象
- [示例程序](../duo-agent-example/src/main/java/com/example/) —— 16 个可运行示例
- 235 个单元测试（`@Test` 计数，默认套件执行 232 个——集成测试默认排除）覆盖全部核心功能
