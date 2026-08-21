# 已知限制与路线图

如实列出当前版本的限制与未完成项，以及规划中的能力。

## 当前限制

### API 格式

- 0.3.0 起提供三种协议的 `DuoModel` 实现：`ChatCompletionsModel`（一切 OpenAI 兼容端点）、`AnthropicModel`（Anthropic Messages 协议，含智谱/DeepSeek 兼容端点与企业中转站）、`ResponsesModel`（OpenAI Responses 协议）。接入方式见[多厂商接入指南](../02-guide/multi-provider.md)。
- **DeepSeek 的 Responses 兼容端点思考不可见**：端点忽略 `reasoning.summary` 参数，思考增量不透出（Chat Completions 与 Anthropic 端点均正常透出）。
- **OpenAI 官方端点未实机验证**：`ResponsesModel` 经协议级测试与 DeepSeek 兼容端点实测，OpenAI 官方端点待持有 key 的用户验证。

### 线程模型

- **DuoAgent 实例非线程安全**：实例共享 Session 状态，不可并发调用同一实例。并发场景请每请求新建（构建成本极低；`DuoModel` 无状态可全局共享）。需要异步时由调用方自行包装：`CompletableFuture.supplyAsync(() -> agent.call(msg), executor)`。

### 流式 API

- **断线不能续传**：`stream()` 是一次性对话流，连接断开即本轮作废。（session seq 已为续传打好基础，见路线图）

### 尚未接入门面的功能

以下功能**底层完整可用**，但暂无 Builder 一键配置，需手动组装（各有文档与示例）：

| 功能 | 状态 | 手动接入文档 |
|------|------|-------------|
| LLM 自动重试（LlmRetryHook） | **门面可用**：`builder.hooks().addRequestErrorHook(new LlmRetryHook())` | [LLM 自动重试](../03-advanced/retry.md) |
| 上下文压缩（CompactionHook） | 需手动组装底层 Agent（hook 需要 llmRuntime/systemPrompt 引用） | [上下文压缩](../03-advanced/compaction.md) |
| 会话持久化（JsonlSessionPersistence） | 需手动组装底层组件 | [会话持久化](../03-advanced/session-persistence.md) |
| Skill 系统 | 需手动组装底层组件 | [Skill 系统](../03-advanced/skills.md) |

### 取消与中断

> **0.4.0 完整实现**：双通道取消信号（`CancellationSignal` + `Thread.interrupt()`）贯穿调用链，详见 [ADR_004](../../ADR_004_CANCELLATION_INTERRUPT.md)。

- **取消会中断 LLM stream**：`Agent.cancel()` 调用断连监听器，HTTP 客户端取消挂起的流请求（已调度的工具执行同步收到信号与线程中断）
- **已调度工具执行不能强制终止**：除 bash（两级 SIGTERM/SIGKILL），自定义工具需自行在 `executor` 内部检查 `execution.cancellation().isCancelled()` 并提前返回或抛 `TurnCancelledException`
- **取消产生两层 Aborted 哨兵结果**：未调度的 tool_call 配对 `ABORTED_BEFORE_DISPATCH`（无副作用）；已调度但中断的配对 `ABORTED`（可能有副作用）。哨兵事件维持协议要求的 tool_call/tool_result 配对

### 已定义未接线的事件与类型

- `todo/write`、`request/header`、`request/context` 三种事件无生产者（todo 状态仅存内存）
- `AgentRegistry` / `AgentFactory` / `AgentHandle`（多 Agent 管理体系）已定义，门面构建路径暂不经过

### 其他

- **无前缀缓存优化**：请求全量重发（DeepSeek 服务端自动缓存，成本影响有限）
- **FrontmatterParser 仅支持单行 `key: value`**：不支持嵌套/数组/多行值

## 路线图

### 近期

- **Builder 暴露高级功能入口**：压缩 / 重试 / 持久化 / Skill 一键启用
- **断线重连续传**：基于 session seq 的 `stream()` 断点恢复
- **todo/write 事件接线**：todo 状态入日志，UI 可实时渲染任务列表

### 中期

- **Anthropic 的 DuoModel 实现**：Claude 原生协议支持
- **多 Agent 管理**：AgentRegistry/Factory 接入门面，子 Agent 编排
- **请求头记录**：request/header 事件接线（可审计每次请求的完整配置）

### 远期

- **前缀缓存友好**：稳定消息前缀排序，最大化命中 DeepSeek 前缀缓存
- **审批交互协议**：approval 类事件（工具执行前人工确认，配对 asked/decided）
