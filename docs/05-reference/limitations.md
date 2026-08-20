# 已知限制与路线图

如实列出当前版本的限制与未完成项，以及规划中的能力。

## 当前限制

### API 格式

- **仅支持 OpenAI 兼容格式**（`apiFormat("openai")`）。DeepSeek、Ollama、vLLM 等均可用；Anthropic（Claude 原生协议）适配器尚未实现，`apiFormat("anthropic")` 会被直接拒绝。

### 线程模型

- **DuoAgent 实例非线程安全**：实例共享 Session 状态，不可并发调用同一实例。并发场景请每请求新建（构建成本极低）。
- `chatAsync` 使用 commonPool，高并发生产环境建议自定义线程池包装。

### 流式 API

- **断线不能续传**：`stream()`/`chatEvents()` 是一次性对话流，连接断开即本轮作废。（session seq 已为续传打好基础，见路线图）
- **取消不中断推理**：订阅 `cancel()` 只停止推送，底层对话轮会执行完毕（仍消耗一次 API 调用）。

### 尚未接入门面的功能

以下功能**底层完整可用**，但暂无 Builder 一键配置，需手动组装（各有文档与示例）：

| 功能 | 状态 | 手动接入文档 |
|------|------|-------------|
| LLM 自动重试（LlmRetryHook） | **门面可用**：`builder.hooks().addRequestErrorHook(new LlmRetryHook())` | [LLM 自动重试](../03-advanced/retry.md) |
| 上下文压缩（CompactionHook） | 需手动组装底层 Agent（hook 需要 llmRuntime/systemPrompt 引用） | [上下文压缩](../03-advanced/compaction.md) |
| 会话持久化（JsonlSessionPersistence） | 需手动组装底层组件 | [会话持久化](../03-advanced/session-persistence.md) |
| Skill 系统 | 需手动组装底层组件 | [Skill 系统](../03-advanced/skills.md) |

### 已定义未接线的事件与类型

- `todo/write`、`request/header`、`request/context` 三种事件无生产者（todo 状态仅存内存）
- `AgentRegistry` / `AgentFactory` / `AgentHandle`（多 Agent 管理体系）已定义，门面构建路径暂不经过

### 其他

- **取消不产生 `Aborted` 事件**：`Agent.cancel()` 为简化实现（清 Inbox 置 Idle，turn 以 `Completed` 收尾）；`Aborted` 原因协议已定义但主流程无生产点
- **无前缀缓存优化**：请求全量重发（DeepSeek 服务端自动缓存，成本影响有限）
- **FrontmatterParser 仅支持单行 `key: value`**：不支持嵌套/数组/多行值

## 路线图

### 近期

- **Builder 暴露高级功能入口**：压缩 / 重试 / 持久化 / Skill 一键启用
- **断线重连续传**：基于 session seq 的 `chatEvents` 断点恢复
- **todo/write 事件接线**：todo 状态入日志，UI 可实时渲染任务列表

### 中期

- **Anthropic 适配器**：`apiFormat("anthropic")`（Claude 原生协议）
- **多 Agent 管理**：AgentRegistry/Factory 接入门面，子 Agent 编排
- **请求头记录**：request/header 事件接线（可审计每次请求的完整配置）

### 远期

- **前缀缓存友好**：稳定消息前缀排序，最大化命中 DeepSeek 前缀缓存
- **审批交互协议**：approval 类事件（工具执行前人工确认，配对 asked/decided）
