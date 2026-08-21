# ReAct 循环

`ReactLoopAgent` 是 duo-agent 的执行引擎：推理（Reasoning）与行动（Acting）交替推进，直到模型给出最终回答。本文描述 turn/step 生命周期与完整事件时序。

## 核心概念

| 概念 | 定义 |
|------|------|
| **turn** | 一次用户输入的完整处理（可能包含多个 step） |
| **step** | 一次模型调用 + 其工具执行（ReAct 的一轮） |
| **Inbox** | 输入队列，双优先级：`NEXT_TURN`（新对话轮）/ `NEXT_STEP`（当前轮内继续） |

## 循环结构

```
虚拟线程驱动 runLoop
   │
   ▼
turn(turn N)                                    ① turn/start
   │  认领 Inbox 中的输入
   │
   ├─ step(N, 1)                                ② step/start
   │    user/message × 输入条数                    （pre-step hook 可拒绝→Blocked）
   │    ┌─────────── 模型调用 ───────────┐
   │    │  request hook 分发（可改写请求）    │
   │    │  SSE 流式：                       │
   │    │    assistant/chunk × N           │       token 级增量逐条落日志
   │    │  应用层超时 barrier               │       （推理/普通双超时）
   │    └───────────────────────────────────┘
   │    assistant/message                         ③ 完整消息 + usage + 回链
   │    ├─ 有工具调用？
   │    │    (tool/call → 执行 → tool/result) × K   ④ 逐对交错（tool hook 分发）
   │    │    工具结果 → Inbox(NEXT_STEP) → 下一个 step
   │    │    step/end → step(N, 2) → ...
   │    └─ 无工具调用 → turn 结束
   │
   ▼
turn/end                                        ⑤ finally 必发，携带结束原因
```

## 事件时序（单 turn 完整序列）

```
turn/start
└─ step/start
   user/message                       本 step 进入的输入
   assistant/chunk × N                流式增量（文本/思考/工具参数）
   assistant/message                  组装完成的完整消息（usage 随行，
                                       sourceEventSeqs 回链 chunk seq）
   (tool/call → tool/result) × K      逐对交错：call₁ → result₁ → call₂ → …
└─ step/end                           finally 必发
turn/end                              finally 必发，携带结束原因
```

完整字段表见 [事件类型参考](../05-reference/events.md)。

## 四个 Hook 分发点

| 位置 | Hook | 时机 |
|------|------|------|
| ① 前 | `PreStepHook` | step 进入前——可改写消息或拒绝（`Blocked`，不消耗模型调用） |
| ② 中 | `RequestHook` | 模型请求组装时——可改写 `GenerateOptions` |
| ② 后 | `RequestErrorHook` | 调用失败时——`Retry` 重试（每 step 硬上限 10 次）或 `Fail` |
| ④ 中 | `ToolExecutionHook` | 工具执行环绕——可短路拒绝、改写结果 |

## 超时控制

```
连接建立：60s（HttpClient.connectTimeout）
HTTP 整体：max(llmTimeout, reasoningTimeout) + 1min（防 SSE 长流被掐，兜底）
应用层 barrier：推理模式 reasoningTimeout（默认 5min）/ 普通模式 llmTimeout（默认 60s）
```

超时/失败后 `closed` 标志关闭回调入口——迟到的 chunk 不会混入日志（重试场景防污染）。

## 结束原因（TurnEndReason）

| 原因 | 触发 |
|------|------|
| `Completed` | 模型给出无工具调用的回答 |
| `Aborted(cause)` | `Agent.cancel()` 中断当前 turn（详见 [ADR_004](../../ADR_004_CANCELLATION_INTERRUPT.md)） |
| `Blocked` | pre-step hook 拒绝输入 |
| `Error(LlmFailure)` | 模型调用最终失败（重试耗尽等） |
| `MaxTokens` | 输出达到 token 上限 |
| `Interrupted` | 进程崩溃后的恢复闭合标记（仅持久化 load 时合成） |

> ✅ **0.4.0 起完整实现取消打断**：
> - 双通道传播：`CancellationSignal` + `Thread.interrupt()`
> - 中断 HTTP stream：断连监听器立即触发
> - 终止 bash 进程：两级 kill (SIGTERM → 3s → SIGKILL)
> - 哨兵结果配对：`ABORTED` / `ABORTED_BEFORE_DISPATCH`
> - Turn 收尾：记录 `Aborted(reason)`
> 
> 详见 [ADR_004](../../ADR_004_CANCELLATION_INTERRUPT.md) 和 [取消与中断限制](../05-reference/limitations.md#取消与中断)

## 输入路由（Inbox）

```java
agent.followup(msg);   // NEXT_TURN + 唤醒：排队为下一个对话轮
agent.steer(msg);      // NEXT_STEP + 唤醒：运行中在下一个 step 边界注入（转向）
agent.inject(msg);     // NEXT_STEP 不唤醒：静默补充上下文
agent.cancel(AgentCancelCause.User(), new CancelOptions());  // 取消排队/活跃工作
agent.whenIdle();      // 等待全部活动静止
```

`chat()` 等门面方法内部即 `followup + whenIdle`。

`AgentCancelCause` 五种：`User` / `Parent` / `Hook(reason)` / `Disposed` / `Legacy`。
