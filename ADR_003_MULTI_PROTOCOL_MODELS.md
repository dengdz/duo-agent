# ADR 003: 多厂商模型适配——按 API 协议格式分类

**状态**: 已实施
**日期**: 2026-08-21
**决策者**: zhangyl
**背景会话**: 本次多厂商适配设计会话

---

## 背景

0.2.0 的 `DuoModel` 体系只有 DeepSeek 一个实现。多厂商接入（智谱 GLM、Ollama 本地部署、
Kimi、通义等）是 0.3.0 的核心目标。

最初考虑按厂商建模（`ZhipuModel`、`QwenModel`、`OllamaModel`…），但存在两个问题：

1. **类爆炸**——每厂商一个类，而大多数厂商的协议完全相同
2. **抽象错位**——厂商之间的真实差异是「API 协议格式」，不是厂商身份

参考 ZCode 的模型供应商配置抽象（名称 + Base URL + API Key + **API 格式下拉框**，
格式仅三类：Anthropic Messages / Chat Completions / Responses），确认按协议格式分类
是经过业界验证的切分：**厂商只是配置值，协议才是类型**。

## 决策

**按 API 协议格式建 Model 类型，厂商退化为 Builder 配置值。**

0.3.0 实现全部三类协议：

| 协议格式 | 端点 | Model 类型 | 覆盖厂商 |
|---------|------|-----------|---------|
| Chat Completions | `{base}/chat/completions` | `ChatCompletionsModel` | DeepSeek、Kimi、通义、Ollama、vLLM 及一切 OpenAI 兼容端点 |
| Anthropic Messages | `{base}/v1/messages` | `AnthropicModel` | 智谱 GLM（Anthropic 兼容端点）、Claude 及一切 Anthropic 兼容端点 |
| Responses | `{base}/responses` | `ResponsesModel` | OpenAI 官方（gpt-5/o 系列——新模型能力只在 Responses 提供，Chat Completions 已进入维护态） |

---

## 核心设计

### 1. 包结构

```
dev.duo.model.openai.ChatCompletionsModel        ← 新增：Chat Completions 通用 Model
dev.duo.model.openai.ResponsesModel              ← 新增：OpenAI Responses Model
dev.duo.model.anthropic.AnthropicModel           ← 新增：Anthropic Messages Model
dev.duo.model.deepseek.DeepSeekModel             ← 保留：DeepSeek 预设（0.2.0 API 零变化）
dev.duo.adapter.openai.ChatCompletionsAdapter    ← 新增：泛化自 DeepSeekAdapter 四件套
dev.duo.adapter.openai.OpenAiRequestBuilder
dev.duo.adapter.openai.OpenAiSseParser
dev.duo.adapter.openai.OpenAiJsonExtractor
dev.duo.adapter.openai.ResponsesAdapter          ← 新增：Responses 协议四件套
dev.duo.adapter.openai.ResponsesRequestBuilder
dev.duo.adapter.openai.ResponsesSseParser
dev.duo.adapter.anthropic.AnthropicAdapter       ← 新增：Anthropic 协议四件套
dev.duo.adapter.anthropic.AnthropicRequestBuilder
dev.duo.adapter.anthropic.AnthropicSseParser
dev.duo.adapter.deepseek.DeepSeekAdapter         ← 保留为委托薄壳（公开类，不 breaking）
```

三种协议同属 `model.openai` / `adapter.openai` 的仅前两种（OpenAI 协议族），类名前缀
（`OpenAi`/`Responses`）已消歧；`anthropic` 独立成包与协议边界一致。

`DeepSeekAdapter`/`DeepSeekModel` 保留理由：0.2.0 刚发布的公开 API，0.3.0 是增量版本，
不引入破坏性变更。`DeepSeekModel` 语义从「唯一实现」变为「Chat Completions 协议的
DeepSeek 预设」。

### 2. ChatCompletionsModel 设计

`extends AbstractDuoModel`，`newAdapter(Duration)` 返回 `ChatCompletionsAdapter`。

**Builder 参数**：

