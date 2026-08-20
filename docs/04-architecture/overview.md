# 架构总览

duo-agent 采用分层架构：门面（简化 API）→ Agent 循环（ReAct）→ LLM 适配层（协议）→ 会话（事件溯源）。每层只依赖下一层的抽象。

## 分层图

```
┌─────────────────────────────────────────────────────────────┐
│  门面层（dev.duo.api）                                        │
│  DuoAgent / DuoAgentBuilder                                  │
│  chat · chatAsync · stream · chatEvents                      │
├─────────────────────────────────────────────────────────────┤
│  Agent 循环层（dev.duo.core.agent）                            │
│  ReactLoopAgent —— ReAct 循环（turn/step、工具调度、hook 分发）  │
│  Inbox（NEXT_TURN / NEXT_STEP 双队列）                        │
├──────────────────────────────┬──────────────────────────────┤
│  LLM 适配层（dev.duo.api.llm） │  会话层（dev.duo.core.session） │
│  LlmRuntime（注册/路由）        │  Session（append-only 事件日志）│
│  LlmAdapter（SPI）             │  SurfaceManager（模型可见表面） │
│  DeepSeekAdapter（SSE 流式）    │  JsonlSessionPersistence      │
├──────────────────────────────┼──────────────────────────────┤
│  模型协议层（dev.duo.model）     │  扩展                        │
│  Message / ContentBlock       │  4 类 Hook                   │
│  StreamChunk / GenerateOptions│  Compaction / Skill          │
└──────────────────────────────┴──────────────────────────────┘
```

## 各层职责

### 门面层

`DuoAgent` 提供四种对话模式与底层实例访问；`DuoAgentBuilder` 负责"配置校验 → 组件组装"（LlmRuntime、ToolRegistry、SystemPrompt、Session、ReactLoopAgent）。超时分层在这里完成：HTTP 层兜底 = max(llmTimeout, reasoningTimeout) + 1 分钟。

### Agent 循环层

`ReactLoopAgent` 是唯一的执行引擎：虚拟线程驱动，`turn`（一次用户输入的完整处理）内推进 `step`（一次模型调用 + 工具执行）。四个 hook 分发点嵌在关键路径上。详见 [ReAct 循环](react-loop.md)。

### LLM 适配层

`LlmAdapter` 是 SPI（唯一抽象方法 `stream`）；`LlmRuntime` 按 provider 名路由。`DeepSeekAdapter` 处理 OpenAI 兼容协议：SSE 逐行解析、tool_calls 增量重组、错误码映射。**适配器只做单次请求**——重试、恢复策略在循环层由 hook 外挂。

### 会话层

事件溯源：Session 日志是唯一事实源，"模型可见 = 已记日志"。表面管理（SurfaceManager）决定哪些事件进入模型上下文。详见 [事件溯源](event-sourcing.md)。

## 关键设计决策

| 决策 | 理由 |
|------|------|
| 事件溯源而非可变消息列表 | 持久化、回放、压缩、上下文派生全部同源；崩溃恢复天然可行 |
| Flow.Publisher 而非自建流 API | Reactive Streams 是 JDK 原生标准，Reactor/RxJava 零成本互操作 |
| 适配器单次请求 + hook 外挂重试 | 职责单一；恢复策略可插拔（LlmRetryHook）而非写死 |
| 手写 JSON 编解码 | 零依赖原则；事件格式即持久化格式，版本演进可控 |
| 虚拟线程驱动 | 对话是 IO 密集任务，虚拟线程低成本阻塞等待，无需响应式框架 |
| 表面替换（Replace）而非删除历史 | 压缩后原始事件保留——审计与回放不丢失信息 |

## 包结构速查

| 包 | 内容 |
|----|------|
| `dev.duo.api` | 门面：DuoAgent、DuoAgentBuilder |
| `dev.duo.api.agent` | 底层 Agent 接口、AgentOptions、AgentHooks、Inbox |
| `dev.duo.api.hook` | 4 类 Hook SPI |
| `dev.duo.api.llm` | LlmRuntime、LlmAdapter、StreamCallback、ToolRegistry |
| `dev.duo.api.skill` | Skill SPI（Provider、Skill、SkillSource） |
| `dev.duo.core.agent` | ReactLoopAgent 实现 |
| `dev.duo.core.session` | Session、SurfaceManager、Codec、Persistence |
| `dev.duo.core.compaction` | CompactionHook、TokenEstimator |
| `dev.duo.core.skill` | SkillRegistry、FilesystemSkillProvider |
| `dev.duo.core.llm` | BlockAssembler、SystemPromptImpl、LlmRetryHook |
| `dev.duo.adapter.deepseek` | DeepSeek 适配器（Adapter/RequestBuilder/SseParser） |
| `dev.duo.model.llm` | Message、ContentBlock、StreamChunk、GenerateOptions |
| `dev.duo.model.session` | SessionEvent 15 种、TurnEndReason、SurfaceOp |
| `dev.duo.tool` | 8 个内置工具 |
