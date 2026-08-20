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
    <version>0.1.0</version>
</dependency>
```

**Gradle：**

```gradle
implementation 'dev.duo:duo-agent-sdk:0.1.0'
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

public class HelloWorld {
    public static void main(String[] args) {
        var agent = DuoAgent.builder()
                .apiFormat("openai")                        // API 格式
                .baseUrl("https://api.deepseek.com")        // 服务地址
                .apiKey(System.getenv("DEEPSEEK_API_KEY"))  // 密钥
                .model("deepseek-chat")                     // 模型
                .contextWindow(128000)                      // 上下文窗口
                .withSearchTools()                          // 启用搜索工具（grep + glob）
                .build();

        String response = agent.chat("当前目录有哪些 Java 文件？");
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
    @Override public void onNext(String chunk) {
        System.out.print(chunk);   // 文字实时逐段到达
    }
    @Override public void onError(Throwable t) { t.printStackTrace(); }
    @Override public void onComplete() { System.out.println("\n— 完成 —"); }
});
```

## 配置速查

| 配置 | 必填 | 默认 | 说明 |
|------|------|------|------|
| `apiFormat("openai")` | ✅ | — | 目前仅支持 OpenAI 兼容格式 |
| `baseUrl(...)` | ✅ | — | 如 DeepSeek、Ollama `http://localhost:11434/v1` |
| `apiKey(...)` | ✅ | — | API 密钥 |
| `model(...)` | ✅ | — | 如 `deepseek-chat`、`deepseek-reasoner` |
| `contextWindow(...)` | 可选 | — | 上下文窗口 token 数 |
| `maxOutputTokens(...)` | 可选 | 不限制 | 输出上限（推理模型建议不设） |
| `enableReasoning(true)` | 可选 | false | 启用推理模式 |
| `reasoningTimeout(...)` | 可选 | 5 分钟 | 推理模式超时 |
| `timeout(...)` | 可选 | 60 秒 | 普通调用超时 |
| `systemPrompt(...)` | 可选 | 通用助手 | 系统提示词 |

## 常见问题

**IDEA 里运行报"未设置 DEEPSEEK_API_KEY"？**
GUI 应用不继承 shell 的 `export`。在 Run Configuration → Environment variables 中添加。

**支持哪些模型？**
一切 OpenAI 兼容 Chat Completions API：DeepSeek（`deepseek-chat` / `deepseek-reasoner`）、Ollama 本地模型、vLLM 自部署等。

## 下一步

→ [对话 API](../02-guide/chat-api.md)：四种对话模式详解
→ [内置工具](../02-guide/tools-builtin.md)：工具能力全表
