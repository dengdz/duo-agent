# 术语表

本文档收录 duo-agent 特有的核心概念和术语定义。

## 会话与驱动

### Turn（轮次）
一次用户输入驱动的完整循环（模型调用 × N 步 + 工具执行），事件日志中 `turn/start` 与 `turn/end` 之间的全部事件。

**避免使用**：round、回合

### Turn 链（activity）
一次驱动唤醒连续执行的多个 turn；turn 正常结束且 inbox 有待办时链继续，每个 turn 换新取消信号。

**避免使用**：会话循环

### Session（会话）
Agent 的完整对话历史，以事件溯源方式存储。包含元数据（SessionHeader）和事件日志（SessionEvent[]）。Session 是持久化的最小单位，可跨进程恢复。

### Inbox（收件箱）
Agent 的待处理消息队列，分为两个通道：
- `next-turn`：等待单个轮次的提示
- `next-step`：等待下一个 step 边界的输入

所有变更先记录到 Session 日志，再更新内存投影。

---

## 取消机制

### CancellationSignal（取消信号）
Turn 级取消权威对象：cause 首写固化、检查点抛出、监听器触发断连/杀进程。一个 turn 一个实例，显式传参贯穿 LLM 调用与工具执行，不用 ThreadLocal。

**避免使用**：AbortSignal、cancel flag、中断标志

### 取消收敛（cancel convergence）
cancel 发出后到驱动线程完成收尾（写 sentinel、写 turn/end、置 Idle）的过程；收敛完成前不开新驱动，期间的新消息落入 wake latch。

**避免使用**：取消完成、取消生效

### 双通道取消
显式 signal 承载语义 + 驱动线程 interrupt 即时唤醒阻塞原语的组合；interrupt 后凭 signal 查询区分「用户取消」与「意外中断」。

**避免使用**：抢占式取消（本项目的取消是协作式立场，interrupt 只是唤醒手段）

### Sentinel 工具结果
取消后为保持 `tool_call`/`tool_result` 事件配对而写入的占位错误结果；分 `ABORTED`（body 已启动）与 `ABORTED_BEFORE_DISPATCH`（未启动）两档。

**避免使用**：假结果、占位结果

---

## 模型与协议

### API 格式（api format）
模型端点的协议三分类（Chat Completions / Anthropic Messages / Responses），是 Model 类型系统的切分维度；厂商只是配置值。

**避免使用**：提供方类型、厂商协议

### DuoModel
模型抽象层，封装三种 API 格式的统一接口。提供 `generate()` 和 `stream()` 方法，由具体协议实现类（OpenAIModel、AnthropicModel、ResponsesModel）提供适配。

### LlmAdapter
模型适配器接口，负责将 GenerateOptions 转换为厂商特定的请求格式，并解析响应。每个提供方（OpenAI、Anthropic、阿里云等）需要实现对应的适配器。

---

## 事件溯源

### SessionEvent
会话日志的最小单位，记录 Agent 执行过程中的所有状态变更。每个事件包含 seq（序列号）、time（时间戳）、type（类型）和特定负载。

### Event Sourcing（事件溯源）
以事件日志作为唯一事实源的架构模式。Session 不存储当前状态，而是记录所有变更事件，状态通过重放事件计算得出。支持完整历史追溯、崩溃恢复和调试回放。

### Compaction（压缩）
上下文窗口接近限制时，将历史消息转换为摘要的过程。压缩事务以 `compaction/start` 和 `compaction/end` 事件包裹，保证原子性。

---

## 扩展机制

### Hook
Agent 生命周期的拦截点，支持在关键节点注入自定义逻辑。包括：
- **PreStepHook**：step 前决策（是否进入、修改 inbox）
- **RequestHook**：LLM 请求构造前拦截
- **RequestErrorHook**：LLM 请求失败后恢复
- **ToolExecutionHook**：工具执行环绕拦截

### Hook 链（waterfall）
多个同类型 hook 按注册顺序组成调用链，先注册者在最外层。调用 `chain.proceed()` 委托下游（最终是内置行为），不调用即接管/否决；`proceed()` 仅可调用一次。

### Skill
可被 Agent 动态加载的能力单元，包含工具定义、系统提示词、示例等。通过 SkillProvider 提供，支持文件系统、JAR 包等多种来源。

---

## 参考链接

- [事件类型速查](./events.md)
- [取消与中断指南](../02-guide/cancellation.md)
- [事件溯源架构](../04-architecture/event-sourcing.md)
- [Hook 扩展](../03-advanced/hooks.md)