| 参数 | 必填 | 默认 | 说明 |
|------|------|------|------|
| `baseUrl(String)` | ✅ | — | 任意 OpenAI 兼容端点（含版本前缀，如 Ollama `http://localhost:11434/v1`） |
| `model(String)` | ✅ | — | 模型名 |
| `apiKey(String)` | 可选 | null | **null 时不发 Authorization 头**（Ollama/vLLM 本地部署无鉴权）；云端忘配时由服务端 401 明确报错 |
| `reasoningContentField(String)` | 可选 | null | 流式思考字段名（DeepSeek/Qwen 系为 `"reasoning_content"`）；null 不解析思考流 |
| `systemPrompt` / `contextWindow` / `maxOutputTokens` / `temperature` / `enableReasoning` / `reasoningTimeout` | 可选 | 同 AbstractDuoModel 语义 | 不变 |

**协议变体的表达方式**：不引入 Spec 类型——协议内差异仅 `reasoningContentField` 与
「apiKey 可选」两点，以 `ChatCompletionsAdapter` 构造参数表达。等第三个协议变体
出现再抽象（最小抽象原则）。

**DeepSeekModel 薄壳化**：继续 `extends AbstractDuoModel`，Builder 默认值预设
（`baseUrl = https://api.deepseek.com`、apiKey 回落 `DEEPSEEK_API_KEY`、
`reasoningContentField = "reasoning_content"`），`newAdapter` 委托
`ChatCompletionsAdapter`。公开 API 与行为零变化。

### 3. AnthropicModel 设计

`extends AbstractDuoModel`，`getApiFormat()` 返回 `"anthropic"`。

**Builder 参数**：

| 参数 | 必填 | 默认 | 说明 |
|------|------|------|------|
| `baseUrl(String)` | ✅ | — | 如智谱 `https://open.bigmodel.cn/api/anthropic`（确切值查证后定） |
| `model(String)` | ✅ | — | 如 `glm-4.6`、`claude-sonnet-4-5` |
| `apiKey(String)` | ✅（回落环境变量 `ANTHROPIC_API_KEY`） | — | Anthropic 兼容端点均需鉴权 |
| `anthropicVersion(String)` | 可选 | `"2023-06-01"` | `anthropic-version` 请求头，兼容端点需要时可覆盖 |
| `thinkingBudgetTokens(long)` | 可选 | `10240` | `enableReasoning(true)` 时映射请求参数 `thinking: {type: "enabled", budget_tokens}` |
| 其余参数 | — | — | 同 AbstractDuoModel 语义 |

**协议差异处理**（与 Chat Completions 的对照）：

| 差异点 | Chat Completions | Anthropic Messages | 处理 |
|--------|-----------------|-------------------|------|
| 端点路径 | `/chat/completions` | `/v1/messages` | 各 Adapter 常量 |
| 鉴权头 | `Authorization: Bearer` | `x-api-key` + `anthropic-version` | 请求头分叉 |
| system | messages 内 system role | **顶层 `system` 参数** | AnthropicRequestBuilder 从 `GenerateOptions.system` 提取（现有 DeepSeekRequestBuilder 将其拼为 messages 首条 system role） |
| max_tokens | 可选 | **必填** | `GenerateOptions.maxTokens == null` 时 Adapter 层兜底默认 `8192` |
| 流式事件 | `choices[0].delta` 单一结构 | 9 种事件类型（见下表） | 独立 AnthropicSseParser |
| 工具调用 | `tool_calls` delta | `tool_use` block + `input_json_delta` | 映射到现有 `StreamChunk.ToolCallDelta` |
| 错误体 | `{error: {message, ...}}` | `{type: "error", error: {type, message}}` | 各自解析为 `LlmException`（携带 status） |

**Anthropic SSE 事件 → StreamChunk 映射**：

