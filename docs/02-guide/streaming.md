# 流式输出

duo-agent 的流式 API 基于 JDK 原生 `java.util.concurrent.Flow`（Reactive Streams 规范），零第三方依赖。`stream()` 一个方法承担全部流式场景：**全量透传**会话事件，只要文本增量时在订阅侧过滤。

## stream()：事件流

```java
Flow.Publisher<SessionEvent> publisher = agent.stream("写一个排序算法");
```

推送**全部会话事件**：思考、文本、工具调用与结果、step 边界、turn 结束原因。事件信封含 `seq`（单调递增序号），是未来断线重连的基础。事件不做服务端过滤，订阅者按需 `instanceof` 模式匹配自行取用。

### 只要文本增量

聊天界面逐字输出回答时，过滤 `assistant/chunk` 中的 `TextDelta`：

```java
agent.stream("写一个排序算法").subscribe(new Flow.Subscriber<>() {
    private Flow.Subscription subscription;

    @Override public void onSubscribe(Flow.Subscription s) {
        subscription = s;
        s.request(Long.MAX_VALUE);
    }
    @Override public void onNext(SessionEvent event) {
        if (event instanceof SessionEventAssistantChunk c
                && c.chunk() instanceof StreamChunk.TextDelta d) {
            System.out.print(d.text());  // 文本增量实时到达
        }
    }
    @Override public void onError(Throwable t) { /* 失败 */ }
    @Override public void onComplete() { /* 对话轮结束 */ }
});
```

需要的 import：`dev.duo.model.session.SessionEvent`、`dev.duo.model.session.SessionEventAssistantChunk`、`dev.duo.model.llm.StreamChunk`。

### 消费模式

需要渲染完整工作过程（类似 IDE Agent 界面）时，按事件类型分发：

```java
agent.stream("分析代码").subscribe(new Flow.Subscriber<>() {
    private Flow.Subscription subscription;

    @Override public void onSubscribe(Flow.Subscription s) {
        subscription = s;
        s.request(Long.MAX_VALUE);
    }

    @Override public void onNext(SessionEvent event) {
        switch (event) {
            case SessionEventAssistantChunk c
                    when c.chunk() instanceof StreamChunk.ReasoningDelta r ->
                    showThinking(r.text());      // 推理模型思考过程
            case SessionEventAssistantChunk c
                    when c.chunk() instanceof StreamChunk.TextDelta t ->
                    showAnswer(t.text());        // 回答文本
            case SessionEventToolCall call ->
                    showTool(call.name(), call.arguments());
            case SessionEventToolResult res ->
                    showResult(res.message());
            case SessionEventAssistantMessage msg -> {
                // 组装完成的完整消息：usage 与回链信息
            }
            case SessionEventTurnEnd end ->
                    finish(end.reason());        // 6 种结束原因
            default -> { /* 边界事件按需处理 */ }
        }
    }

    @Override public void onError(Throwable t) { /* ... */ }
    @Override public void onComplete() { /* ... */ }
});
```

### 完成信号

`turn/end` 事件是整轮对话的**权威结束信号**，`TurnEndReason` 区分六种结束原因：

| 原因 | 含义 |
|------|------|
| `Completed` | 正常完成 |
| `Aborted` | 被取消（**协议已定义，主流程暂无生产点**——当前 cancel 简化实现以 Completed 收尾） |
| `Blocked` | 预步被拒（pre-step hook 拒绝了输入） |
| `Error` | 失败（结构化 `LlmFailure`：消息/错误码/HTTP 状态） |
| `MaxTokens` | 输出达到 token 上限 |
| `Interrupted` | 进程崩溃后的恢复标记 |

事件字段完整清单见 [事件类型参考](../05-reference/events.md)。

### 行为语义

| 语义 | 说明 |
|------|------|
| **冷发布者** | 调用 `stream()` 不发任何请求；`subscribe()` 时才发起对话。不订阅零消耗 |
| **单订阅** | 每个 Publisher 仅支持订阅一次，重复订阅收到 `onError`。需要重试请重新调用 `stream()` |
| **背压** | `request(n)` 控制拉取节奏；消费慢于生产时事件在内部缓冲，不丢失 |
| **取消** | `cancel()` 停止推送并释放资源；但底层对话轮会继续执行完毕（不中断模型推理，仍消耗一次 API 调用） |
| **完成** | `turn/end` 事件即整轮对话的权威结束信号，随后 `onComplete` |
| **慢消费者保护** | 内部缓冲上限 8192 条，溢出即 `onError` 终止订阅 |
| **线程模型** | `onNext` 等回调可能在驱动线程上执行，订阅者需保证自身处理的线程安全 |

### 与响应式生态互操作

```java
// Spring WebFlux / Reactor
Flux<SessionEvent> flux = Flux.from(agent.stream("..."));

// RxJava
Flowable<SessionEvent> flowable = Flowable.fromPublisher(agent.stream("..."));

// Mutiny（注意收集后是 Multi，按需再转 Uni/字符串）
Multi<SessionEvent> multi = Multi.createFrom().publisher(agent.stream("..."));
```

## 如何选择

| 需求 | 选择 |
|------|------|
| 聊天界面逐字显示回答 | `stream()`，订阅侧过滤 `TextDelta` |
| 展示 Agent 的工具调用过程（类似 IDE Agent） | `stream()` |
| 展示推理模型的思考过程 | `stream()`（订阅 `ReasoningDelta`） |
| 需要 token 用量统计 | `stream()`（`assistant/message` 事件携带 usage） |
| 后端只需要最终文本 | `call()` |

## 前端对接

→ [Spring Boot SSE 桥接](spring-sse.md)：完整的服务端 + 前端接入方案
