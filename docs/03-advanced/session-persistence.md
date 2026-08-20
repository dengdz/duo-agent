# 会话持久化

duo-agent 的会话是 append-only 事件日志，`JsonlSessionPersistence` 把它落盘为 JSONL 文件——支持崩溃恢复、跨进程续聊。

> ⚠️ 持久化当前**无门面入口**（`DuoAgentBuilder` 未暴露），需手动组装底层组件。完整可运行代码参见示例 `SessionRecoveryExample`。

## 能力一览

| 能力 | 说明 |
|------|------|
| **write-behind 批处理** | 事件先入内存缓冲，200ms 定时批量刷盘（单线程调度器） |
| **落盘保证** | `append()` / `flush()` 返回即已 `FileChannel.force`（同步刷盘） |
| **原子发布** | 首批落盘走临时文件 + 原子改名（`ATOMIC_MOVE`），崩溃不留半写文件 |
| **seq 连续性校验** | 写入侧强制每条事件 seq 严格连续（= 日志下标），不连续即失败 |
| **崩溃修复** | `load()` 自动处理：撕裂的末行丢弃；崩溃遗留的 open turn 闭合（合成工具结果 + `turn/end{interrupted}`） |
| **会话清单** | `list()` 列出目录下全部会话头 |

文件布局：`{baseDir}/{base64url(sessionId)}.jsonl`，首行会话头 + 每行一个事件。

## 基本用法

### 创建持久化会话

```java
try (var persistence = new JsonlSessionPersistence(Path.of("sessions"))) {
    // create：新会话（磁盘已有同名日志会拒绝，防止误覆盖）
    persistence.create(new SessionHeader(0, SESSION_ID, System.currentTimeMillis(),
            null, null, null, null, null, null));

    var session = new Session(SESSION_ID);
    // 关键接线：Session 事件 → 持久化（acceptFromListener 是非抛错入口）
    session.onAppend(event -> persistence.acceptFromListener(SESSION_ID, event));

    var agent = new ReactLoopAgent(
            new SessionId("agent-1"), agentOptions, session,
            llmRuntime, systemPrompt, toolRegistry);
    agent.followup(userMessage("你好"));
    agent.whenIdle();

    persistence.flush(SESSION_ID);   // 同步落盘（write-behind 缓冲强制刷出）
}
```

### 恢复会话（跨进程续聊）

```java
try (var persistence = new JsonlSessionPersistence(Path.of("sessions"))) {
    // load：读取日志 + 自动崩溃修复（撕裂末行丢弃、open turn 闭合）
    var inspection = persistence.load(SESSION_ID);

    // 种子构造：历史完整恢复（注意 events() 是 List，种子构造器收数组）
    var session = new Session(SESSION_ID,
            inspection.events().toArray(new SessionEvent[0]),
            inspection.header(),
            // 初始监听器在构造期（含 end-seed 边界事件）之前注册，恢复期也不遗漏
            event -> persistence.acceptFromListener(SESSION_ID, event));

    var agent = new ReactLoopAgent(/* ...同上配置... */);
    agent.followup(userMessage("继续刚才的话题"));   // 模型记得全部历史
}
```

## 崩溃恢复语义

进程意外退出时正在进行的 turn 处于"open"状态。`load()` 的修复策略：

1. **撕裂末行**（写到一半的 JSON 行）→ 丢弃
2. **悬挂的工具调用**（`tool/call` 有、`tool/result` 无）→ 合成结构化错误结果：
   - 工具已启动但结果未知 → `isError` 结果注明"结果未知，如需精确请幂等重试"
   - 工具未启动 → 注明"未执行，可直接重试"
3. **open turn** → 追加 `step/end` + `turn/end{reason: interrupted}` 闭合

修复只发生在**加载时**且**只修复非活跃会话**——运行中会话二次 `load` 只读返回快照。

## 与事件流的关系

持久化与 `chatEvents()` 共享同一事件源：落盘的每一行就是 `chatEvents()` 推送的每一个事件。因此：

- 回放一个持久化会话 = 按 seq 顺序读取 JSONL 行
- `sourceEventSeqs` 回链在持久化中同样保留（token 级追溯不丢失）

## 路线图

- Builder 门面入口（如 `builder.sessionFile(path)`）——规划中
- 基于 seq 的断线重连（`chatEvents` 从指定 seq 续传）——规划中
