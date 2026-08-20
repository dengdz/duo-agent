# 推理模型

深度推理模型（DeepSeek-R1、`deepseek-reasoner` 等）在给出回答前会进行长思考。duo-agent 对推理模型提供一等公民支持：独立的推理超时、思考内容分离、不截断长输出。

## 启用推理模式

推理配置属于模型本身，全部在 `DeepSeekModel.builder()` 上设置：

```java
DuoModel model = DeepSeekModel.builder()
        .apiKey(System.getenv("DEEPSEEK_API_KEY"))  // 缺省回落环境变量
        .model("deepseek-reasoner")                 // R1 推理模型
        .contextWindow(64000)
        .enableReasoning(true)                      // 启用推理模式
        .reasoningTimeout(Duration.ofMinutes(8))    // 思考最长 8 分钟（默认 5 分钟）
        .build();                                   // baseUrl 默认官方端点，可省略

var agent = DuoAgent.builder()
        .model(model)                              // 必填：模型实例
        .build();
```

## 超时自动切换

| 模式 | 生效超时 | 配置位置 | 默认值 |
|------|---------|---------|--------|
| 普通（`enableReasoning(false)`） | `timeout` | `DuoAgent.builder().timeout(...)` | 60 秒 |
| 推理（`enableReasoning(true)`） | `reasoningTimeout` | `DeepSeekModel.builder().reasoningTimeout(...)` | 5 分钟 |

超时触发时整轮失败，错误信息区分模式（"推理调用超时" / "LLM 调用超时"）。

HTTP 层还有一层兜底：Agent 组装时自动按 `max(Agent timeout, Model reasoningTimeout) + 1 分钟` 设置单请求整体超时——你配置多长的推理超时都不会被 HTTP 层提前掐断。

## 思考过程的可见性

推理模型输出分两部分，duo-agent 原生分离处理：

| 内容 | call | stream |
|------|------|--------|
| 思考过程（`<think>`） | 不可见（不进入最终文本） | `ReasoningDelta` 增量实时推送 |
| 最终回答 | 正常返回 | `TextDelta` 增量推送 |

想在前端展示"模型正在思考…"的界面，用 `stream()` 订阅 `ReasoningDelta`：

```java
agent.stream("证明 √2 是无理数").subscribe(new Flow.Subscriber<>() {
    @Override public void onNext(SessionEvent event) {
        if (event instanceof SessionEventAssistantChunk c
                && c.chunk() instanceof StreamChunk.ReasoningDelta r) {
            showThinkingBubble(r.text());   // 思考气泡
        }
        // TextDelta 等其他事件正常处理
    }
    // ...
});
```

## maxOutputTokens 的注意事项

> ⚠️ 推理模型的 `max_tokens` 限额**包含思考过程**。设置过小会导致：思考占满额度 → 回答被截断甚至为空。

**最佳实践：推理模型不设置 `maxOutputTokens`**（duo-agent 默认不设置，由模型自行决定）。普通模型需要限制输出时才设置。

## 完整示例

参见 `duo-agent-example/src/main/java/com/example/ReasonerExample.java`——用鸡兔同笼问题验证推理链路：思考期静默（过滤）、回答流式输出、8 分钟超时余量。

## 常见问题

**思考期有多长？**
取决于问题难度：简单问题几秒，复杂数学/证明可能数分钟。`reasoningTimeout` 按"最坏情况"配置。

**思考过程会消耗上下文吗？**
思考作为独立的内容块（`Reasoning`）记录在事件日志中，下一次请求模型时按 DeepSeek API 约定处理。

**中途取消会怎样？**
`stream()` 订阅者 `cancel()` 停止推送，但底层推理继续执行完毕（不中断 API 调用）。
