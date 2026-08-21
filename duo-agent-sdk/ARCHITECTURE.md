# SDK 架构设计

本文档已迁移至在线文档站，完整内容请访问：

📖 **[SDK 设计 - duo-agent 文档](../docs/04-architecture/sdk-design.md)**

---

## 快速导航

- [分层设计](../docs/04-architecture/overview.md)
- [事件溯源](../docs/04-architecture/event-sourcing.md)
- [ReAct 循环](../docs/04-architecture/react-loop.md)
- [SDK 设计](../docs/04-architecture/sdk-design.md)

---

## 本地快速参考

### 目录结构

```
dev.duo/
├── api/              # 公开 API 层（用户直接使用）
│   ├── agent/        # Agent API（含 AgentHooks 扩展点）
│   ├── hook/         # Hook 扩展点接口
│   ├── llm/          # LLM 相关 API
│   └── skill/        # Skill 系统 API
├── core/             # 核心实现层
│   ├── agent/        # ReactLoopAgent 实现
│   ├── compaction/   # 上下文压缩
│   ├── llm/          # LLM 核心逻辑
│   ├── session/      # Session 管理、JSONL 持久化
│   └── skill/        # Skill 加载与注册
├── adapter/          # 适配器层（外部系统集成）
│   ├── openai/       # OpenAI 适配器
│   ├── anthropic/    # Anthropic 适配器
│   └── responses/    # 阿里云百炼 Responses 适配器
├── tool/             # 内置工具层
├── model/            # 数据模型层
│   ├── llm/          # LLM 相关模型
│   └── session/      # Session 相关模型
├── exception/        # 异常定义
└── util/             # 工具类
```

### 扩展规范

**添加新功能前，问自己**：

1. **这是公开 API 吗？** → 放 `api/`
2. **这是 API 的实现吗？** → 放 `core/`
3. **这是外部系统集成吗？** → 放 `adapter/`
4. **这是 Agent 的工具吗？** → 放 `tool/`
5. **这是数据模型吗？** → 放 `model/`
6. **这是自定义异常吗？** → 放 `exception/`
7. **这是通用工具类吗？** → 放 `util/`

### 依赖规则

- `api` 只依赖 `model` 和 `exception`
- `core` 可以依赖 `api`、`model`、`exception`、`util`
- `adapter` 可以依赖 `api`、`model`、`exception`、`util`
- `tool` 可以依赖 `api`、`model`、`exception`、`util`
- `model` 只依赖 `model`（内部）和 `exception`
- `exception` 不依赖任何业务包
- `util` 不依赖任何业务包

### Hook 扩展点

新能力通过 `api/hook` 的四个拦截点外挂实现：

1. **PreStepHook**：step 进入决策
2. **RequestHook**：LLM 请求构造
3. **RequestErrorHook**：请求失败恢复
4. **ToolExecutionHook**：工具执行环绕

详见[在线文档](../docs/04-architecture/sdk-design.md)。
