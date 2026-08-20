# 快速开始

5 分钟内完成从添加依赖到第一次对话。

## 1. 环境要求

- **Java 21+**（使用了 Record、Pattern Matching、虚拟线程等特性）
- **Maven 3.8+**（或对应的 Gradle）
- 一个 DeepSeek API Key（[获取地址](https://platform.deepseek.com)）

## 2. 添加依赖

**Maven：**

```xml
<dependency>
    <groupId>dev.duo</groupId>
    <artifactId>duo-agent-sdk</artifactId>
    <version>0.2.0</version>
</dependency>
```

**Gradle：**

```gradle
implementation 'dev.duo:duo-agent-sdk:0.2.0'
```

> 💡 duo-agent 零第三方依赖（仅 SLF4J API），不想用 Logback 可以自由替换日志实现。

## 3. 设置 API Key

```bash
export DEEPSEEK_API_KEY=your_api_key
```

IDEA 中运行可在 *Run Configuration → Environment variables* 里设置（GUI 应用不读取 shell 环境变量）。

## 4. 第一个 Agent

```java
import dev.duo.api.DuoAgent;
import dev.duo.api.DuoModel;
import dev.duo.model.deepseek.DeepSeekModel;

public class HelloWorld {
    public static void main(String[] args) {
        // 第一步：模型配置（同一 Model 可复用给多个 Agent）
        DuoModel model = DeepSeekModel.builder()
                .apiKey(System.getenv("DEEPSEEK_API_KEY"))  // 可省略，回落环境变量
                .model("deepseek-chat")                     // 模型
                .contextWindow(128000)                      // 上下文窗口
                .build();

        // 第二步：Agent 组装（只管会话与工具）
        var agent = DuoAgent.builder()
                .model(model)                               // 必填：模型实例
                .withSearchTools()                          // 启用搜索工具（grep + glob）
                .build();

        String response = agent.call("当前目录有哪些 Java 文件？");
        System.out.println(response);
    }
}
```

运行后模型会自主调用 `glob` 工具查询目录，然后组织语言回答——**工具调用完全自动**，你只需要提问。

## 5. 换成流式输出

```java
agent.stream("写一个快速排序").subscribe(new Flow.Subscriber<>() {
    private Flow.Subscription subscription;

    @Override public void onSubscribe(Flow.Subscription s) {
        subscription = s;
        s.request(Long.MAX_VALUE);
    }
    @Override public void onNext(SessionEvent event) {
        // 事件流全量透传（思考、文本、工具调用），只要文本增量时按类型过滤
        if (event instanceof SessionEventAssistantChunk c
                && c.chunk() instanceof StreamChunk.TextDelta d) {
            System.out.print(d.text());   // 文字实时逐段到达
        }
    }
    @Override public void onError(Throwable t) { t.printStackTrace(); }
    @Override public void onComplete() { System.out.println("\n— 完成 —"); }
});
```

## 配置速查

两层 API：模型配置在 `DeepSeekModel.builder()` 上，会话与工具配置在 `DuoAgent.builder()` 上。

**模型配置（`DeepSeekModel.builder()`）：**

| 配置 | 必填 | 默认 | 说明 |
|------|------|------|------|
| `model(...)` | ✅ | — | 如 `deepseek-chat`、`deepseek-reasoner` |
| `apiKey(...)` | 可选 | 环境变量 `DEEPSEEK_API_KEY` | API 密钥 |
| `baseUrl(...)` | 可选 | DeepSeek 官方端点 | 接其他 OpenAI 兼容服务时设置，如 Ollama `http://localhost:11434/v1` |
| `contextWindow(...)` | 可选 | — | 上下文窗口 token 数 |
| `maxOutputTokens(...)` | 可选 | 不限制 | 输出上限（推理模型建议不设） |
| `enableReasoning(true)` | 可选 | false | 启用推理模式 |
| `reasoningTimeout(...)` | 可选 | 5 分钟 | 推理模式超时 |
| `systemPrompt(...)` | 可选 | — | 模型级系统提示词 |

**Agent 配置（`DuoAgent.builder()`）：**

| 配置 | 必填 | 默认 | 说明 |
|------|------|------|------|
| `model(duoModel)` | ✅ | — | `DuoModel` 实例 |
| `systemPrompt(...)` | 可选 | 通用助手 | 系统提示词（优先级：Agent > Model > 内置默认） |
| `timeout(...)` | 可选 | 60 秒 | 普通调用超时 |
| `withXxxTools()` / `tool(...)` | 可选 | — | 工具预设与自定义工具 |

> **超时分层**：Agent 实际超时 = max(应用层 `timeout`，Model 的 `reasoningTimeout`)，底层 HTTP 超时在此基础上再加 1 分钟余量，保证应用层先于网络层超时。

## 常见问题

**IDEA 里运行报"未设置 DEEPSEEK_API_KEY"？**
GUI 应用不继承 shell 的 `export`。在 Run Configuration → Environment variables 中添加。

**支持哪些模型？**
API 格式由 Model 实现决定——`DeepSeekModel` 即 OpenAI 兼容格式，通过 `baseUrl(...)` 可接一切 OpenAI 兼容 Chat Completions API：DeepSeek（`deepseek-chat` / `deepseek-reasoner`）、Ollama 本地模型、vLLM 自部署等。

## 下一步

→ [对话 API](../02-guide/chat-api.md)：两种对话模式详解
→ [内置工具](../02-guide/tools-builtin.md)：工具能力全表
