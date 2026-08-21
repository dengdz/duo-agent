# 事件类型参考

`stream()` 全量透传 15 种 `SessionEvent`。本文是完整字段速查表。

## 事件信封公共字段

每个事件都有：`type()`（类型字符串）、`seq()`（严格单调递增）、`time()`（Unix 毫秒）、`ignorable()`（未知类型可安全跳过）。表面事件额外携带 `surfaceOp()` 与 `sourceEventSeqs()`。

## 事件总表

### 边界事件

| 类型 | Java 类型 | 专有字段 | 说明 |
|------|----------|---------|------|
| `turn/start` | `SessionEventTurnStart` | `turn` | turn 开启 |
| `turn/end` | `SessionEventTurnEnd` | `turn`, `reason: TurnEndReason` | turn 关闭（finally 必发），完成信号 |
| `step/start` | `SessionEventStepStart` | `turn`, `step` | step 开启 |
| `step/end` | `SessionEventStepEnd` | `turn`, `step` | step 关闭（finally 必发） |

### 流式增量事件

| 类型 | Java 类型 | 专有字段 | 说明 |
|------|----------|---------|------|
| `assistant/chunk` | `SessionEventAssistantChunk` | `turn`, `step`, `chunk: StreamChunk` | 模型流式增量，**7 种变体见下** |

`StreamChunk` 变体：

| 变体 | 字段 | 说明 |
|------|------|------|
| `BlockStart(index, blockType)` | 块索引、类型（text/reasoning/tool-call） | 新块开始 |
| `TextDelta(index, text)` | 文本增量 | 回答内容 |
| `ReasoningDelta(index, text)` | 思考增量 | 推理模型的 `<think>` 过程 |
| `ToolCallDelta(index, id, name, argumentsDelta)` | 工具参数 JSON 碎片 | 逐字符流出的调用参数 |
| `BlockEnd(index, block)` | 组装完成的完整块 | 块结束 |
| `Usage(TokenUsage)` | input/output/cacheRead/cacheWrite/reasoningTokens | 用量 |
| `Finish(reason, replayState)` | Stop/ToolCalls/MaxTokens/Aborted/Error | 流终结 |

### 表面事件（进入模型上下文）

| 类型 | Java 类型 | 专有字段 | 说明 |
|------|----------|---------|------|
| `user/message` | `SessionEventUserMessage` | `message: UserMessage` 🟦 | 用户输入 / 注入的上下文 |
| `assistant/message` | `SessionEventAssistantMessage` | `turn`, `step`, `message`, `usage?`, `sourceEventSeqs` 🟦 | 组装完成的完整消息，usage 随行，回链全部 chunk seq |
| `tool/result` | `SessionEventToolResult` | `turn`, `step`, `message`, `errorName?`, `errorCode?` 🟦 | 工具执行结果。`errorCode` 为 `"ABORTED"` 时表示执行已调度但被中断（可能有副作用），`"ABORTED_BEFORE_DISPATCH"` 表示尚未调度（无副作用） |

🟦 表面事件携带 `surfaceOp`（`Append` 或 `Replace(start, end)`）。

### 工具事件

| 类型 | Java 类型 | 专有字段 | 说明 |
|------|----------|---------|------|
| `tool/call` | `SessionEventToolCall` | `turn`, `step`, `callId`, `name`, `arguments`（原始 JSON 字符串） | 模型发起的工具调用，`callId` 与 `tool/result` 配对 |

### 簿记事件

| 类型 | Java 类型 | 专有字段 | 说明 |
|------|----------|---------|------|
| `compaction/start` | `SessionEventCompactionStart` | `compactionId`, `turn?` | 压缩事务开始 |
| `compaction/end` | `SessionEventCompactionEnd` | `compactionId`, `turn?`, `error?` | 压缩事务结束 |
| `session/end-seed` | `SessionEventSessionEndSeed` | （无） | 持久化种子边界（唯一强制 ignorable） |

### 暂无生产者的事件

| 类型 | 说明 |
|------|------|
| `todo/write` | 类型与编解码已定义，主流程暂不产生（todo 仅存内存） |
| `request/header` | 同上 |
| `request/context` | 同上 |

## TurnEndReason 六种

| 变体 | 载荷 | 含义 |
|------|------|------|
| `Completed` | — | 正常完成 |
| `Aborted(reason)` | `TurnEndCancelCause`：User/Parent/Hook(reason)/Disposed/Legacy | 被取消 |
| `Blocked` | — | pre-step 拒绝 |
| `Error(failure)` | `LlmFailure`：message/code/status?/providerRetryAfterMs?/requestId? | 执行失败 |
| `MaxTokens` | — | 输出触顶 |
| `Interrupted` | — | 崩溃恢复闭合（仅 load 时合成） |

## 内容块（ContentBlock，4 种）

消息内容由块组成：`Text(text)` / `Reasoning(text)` / `ToolCall(id, name, arguments)` / `ToolResult(toolCallId, content: List<ContentBlock>, isError)`。

## 消费模式速查

```java
switch (event) {
    case SessionEventTurnStart t          -> /* turn 开始 */
    case SessionEventAssistantChunk c when c.chunk() instanceof StreamChunk.TextDelta d
                                          -> /* 回答增量 d.text() */
    case SessionEventAssistantMessage msg -> /* 完整消息 msg.message()、用量 msg.usage() */
    case SessionEventToolCall call        -> /* call.name()、call.arguments() */
    case SessionEventTurnEnd end          -> /* 结束：end.reason() */
    default -> { /* 其余类型 */ }
}
```
