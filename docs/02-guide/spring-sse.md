# Spring Boot SSE 桥接

把 duo-agent 的流式输出接入前端。duo-agent 是**纯 Java SDK，不绑定任何框架**——`stream()`/`chatEvents()` 返回的 `Flow.Publisher` 是 Reactive Streams 中立标准，Spring Boot 应用在自己的代码里桥接即可，无需 duo-agent 侧的任何额外依赖。

> ⚠️ **关键前提：每请求一个 Agent 实例。** DuoAgent 非线程安全且 Session 有状态，不要注入单例 Agent 复用；`DuoAgent.builder()` 构建成本极低，每个请求新建。

## 方案一：Spring MVC（SseEmitter）

```java
@RestController
public class ChatController {

    @PostMapping(value = "/api/chat/stream")
    public SseEmitter chat(@RequestBody ChatRequest request) {
        SseEmitter emitter = new SseEmitter(0L);  // 不超时
        var agent = DuoAgent.builder()            // 每请求新建 Agent
                .apiFormat("openai")
                .baseUrl("https://api.deepseek.com")
                .apiKey(System.getenv("DEEPSEEK_API_KEY"))
                .model("deepseek-chat")
                .contextWindow(128000)
                .withCodeTools()
                .build();

        agent.stream(request.message()).subscribe(new Flow.Subscriber<>() {
            private Flow.Subscription subscription;

            @Override public void onSubscribe(Flow.Subscription s) {
                subscription = s;
                s.request(Long.MAX_VALUE);
            }
            @Override public void onNext(String chunk) {
                try {
                    emitter.send(SseEmitter.event().name("delta").data(chunk));
                } catch (IOException e) {
                    subscription.cancel();  // 客户端断开，停止推送
                }
            }
            @Override public void onError(Throwable t) {
                try {
                    emitter.send(SseEmitter.event().name("error").data(t.getMessage()));
                } catch (IOException ignored) { }
                emitter.complete();
            }
            @Override public void onComplete() {
                try {
                    emitter.send(SseEmitter.event().name("done"));
                } catch (IOException ignored) { }
                emitter.complete();
            }
        });
        return emitter;
    }
}
```

## 方案二：Spring WebFlux（Flux）

WebFlux 原生就是 Reactive Streams，桥接只需一行 `Flux.from(...)`：

```java
@RestController
public class ChatController {

    @PostMapping(value = "/api/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> chat(@RequestBody ChatRequest request) {
        var agent = DuoAgent.builder()/* 同上配置 */ .build();
        return Flux.from(agent.stream(request.message()))
                .map(chunk -> ServerSentEvent.<String>builder(chunk).event("delta").build())
                .concatWith(Flux.just(ServerSentEvent.<String>builder("").event("done").build()))
                .onErrorResume(e -> Flux.just(
                        ServerSentEvent.<String>builder(e.getMessage()).event("error").build()));
    }
}
```

背压在 WebFlux 下**端到端打通**：前端消费慢 → Netty 缓冲堆积 → Flux 自动减少 `request(n)` → SDK 内部缓冲兜住 → 不丢数据、不爆内存。

## 多事件类型（chatEvents → SSE）

前端要渲染完整工作过程（工具调用、思考过程）时，把 `stream` 换成 `chatEvents`，SSE 的 `event:` 字段直接用事件类型：

```java
agent.chatEvents(request.message()).subscribe(new Flow.Subscriber<>() {
    // onSubscribe 同上
    @Override public void onNext(SessionEvent event) {
        try {
            emitter.send(SseEmitter.event()
                    .name(event.type())          // "delta"粒度变成事件类型粒度
                    .data(describe(event)));     // 按需序列化事件内容
        } catch (IOException e) {
            subscription.cancel();
        }
    }
    // onError / onComplete 同上
});
```

前端即可按 `tool/call` / `tool/result` / `assistant/chunk` / `turn/end` 等事件名（即 `type()` 的斜杠形式原值）分别渲染。

## 前端接入（fetch 流式读取，支持 POST）

```js
const resp = await fetch('/api/chat/stream', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ message: '写一个排序算法' })
});
const reader = resp.body.getReader();
const decoder = new TextDecoder();
let buffer = '';
while (true) {
    const { done, value } = await reader.read();
    if (done) break;
    buffer += decoder.decode(value, { stream: true });
    // SSE 帧以空行（\n\n）分隔，每帧内解析 event: 与 data: 行
    const frames = buffer.split('\n\n');
    buffer = frames.pop();  // 最后一段可能不完整，留到下一轮
    for (const frame of frames) {
        const event = /^event: (.+)$/m.exec(frame)?.[1];
        const data = /^data: (.*)$/m.exec(frame)?.[1] ?? '';
        if (event === 'delta') output.textContent += data;
        if (event === 'done')  console.log('对话完成');
        if (event === 'error') console.error('对话失败:', data);
    }
}
```

> 💡 简单 GET 场景可用浏览器原生 `new EventSource(url)`，但它不支持 POST——消息体较大或涉及鉴权时推荐 fetch 方案。

## 实践建议

- **完成/错误信号约定** —— 用 `event: done` / `event: error` 哨兵帧让前端明确区分正常结束与失败，不要只断连接
- **心跳保活** —— 推理模型思考期可能数十秒无输出，中间有 Nginx/网关时需定期发送 SSE 注释行（`: ping`）防止空闲连接被掐断
- **断线语义** —— 当前流是一次性对话，断线即本轮作废，前端重新发起即可（基于 session seq 的断点续传在路线图中）