| Anthropic 事件 | StreamChunk |
|----------------|-------------|
| `message_start` | 忽略（可提取 usage.input_tokens） |
| `content_block_start` (type=text) | `BlockStart(BLOCK_TEXT)` |
| `content_block_start` (type=thinking) | `BlockStart(BLOCK_REASONING)` |
| `content_block_start` (type=tool_use) | `BlockStart(BLOCK_TOOL_CALL)` + `ToolCallDelta`（携带 id/name，argumentsDelta 为空串） |
| `content_block_delta` (text_delta) | `TextDelta` |
| `content_block_delta` (thinking_delta) | `ReasoningDelta` |
| `content_block_delta` (input_json_delta) | `ToolCallDelta`（argumentsDelta 累加） |
| `content_block_stop` | `BlockEnd`（携带组装完成的 ContentBlock） |
| `message_delta` (stop_reason/usage) | `Usage` + `Finish`（end_turn→`Stop`、tool_use→`ToolCalls`、max_tokens→`MaxTokens`） |
| `message_stop` | 流完成信号 |
| `ping` | 忽略 |
| `error` | `onError` |

`BlockAssembler` 及其后的 Agent 全链路（工具配对、事件日志、压缩）不做任何修改——
协议差异终结在 SseParser 层。

### 4. ResponsesModel 设计

`extends AbstractDuoModel`，`getApiFormat()` 返回 `"responses"`。仅无状态用法：
不使用 `previous_response_id`（服务端会话状态）与 OpenAI 托管内置工具
（web_search/file_search/computer use），状态管理与工具执行是 SDK 自身的职责。

**Builder 参数**：

| 参数 | 必填 | 默认 | 说明 |
|------|------|------|------|
| `baseUrl(String)` | 可选 | `https://api.openai.com/v1` | OpenAI 官方端点是唯一主流实现，给默认值；兼容端点可覆盖 |
| `model(String)` | ✅ | — | 如 `gpt-5.2`、`o4-mini` |
| `apiKey(String)` | ✅（回落环境变量 `OPENAI_API_KEY`） | — | 鉴权同 Bearer |
| `reasoningEffort(String)` | 可选 | `"medium"` | `enableReasoning(true)` 时映射请求参数 `reasoning: {effort}`（minimal/low/medium/high，Builder 校验取值） |
| 其余参数 | — | — | 同 AbstractDuoModel 语义 |

**与 Chat Completions 的协议差异**：

| 差异点 | Chat Completions | Responses | 处理 |
|--------|-----------------|-----------|------|
| 系统提示 | messages 内 system role | 顶层 `instructions` 参数 | RequestBuilder 分叉 |
| 输入结构 | `messages` 数组 | `input` items（message / function_call / function_call_output） | RequestBuilder 转换：assistant 的 tool_calls → `function_call` item，tool role → `function_call_output` item |
| 工具定义 | `{type:"function", function:{name,...}}` 嵌套 | `{type:"function", name, ...}` 平铺 | RequestBuilder 转换 |
| 推理控制 | 无（厂商私有字段） | `reasoning: {effort, summary}` | `reasoningEffort` 映射 |
| stop 参数 | 支持 | 不支持 | `GenerateOptions.stop` 忽略（文档标注，不报错） |
| 流式事件 | `choices[0].delta` | `response.*` 事件族 | 独立 ResponsesSseParser |

**Responses SSE 事件 → StreamChunk 映射**：

| Responses 事件 | StreamChunk |
|----------------|-------------|
| `response.output_item.added` (type=message) | `BlockStart(BLOCK_TEXT)` |
| `response.output_item.added` (type=reasoning) | `BlockStart(BLOCK_REASONING)` |
| `response.output_item.added` (type=function_call) | `BlockStart(BLOCK_TOOL_CALL)` + `ToolCallDelta`（携带 call_id/name，argumentsDelta 为空串） |
| `response.output_text.delta` | `TextDelta` |
| `response.reasoning_summary_text.delta` | `ReasoningDelta` |
| `response.function_call_arguments.delta` | `ToolCallDelta`（argumentsDelta 累加） |
| `response.output_item.done` | `BlockEnd`（携带组装完成的 ContentBlock） |
| `response.completed` | `Usage`（input_tokens/output_tokens 映射）+ `Finish(Stop)` |
| `response.incomplete` | `Finish(MaxTokens)`（reason 为 max_output_tokens 时） |
| `response.failed` | `onError`（`LlmException` 携带 status） |
| 其余 `response.*` 事件 | 忽略 |

