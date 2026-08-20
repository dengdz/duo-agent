# 对话 API

duo-agent 提供四种对话模式，从最简单到最强大逐级递进。所有模式共享同一个 Agent 实例与 Session（对话历史自动累积）。

## 模式总览

| 模式 | 签名 | 适用场景 |
|------|------|---------|
| 同步 | `String chat(String message)` | 脚本、批处理、最简单直接 |
| 异步 | `CompletableFuture<String> chatAsync(String message)` | 并发处理、不阻塞当前线程 |
| 文本流 | `Flow.Publisher<String> stream(String message)` | 聊天界面逐字输出 |
| 事件流 | `Flow.Publisher<SessionEvent> chatEvents(String message)` | 渲染完整 Agent 工作过程 |

## 同步对话

```java
String response = agent.chat("帮我分析这段代码的设计");
```

- 阻塞直到整个对话轮完成（含全部工具调用循环）
- 自动校验：执行失败（如 LLM 调用超时、未生成响应）抛出 `IllegalStateException`，不会静默返回空串
- 消息为空抛 `IllegalArgumentException`

## 异步对话

```java
agent.chatAsync("分析项目依赖")
     .thenAccept(System.out::println)
     .exceptionally(e -> { e.printStackTrace(); return null; });
```

内部使用 `CompletableFuture.supplyAsync`（commonPool）。生产环境高并发场景建议自行指定线程池包装 `chat`。

## 文本流（stream）

```java
agent.stream("写一个排序算法").subscribe(new Flow.Subscriber<>() {
    private Flow.Subscription subscription;

    @Override public void onSubscribe(Flow.Subscription s) {
        subscription = s;
        s.request(Long.MAX_VALUE);     // 或分批 s.request(10)
    }
    @Override public void onNext(String chunk) {
        System.out.print(chunk);       // 文本增量实时到达
    }
    @Override public void onError(Throwable t) { /* 失败 */ }
    @Override public void onComplete() { /* 对话轮结束 */ }
});
```

只推送**回答文本的增量**：思考过程（`<think>`）与工具调用参数不推送。Spring WebFlux 用户可 `Flux.from(...)` 一行桥接。

详见 [流式输出](streaming.md)。

## 事件流（chatEvents）

```java
agent.chatEvents("分析当前目录的代码").subscribe(new Flow.Subscriber<>() {
    @Override public void onNext(SessionEvent event) {
        switch (event) {
            case SessionEventAssistantChunk c when c.chunk() instanceof StreamChunk.ReasoningDelta r
                    -> showThinking(r.text());       // 思考过程
            case SessionEventToolCall call  -> showTool(call.name(), call.arguments());
            case SessionEventToolResult res -> showResult(res.message());
            case SessionEventTurnEnd end    -> finish(end.reason());  // 完成信号
            default -> { }
        }
    }
    // ... 其余回调同上
});
```

全量透传 15 种会话事件（带 seq 单调序号），前端可渲染完整的 Agent 工作过程——类似 IDE Agent 界面。详见 [流式输出](streaming.md) 与 [事件类型参考](../05-reference/events.md)。

## 多轮对话

同一个 Agent 实例上连续调用，历史自动累积：

```java
agent.chat("什么是快速排序？");       // 第一轮
agent.chat("给我一个 Java 实现");     // 第二轮，模型记得上文
agent.chat("加上单元测试");           // 第三轮
```

## 线程安全

> ⚠️ **同一 DuoAgent 实例不是线程安全的。** 实例共享一个 Session（对话状态），并发调用同一实例会产生交错的历史。需要并发处理多个请求时，为每个请求创建独立实例——`DuoAgent.builder()` 构建成本极低。

## 错误处理约定

| 场景 | 行为 |
|------|------|
| 消息为空 | `IllegalArgumentException`（调用即抛） |
| LLM 调用超时 | 推理模式用 `reasoningTimeout`（默认 5 分钟），普通模式用 `timeout`（默认 60 秒）；超时导致 turn 失败 |
| 执行失败 / 未生成响应 | `IllegalStateException`（chat）或 `onError`（流式）；具体失败原因（如 `TIMEOUT` 错误码）记录在 session 日志的 `turn/end` 事件的 `Error(LlmFailure)` 中，订阅 `chatEvents()` 可获取 |
| 工具执行失败 | 不中断对话——错误结构化回传给模型，模型自行决策重试或调整 |

## 访问底层实例（高级用户）

```java
Agent rawAgent = agent.getAgent();     // 底层 Agent：followup / steer / inject / cancel / whenIdle
Session session = agent.getSession();  // 会话：events() 事件日志 / deriveMessages() / onAppend 订阅
```

需要精细控制（step 级转向、事件订阅、多轮手动驱动）时使用，详见 [ReAct 循环](../04-architecture/react-loop.md)。
