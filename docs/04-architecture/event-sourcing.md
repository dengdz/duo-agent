# 事件溯源

duo-agent 的会话不是"可变的消息列表"，而是一个 **append-only 事件日志**——这是整个系统最重要的设计决策。

## 核心原则

> **Session 事件日志是唯一事实源。** 持久化、回放、UI 订阅、模型上下文派生——全部从同一个事件流派生，不存在状态漂移。

```java
// 每次状态变化 = 一条不可变事件
session.append(new SessionEventUserMessage(seq, message, new SurfaceOp.Append()));
// 事件一旦写入，永不修改、永不删除
```

## 事件信封

每个事件共享统一结构（sealed interface `SessionEvent`，15 种实现）：

| 字段 | 说明 |
|------|------|
| `type()` | 事件类型字符串（如 `"assistant/chunk"`），持久化格式的判别键 |
| `seq()` | 会话内**严格单调递增**的序号（= 日志下标，从 0 起） |
| `time()` | Unix 毫秒时间戳 |
| `ignorable()` | 读者不认识 type 时可安全跳过（格式向前兼容机制） |
| `surfaceOp()` | 仅表面事件非 null（Append / Replace） |
| `sourceEventSeqs()` | 仅表面事件可携带：引用构成它的源事件 seq |

## 三类事件

### 表面事件（3 种）—— 决定模型上下文

只有 `user/message`、`assistant/message`、`tool/result` 三种事件进入模型可见表面：

- 必须携带 `surfaceOp`；其余事件禁止携带（`SurfaceManager` 强制校验）
- `deriveMessages()` 从表面节点投影出 LLM 消息历史，增量缓存

### 过程事件 —— 回放与观测

`assistant/chunk`（token 级增量）、`tool/call`、`turn/*`、`step/*` 等：不进模型上下文，但完整记录"发生了什么"。`stream()` 与 UI 渲染的数据源。

### 簿记事件 —— 事务边界

`compaction/start|end`、`session/end-seed`：压缩事务、持久化种子边界。

## sourceEventSeqs：从消息回溯到 token

`assistant/message` 事件携带 `sourceEventSeqs`——拼出这条完整消息的**全部 chunk 事件 seq**：

```
assistant/chunk  seq=3   (block-start: text)
assistant/chunk  seq=4   (text-delta "你")
assistant/chunk  seq=5   (text-delta "好")
assistant/chunk  seq=6   (block-end)
...
assistant/message seq=9  sourceEventSeqs=[3,4,5,6,7,8]   ← 回链
```

任何一条最终消息都能追溯到 token 级增量——回放保真、审计可查。

## SurfaceOp.Replace：压缩不改历史

上下文压缩不删除任何事件。它写入一条携带 `SurfaceOp.Replace(startSeq, endSeq)` 的摘要 checkpoint 消息：

- **日志**：原始事件原样保留（事实源不变）
- **表面**：`[startSeq, endSeq]` 区间的节点被摘要节点替换，`deriveMessages()` 自动应用
- 回放完整日志仍能看到压缩前的全部对话

`replaceGeneration` 递增触发派生缓存失效——压缩后下一次 `deriveMessages()` 全量重建。

## 持久化即事件序列化

`SessionEventCodec` 把每个事件编码为一行 JSON（扁平判别格式，sealed 层级用 `"k"` 判别键）；`JsonlSessionPersistence` 逐行落盘。因此：

- **回放会话 = 按 seq 顺序读取 JSONL**
- `stream()` 的实时推送与持久化内容**逐字节一致**
- 未知 type 解码拒绝（安全），未知字段忽略（向前兼容）

## seq 的三个用途

1. **顺序保证**：严格连续（写入侧强制校验），乱序即数据损坏
2. **去重**：UI 订阅重复收到同 seq 事件即丢弃
3. **断线重连基础**（路线图）：客户端记住最后 seq，重连后从 seq+1 续传