### 5. getApiFormat() 语义升级

返回值从「隐式的 openai」变为**显式的协议标识**：

- `ChatCompletionsModel` / `DeepSeekModel` → `"openai"`
- `AnthropicModel` → `"anthropic"`
- `ResponsesModel` → `"responses"`

影响面分析（均无需修改）：
- `DuoAgentBuilder`：`registerAdapter(model.getApiFormat(), ...)` 同值注册同值路由，自洽
- `AgentOptions.provider`：与 apiFormat 同源，每个 Agent 的 LlmRuntime 独立注册
- `GenerateOptions.provider`：Model 直连适配器，不走 runtime 路由，仅契约字段

---

## 使用示例

```java
// 智谱 GLM（Anthropic 兼容端点，ZCode 同款接法）
DuoModel glm = AnthropicModel.builder()
        .baseUrl("https://open.bigmodel.cn/api/anthropic")
        .apiKey(System.getenv("ZHIPU_API_KEY"))
        .model("glm-4.6")
        .contextWindow(200000)
        .enableReasoning(true)
        .build();
var agent = DuoAgent.builder().model(glm).withCodeTools().build();

// Ollama 本地部署（无鉴权）
DuoModel local = ChatCompletionsModel.builder()
        .baseUrl("http://localhost:11434/v1")
        .model("qwen3:32b")
        .build();

// OpenAI 官方（Responses 格式，新模型能力）
DuoModel gpt = ResponsesModel.builder()
        .apiKey(System.getenv("OPENAI_API_KEY"))
        .model("gpt-5.2")
        .enableReasoning(true)
        .reasoningEffort("high")
        .build();

// DeepSeek（0.2.0 写法完全不变）
DuoModel ds = DeepSeekModel.builder()
        .model("deepseek-chat")
        .build();
```

---

## 约束与权衡

### 零依赖红线

✅ 全部基于 JDK HttpClient + 自研 JsonParser/JsonWriter，不引入任何第三方库。

### 超时分层红线

✅ 两个新 Model 均经 `AbstractDuoModel`，`createAdapter(Duration)` 的红线校验
（httpTimeout > appTimeout）自动生效。

### 0.2.0 兼容性

✅ `DeepSeekModel`/`DeepSeekAdapter` 公开 API 与行为零变化；`DuoAgent`/`DuoAgentBuilder`
不修改一行。新增类均为增量。

### 事件溯源不变量

✅ 协议差异终结在 Adapter 的 SseParser 层，产出的 StreamChunk 与现有完全一致，
Session/BlockAssembler/事件日志不受影响。

### 测试策略

- **协议级测试**（新增重点）：JDK 内置 `com.sun.net.httpserver.HttpServer` 起 mock 端点
  - Chat Completions：正常 SSE 流（chunk 序列断言）、非 200 → `LlmException.status()`、
    reasoning 字段有无差异、无 apiKey 时不发鉴权头
  - Anthropic：9 种事件序列、tool_use 组装、`{type:"error"}` 错误体、
    max_tokens 兜底、thinking_delta → ReasoningDelta
  - Responses：`response.*` 事件序列、messages→input items 转换、工具定义嵌套→平铺、
    `response.failed`/`response.incomplete` 映射
- **Builder 校验测试**：两个新 Model 的必填/范围校验
- **回归**：现有 240 个测试全绿（DeepSeek 行为零变化的证明）

---

## 实施阶段

1. **泛化 Chat Completions 协议层**：adapter/openai 四件套（自 DeepSeek 四件套泛化，
   reasoning 字段参数化），DeepSeek 切换，行为零变化
2. **ChatCompletionsModel**：通用 Builder + apiKey 可选
3. **Anthropic 协议四件套 + AnthropicModel**
4. **Responses 协议四件套 + ResponsesModel**（含 messages→input items、工具定义
   嵌套→平铺的请求转换）
