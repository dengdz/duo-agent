# 多厂商模型接入

按 API 协议格式选择 Model 类型，厂商只是配置值。

## 三种协议格式

| 协议格式 | Model 类型 | 端点 | 覆盖厂商 |
|---------|-----------|------|---------|
| Chat Completions | `ChatCompletionsModel` | `{base}/chat/completions` | DeepSeek、Kimi、通义、Ollama、vLLM 及一切 OpenAI 兼容端点 |
| Anthropic Messages | `AnthropicModel` | `{base}/v1/messages` | 智谱 GLM（Anthropic 兼容端点）、Claude 及兼容端点 |
| Responses | `ResponsesModel` | `{base}/responses` | OpenAI 官方（gpt-5 / o 系列） |

`DeepSeekModel` 是 Chat Completions 协议的 DeepSeek 预设（等价于
`ChatCompletionsModel` + 官方端点 + `reasoning_content` 字段），保留它是为了
0.2.0 兼容。

## 接入示例

### 智谱 GLM（Anthropic 兼容端点）

```java
DuoModel glm = AnthropicModel.builder()
        .baseUrl("https://open.bigmodel.cn/api/anthropic")
        .apiKey(System.getenv("ZHIPU_API_KEY"))
        .model("glm-4.6")
        .contextWindow(200000)
        .enableReasoning(true)          // 扩展思考（budget_tokens 默认 10240）
        .build();
```

### 企业内部中转站 / 网关（Anthropic 协议）

内部 OpenRouter 类中转站只要支持 Anthropic 协议即可直连——`baseUrl` 指向中转站，
模型名按中转站实际支持的填写：

```java
DuoModel relay = AnthropicModel.builder()
        .baseUrl("https://your-relay.example.com")   // 中转站地址
        .apiKey(System.getenv("RELAY_API_KEY"))       // 中转站的消费者 AK
        .model("deepseek-v4-flash")                    // 按中转站支持的模型
        .build();
```

SDK 已在真实企业中转站上验证通过，并内置了两类网关兼容：

- **`data:` 无空格写法**——部分网关重写 SSE 行为 `data:{...}`（冒号后无空格，
  SSE 规范两种都合法），解析器两种写法都认
- **事件字段重排**——事件类型判别以 SSE 标准的 `event:` 行为权威来源，
  不依赖 `data` 内 JSON 的字段顺序（网关把 `delta` 排在 `type` 前也不会误判）

### DeepSeek 一家测三协议

DeepSeek 同时提供三种协议的端点（同一把 key）：

| 协议 | base_url | 思考透出 |
|------|----------|---------|
| Chat Completions | `https://api.deepseek.com` | ✓ `reasoning_content` 直接透出 |
| Anthropic Messages | `https://api.deepseek.com/anthropic` | ✓ thinking 块透出（无需参数） |
| Responses | `https://api.deepseek.com` | ✗ 端点当前忽略 summary，思考不可见 |

### 混合推理模型必须开 enableReasoning（时间预算）

`enableReasoning` 决定应用层超时档位：普通模式 60 秒、推理模式默认 5 分钟。
**混合推理模型**（如 DeepSeek v4 系列——不发思考参数也默认思考）必须开
`enableReasoning(true)`，否则隐性思考时间会吃穿普通模式的 60 秒预算，
表现为偶发「模型调用超时（应用层上限 PT1M）」。

### Ollama 本地部署（无鉴权）

```java
DuoModel local = ChatCompletionsModel.builder()
        .baseUrl("http://localhost:11434/v1")  // 含版本前缀
        .model("qwen3:32b")
        .build();                               // apiKey 可选：不发 Authorization 头
```

### OpenAI 官方（Responses 格式）

> Responses 协议的思考默认不透出，SDK 在 `enableReasoning(true)` 时自动请求
> `summary: "auto"`（OpenAI 官方端点可收到思考摘要；DeepSeek 的 Responses 兼容
> 端点当前忽略该参数，思考不可见）。

```java
DuoModel gpt = ResponsesModel.builder()
        .apiKey(System.getenv("OPENAI_API_KEY"))
        .model("gpt-5.2")
        .enableReasoning(true)
        .reasoningEffort("high")         // minimal / low / medium / high
        .build();
```

### Kimi / 通义等 OpenAI 兼容端点

```java
DuoModel kimi = ChatCompletionsModel.builder()
        .baseUrl("https://api.moonshot.cn/v1")
        .apiKey(System.getenv("MOONSHOT_API_KEY"))
        .model("kimi-latest")
        .build();
```

## 协议差异对照

| 差异点 | Chat Completions | Anthropic Messages | Responses |
|--------|-----------------|-------------------|-----------|
| 系统提示 | messages 内 system role | 顶层 `system` 参数 | 顶层 `instructions` 参数 |
| 鉴权 | `Authorization: Bearer` | `x-api-key` + `anthropic-version` | `Authorization: Bearer` |
| 推理控制 | 厂商私有字段（`reasoningContentField` 参数化） | `thinking: {budget_tokens}`（`thinkingBudgetTokens`） | `reasoning: {effort}`（`reasoningEffort`） |
| apiKey | 可选（本地端点无鉴权） | 必填（回落 `ANTHROPIC_API_KEY`） | 必填（回落 `OPENAI_API_KEY`） |
| baseUrl 默认 | 无（必填） | Anthropic 官方端点 | OpenAI 官方端点 |

所有协议差异在适配器层终结——`StreamChunk` 及其后的 Agent 全链路
（工具配对、事件日志、压缩）对协议无感知。

## 流式思考字段（Chat Completions 特有）

部分厂商经非标准字段透出思考增量（DeepSeek/Qwen 系为 `reasoning_content`），
`ChatCompletionsModel` 经 `reasoningContentField` 参数化：

```java
DuoModel reasoner = ChatCompletionsModel.builder()
        .baseUrl("https://api.deepseek.com")
        .apiKey(System.getenv("DEEPSEEK_API_KEY"))
        .model("deepseek-reasoner")
        .enableReasoning(true)
        .reasoningContentField("reasoning_content")  // 以厂商文档为准
        .build();
```

未设置该字段时思考流不解析（标准端点行为）；`DeepSeekModel` 已内置该预设。

## 自定义协议实现

实现 `AbstractDuoModel` 的 `newAdapter(Duration)` 钩子即可接入任意协议：

```java
public final class MyProtocolModel extends AbstractDuoModel {
    @Override
    protected LlmAdapter newAdapter(Duration httpTimeout) {
        return new MyProtocolAdapter(httpTimeout);  // 产出标准 StreamChunk
    }
}
```

适配器负责把协议响应翻译为 `StreamChunk`（七种变体：BlockStart / TextDelta /
ReasoningDelta / ToolCallDelta / BlockEnd / Usage / Finish），其余层无需改动。