5. **协议级测试补齐**（三种协议各一套）
6. **文档**：协议格式 × 厂商对照表（README + quick-start）

## Java 实现规范要求（阿里巴巴 Java 开发手册）

- **常量收口（C-01）**：本文档出现的默认值（`"2023-06-01"`、`8192`、`10240`、
  `"reasoning_content"`、端点路径）实现时一律定义为有名常量
  （如 `DEFAULT_ANTHROPIC_VERSION`、`DEFAULT_MAX_TOKENS`），禁止字面量直写
- **协议标识常量化（C-01/C-05）**：`"openai"` / `"anthropic"` 协议标识沿用现有
  `DeepSeekModel.API_FORMAT` 惯例在各 Model 内定义为 `public static final String`，
  不以 enum 替代（`getApiFormat(): String` 是 0.2.0 公开契约）
- **类声明（O-16）**：两个新 Model 与 Adapter 均 `final class`（与 `DeepSeekModel` 一致），
  构造器 private，仅经 Builder 创建
- **Javadoc（COM-01~03）**：公共类/方法完整 Javadoc 含 `@author`/`@date`/线程安全声明；
  apiKey 等敏感配置不做日志输出（与现有 DeepSeekAdapter 约定一致）

## 需查证项（实现时确认，不猜 API）

1. 智谱 Anthropic 兼容端点的确切 URL 与鉴权方式（`x-api-key` 与 `Authorization: Bearer`
   是否均接受）
2. 智谱 GLM 流式 thinking 字段与 Anthropic `thinking_delta` 的兼容性
3. Ollama 的 OpenAI 兼容路径前缀归属（baseUrl 带 `/v1` 还是固定拼接）
4. Anthropic 官方当前推荐的 `anthropic-version` 值
5. Responses API 的确切 SSE 事件集与字段名（以 OpenAI 官方文档为准，实现时逐事件核对；
   本文档映射表基于当前认知，以官方文档为最终依据）
6. `reasoning_effort` 的合法取值集（minimal/low/medium/high 是否随模型代次变化）

---

## 决策日志

| 问题 | 决策 | 理由 |
|------|------|------|
| 按厂商还是按协议分类？ | **按协议** | 厂商是配置值、协议才是类型；ZCode 三分类为业界验证过的抽象 |
| Responses 格式做不做？ | **做（0.3.0 全量三协议）** | OpenAI 新模型能力只在 Responses 提供、Chat Completions 已进入维护态；三协议经 `newAdapter` 钩子完全隔离，并行实施风险可控 |
| 厂商预设薄壳（ZhipuModel 等）建不建？ | **不建** | `ChatCompletionsModel`/`AnthropicModel` + 配置值即可覆盖，避免类爆炸；DeepSeekModel 因 0.2.0 兼容保留 |
| 协议内差异用什么抽象？ | **构造参数，不建 Spec 类型** | 差异仅 2 个参数级别，最小抽象，第三变体出现再抽 |
| apiKey 设为可选？ | **可选，null 不发鉴权头** | Ollama/vLLM 无鉴权是真实场景；云端忘配由服务端 401 明确报错 |
| Anthropic max_tokens 必填怎么处理？ | **Adapter 层兜底默认 8192** | 协议必填是实现细节，Builder 保持可选语义 |
| DeepSeekAdapter 删除还是保留？ | **保留委托薄壳** | 0.2.0 公开类，增量版本不 breaking |
| getApiFormat 返回值？ | **协议标识（"openai"/"anthropic"）** | 方法本名即「API 格式」，同值注册路由自洽，无下游修改 |
| Anthropic thinking 预算参数？ | **Builder 加 thinkingBudgetTokens（默认 10240）** | token 预算与 reasoningTimeout（时间）是两个维度 |

---

## 参考资料

- [ADR 002: DuoModel 架构设计](./ADR_002_DUOMODEL_ARCHITECTURE.md) - 两层 API 与工厂方法红线
- ZCode 模型供应商配置界面 - 协议三分类的抽象参照
- deepseek-harness `packages/llm/` - 多适配器 seam 设计参照
