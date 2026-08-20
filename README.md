# Duo Agent

**零依赖的 Java AI Agent SDK**，让你用几行代码就能创建强大的 AI Agent。

## ✨ 特性

- 🚀 **极简 API** - 两层抽象：Model 管模型配置，Agent 管会话与工具
- 🔧 **内置工具链** - bash、文件操作、代码编辑、搜索等
- 🔌 **零依赖** - 纯 Java 21，无第三方依赖
- 🧪 **高质量** - 240 个单元测试，100% 工具链稳定性
- 🎯 **ReAct 架构** - 成熟的推理-行动循环模式
- 🧠 **推理模式** - 支持 DeepSeek-R1 等深度推理模型

## 🚀 快速开始（5 分钟）

### 1. 添加依赖

**Maven:**
```xml
<dependency>
    <groupId>dev.duo</groupId>
    <artifactId>duo-agent-sdk</artifactId>
    <version>0.2.0</version>
</dependency>
```

**Gradle:**
```gradle
implementation 'dev.duo:duo-agent-sdk:0.2.0'
```

### 2. 设置 API Key

```bash
export DEEPSEEK_API_KEY=your_api_key
```

### 3. 编写第一个 Agent

```java
import dev.duo.api.DuoAgent;
import dev.duo.api.DuoModel;
import dev.duo.model.deepseek.DeepSeekModel;

public class HelloWorld {
    public static void main(String[] args) {
        // 第一步：模型配置（同一 Model 可复用给多个 Agent）
        DuoModel model = DeepSeekModel.builder()
                .apiKey(System.getenv("DEEPSEEK_API_KEY"))  // 缺省回落环境变量
                .model("deepseek-chat")
                .contextWindow(128000)
                .build();

        // 第二步：Agent 组装（只管会话与工具，不重复填模型配置）
        var agent = DuoAgent.builder()
                .model(model)
                .withFileTools()
                .build();

        String response = agent.call("当前目录有哪些 Java 文件？");
        System.out.println(response);
    }
}
```

**就这么简单！** Agent 会自动调用工具完成任务。

## 📖 更多示例

### 代码助手 Agent

```java
var agent = DuoAgent.builder()
        .model(model)  // 复用同一个 model 实例
        .withCodeTools()  // bash + file + grep + edit
        .systemPrompt("你是专业的 Java 代码助手")  // 覆盖 model 的 systemPrompt
        .build();

agent.call("帮我重构 UserService.java 中的重复代码");
```

### 推理模型 Agent（DeepSeek-R1）

推理配置属于模型本身，全部在 `DeepSeekModel` 上设置：

```java
DuoModel model = DeepSeekModel.builder()
        .apiKey(System.getenv("DEEPSEEK_API_KEY"))
        .model("deepseek-reasoner")
        .contextWindow(64000)
        .enableReasoning(true)      // 启用深度推理（超时自动切换为推理超时，默认 5 分钟）
        // .reasoningTimeout(Duration.ofMinutes(8))  // 可选：自定义推理超时
        .build();

var agent = DuoAgent.builder()
        .model(model)
        .withCodeTools()
        .build();
```

> **注意：** 推理模型建议不要设置 `maxOutputTokens`，由模型决定输出长度，
> 避免 `<think>` 推理过程占用 token 导致回答被截断。

### 完整配置

```java
DuoModel model = DeepSeekModel.builder()
        .apiKey("your-api-key")                     // 必填：API 密钥（回落 DEEPSEEK_API_KEY）
        .baseUrl("https://api.deepseek.com")        // 可选：默认官方端点
        .model("deepseek-chat")                     // 必填：模型名称
        .contextWindow(128000)                      // 可选：上下文窗口
        .maxOutputTokens(8000)                      // 可选：输出限制（默认不限制）
        .temperature(0.7)                           // 可选：采样温度 [0, 2]
        .enableReasoning(false)                     // 可选：推理模式（默认关闭）
        .reasoningTimeout(Duration.ofMinutes(5))    // 可选：推理超时（默认 5 分钟）
        .systemPrompt("你是专业的模型助手")           // 可选：模型级系统提示词
        .build();

var agent = DuoAgent.builder()
        .model(model)                               // 必填：模型实例
        .systemPrompt("你是专业的代码审查助手")       // 可选：覆盖 model 的提示词
        .timeout(Duration.ofSeconds(120))           // 可选：LLM 超时（默认 60 秒）
        .withCodeTools()                            // 代码工具集
        .tool(new CustomTool().getDefinition())     // 自定义工具
        .build();
```

**systemPrompt 优先级**：Agent 显式设置 > Model 设置 > 内置默认。
**超时分层**：Agent 实际超时 = max(应用层 timeout, Model 的 reasoningTimeout)，
底层 HTTP 超时在此基础上再加 1 分钟余量，保证应用层先于网络层超时。

> **线程安全提示：** 同一 DuoAgent 实例共享底层 Session，不是线程安全的。
> 如需并发处理多个请求，请为每个请求创建独立的 Agent 实例
> （Model 无状态线程安全，可以共享）。

### 流式对话（响应式流）

`stream()` 返回 JDK 原生 `Flow.Publisher<SessionEvent>`（Reactive Streams 规范），
零第三方依赖。事件流完整透传 Agent 工作过程：思考增量、文本增量、
工具调用与结果、step/turn 边界——只要文本增量时按类型过滤即可：

```java
agent.stream("写一个排序算法").subscribe(new Flow.Subscriber<>() {
    private Flow.Subscription subscription;

    @Override
    public void onSubscribe(Flow.Subscription s) {
        subscription = s;
        s.request(Long.MAX_VALUE);  // 或按需分批 request(n)
    }

    @Override
    public void onNext(SessionEvent event) {
        // 只要文本增量：过滤 assistant/chunk 中的 TextDelta
        if (event instanceof SessionEventAssistantChunk c
                && c.chunk() instanceof StreamChunk.TextDelta d) {
            System.out.print(d.text());  // 文本增量实时打印
        }
    }

    @Override
    public void onError(Throwable t) { t.printStackTrace(); }

    @Override
    public void onComplete() { /* 对话轮结束 */ }
});
```

Spring WebFlux / RxJava 用户一行桥接：

```java
Flux<SessionEvent> flux = Flux.from(agent.stream("写一个排序算法"));   // Reactor
Flowable<SessionEvent> flowable = Flowable.fromPublisher(agent.stream(...)); // RxJava
```

行为说明：
- **冷发布者** — 订阅时才发起对话，未订阅不消耗 API 调用
- **全量事件** — 思考（ReasoningDelta）、文本（TextDelta）、工具调用、
  边界事件全部推送，订阅者按需过滤
- **多轮工具调用** — Agent 使用工具后会再次调用模型，订阅者收到多段连续文本流
- **背压** — 通过 `request(n)` 控制拉取节奏，消费慢时增量内部缓冲不丢失
- **取消** — `cancel()` 停止推送并释放资源，但底层对话轮继续执行完毕
- **单订阅** — 每次调用返回的 Publisher 仅支持订阅一次

### 事件类型速查

单 turn 事件时序：

```
turn/start
└─ step/start
   user/message                       本 step 进入的输入
   assistant/chunk × N                流式增量（文本/思考/工具参数）
   assistant/message                  组装完成的完整消息（usage 随行，
                                       sourceEventSeqs 回链 chunk seq）
   (tool/call → tool/result) × K      逐对交错：call₁ → result₁ → call₂ → …
└─ step/end                           finally 必发
turn/end                              finally 必发，携带结束原因
```

事件类型速查（按用途分组）：

| 分组 | 事件 | 说明 |
|------|------|------|
| 边界 | `turn/start` `turn/end` `step/start` `step/end` | turn 结束即完成信号，`TurnEndReason` 6 种：completed/aborted/blocked/error/max-tokens/interrupted |
| 流式增量 | `assistant/chunk` | 7 种 StreamChunk：文本/思考/工具参数增量、块边界、usage、finish |
| 表面消息 | `user/message` `assistant/message` `tool/result` | 决定模型可见上下文；assistant/message 携带 usage 和 sourceEventSeqs 回链 |
| 工具 | `tool/call` | callId 与 tool/result 配对 |
| 簿记 | `compaction/*` `session/end-seed` | 压缩事务、持久化边界 |

> `todo/write`、`request/header`、`request/context` 三类事件已定义但当前主流程无产生点，出现时按需处理。

完整事件流演示见 `EventsExample`。

### 在 Spring Boot 中桥接 SSE（前端流式对话）

duo-agent 是**纯 Java SDK，不绑定任何框架**——`stream()` 返回的 `Flow.Publisher` 是
Reactive Streams 中立标准，Spring Boot 应用直接在自己的代码里桥接即可，
duo-agent 侧无需任何额外依赖。以下示例可整体拷贝到你的 Spring 项目。

**关键前提：每请求一个 Agent 实例。** DuoAgent 非线程安全且 Session 有状态，
不要注入单例 Agent 复用；Model 无状态可全局共享，
`DuoAgent.builder()` 构建成本极低，每个请求新建。

#### Spring MVC（SseEmitter 方式）

```java
@RestController
public class ChatController {

    private final DuoModel model = DeepSeekModel.builder()  // Model 全局共享
            .apiKey(System.getenv("DEEPSEEK_API_KEY"))
            .model("deepseek-chat")
            .contextWindow(128000)
            .build();

    @PostMapping(value = "/api/chat/stream")
    public SseEmitter chat(@RequestBody ChatRequest request) {
        SseEmitter emitter = new SseEmitter(0L);  // 不超时
        var agent = DuoAgent.builder()            // 每请求新建 Agent
                .model(model)
                .withCodeTools()
                .build();

        agent.stream(request.message()).subscribe(new Flow.Subscriber<>() {
            private Flow.Subscription subscription;

            @Override
            public void onSubscribe(Flow.Subscription s) {
                subscription = s;
                s.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(SessionEvent event) {
                try {
                    // 只要文本增量；需要渲染工作过程时可透传全部事件类型
                    if (event instanceof SessionEventAssistantChunk c
                            && c.chunk() instanceof StreamChunk.TextDelta d) {
                        emitter.send(SseEmitter.event().name("delta").data(d.text()));
                    }
                } catch (IOException e) {
                    subscription.cancel();  // 客户端断开，停止推送
                }
            }

            @Override
            public void onError(Throwable t) {
                try {
                    emitter.send(SseEmitter.event().name("error").data(t.getMessage()));
                } catch (IOException ignored) {
                    // 客户端已断开
                }
                emitter.complete();
            }

            @Override
            public void onComplete() {
                try {
                    emitter.send(SseEmitter.event().name("done"));
                } catch (IOException ignored) {
                    // 客户端已断开
                }
                emitter.complete();
            }
        });
        return emitter;
    }
}
```

#### 多事件类型变体（完整工作过程 → SSE）

前端要渲染完整工作过程（工具调用、思考过程）时，onNext 不过滤、
按事件类型转发即可，SSE 的 `event:` 字段直接用事件类型，
前端按事件名分别渲染：

```java
@Override
public void onNext(SessionEvent event) {
    try {
        // event: 字段 = 事件类型（turn/start、assistant/chunk、tool/call、tool/result、turn/end…）
        emitter.send(SseEmitter.event().name(event.type()).data(describe(event)));
    } catch (IOException e) {
        subscription.cancel();
    }
}
```

前端即可按 `tool_call` / `tool_result` / `turn_end` 等事件名分栏渲染 Agent 工作过程。

#### Spring WebFlux（Flux 方式）

```java
@RestController
public class ChatController {

    @PostMapping(value = "/api/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> chat(@RequestBody ChatRequest request) {
        var agent = DuoAgent.builder().model(model).build();
        return Flux.from(agent.stream(request.message()))
                .filter(e -> e instanceof SessionEventAssistantChunk c
                        && c.chunk() instanceof StreamChunk.TextDelta)
                .map(e -> {
                    var d = (StreamChunk.TextDelta) ((SessionEventAssistantChunk) e).chunk();
                    return ServerSentEvent.<String>builder(d.text()).event("delta").build();
                })
                .concatWith(Flux.just(ServerSentEvent.<String>builder("").event("done").build()))
                .onErrorResume(e -> Flux.just(
                        ServerSentEvent.<String>builder(e.getMessage()).event("error").build()));
    }
}
```

背压在 WebFlux 下是端到端打通的：前端消费慢 → Netty 缓冲堆积 → Flux 自动减少
`request(n)` → SDK 内部缓冲兜住 → 不丢数据、不爆内存。

#### 前端接入（fetch 流式读取，支持 POST）

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

> 简单 GET 场景也可用浏览器原生 `new EventSource(url)`，但它不支持 POST，
> 消息体较大或涉及鉴权时推荐上面的 fetch 方案。

#### 实践建议

- **完成/错误信号约定** — 用 `event: done` / `event: error` 哨兵帧让前端明确区分
  正常结束与失败，不要只断连接
- **心跳保活** — 推理模型思考期可能数十秒无输出，中间有 Nginx/网关时需定期发送
  SSE 注释行（`: ping`）防止空闲连接被掐断
- **断线语义** — 当前流是一次性对话，断线即本轮作废，前端重新发起即可

### 单次推理（不经 Agent）

Model 本身就是无状态的单次推理接口，不需要会话/工具时直接用：

```java
String answer = model.call("解释什么是事件溯源");        // 同步阻塞

model.stream("写一首诗").subscribe(...)                  // Flow.Publisher<StreamChunk>
```

Agent 的 `stream()` 推送 `SessionEvent`（会话语义），
Model 的 `stream()` 推送 `StreamChunk`（单次响应语义），按场景选层。

### 访问底层 API（高级用户）

```java
Agent rawAgent = agent.getAgent();
Session session = agent.getSession();
// 使用底层 API 进行高级控制
```

## 🛠️ 工具预设

| 预设方法 | 包含工具 | 适用场景 |
|---------|---------|---------|
| `.withFileTools()` | file_read, file_write | 文件操作 |
| `.withSearchTools()` | grep, glob | 内容搜索、文件查找 |
| `.withEditTools()` | edit | 代码编辑 |
| `.withCodeTools()` | bash + 以上所有 | 代码相关任务（推荐） |
| `.withAllBuiltinTools()` | 全部内置工具（不含 skill） | 全能助手 |

工具名称冲突时采用 last-wins 语义：后添加的工具定义覆盖先添加的。
也可以手动添加工具：
```java
.tool(new CustomTool().getDefinition())
```

## 📦 项目结构

```
duo-agent/
├── duo-agent-sdk/          # SDK 核心模块
│   ├── src/main/java/      # SDK 源码
│   └── src/test/java/      # SDK 单元测试（240 个测试）
├── duo-agent-example/      # 示例/调试模块
│   └── src/main/java/      # 使用示例
└── pom.xml                 # 父 POM
```

## 🧪 运行示例

### 克隆项目

```bash
git clone <repository-url>
cd duo-agent
```

### 构建项目

```bash
mvn clean install
```

### 设置 API Key

方式一：项目根目录创建 `.env` 文件（示例模块自动读取）：

```bash
cp .env.example .env
# 编辑 .env 填入 DEEPSEEK_API_KEY=sk-xxx
```

方式二：环境变量：

```bash
export DEEPSEEK_API_KEY=your_api_key
```

### 运行示例

```bash
# Builder API 示例（推荐，最简单）
mvn exec:java -pl duo-agent-example -Dexec.mainClass="com.example.HelloWorldExample"

# 流式输出示例
mvn exec:java -pl duo-agent-example -Dexec.mainClass="com.example.StreamingChatExample"

# 事件流示例（完整工作过程）
mvn exec:java -pl duo-agent-example -Dexec.mainClass="com.example.EventsExample"

# 推理模型示例（deepseek-reasoner）
mvn exec:java -pl duo-agent-example -Dexec.mainClass="com.example.ReasonerExample"
```

## 🏗️ 架构设计

### 核心模块

**duo-agent-sdk** - 核心 SDK 模块，提供：
- 🤖 **Model 抽象层** - `DuoModel` 单次推理接口（call/stream），厂商实现可插拔
- 🤖 **Agent 框架** - ReAct 模式实现（Reasoning + Acting 循环），会话/工具/Hooks
- 🛠️ **工具系统** - 内置 bash、文件操作、grep、glob、edit 等工具
- 📝 **会话管理** - 事件溯源的对话历史管理
- 🎯 **Builder API** - 两层构建：DeepSeekModel + DuoAgent

**duo-agent-example** - 示例项目：
- `HelloWorldExample.java` - Builder API 快速开始（推荐）
- `StreamingChatExample.java` - stream() 流式文本输出演示
- `EventsExample.java` - stream() 完整事件流演示（工作过程可见）
- `ReasonerExample.java` - 推理模型（deepseek-reasoner）验证
- `QuickStartExample.java` - 底层 API 演示（手动组装组件）
- `BasicAgentExample.java` - 基础示例
- `ToolCallingExample.java` - 工具调用演示
- `DeepSeekToolsDemo.java` - 工具链稳定性测试
- `EnvLoader.java` - .env 文件加载工具（示例自动读取 API Key）

### 两层 API（0.2.0）

**高层 API（推荐）：**
```java
DuoModel model = DeepSeekModel.builder()
    .apiKey(System.getenv("DEEPSEEK_API_KEY"))
    .model("deepseek-chat")
    .build();

var agent = DuoAgent.builder()
    .model(model)
    .withFileTools()
    .build();
String response = agent.call("任务描述");
```

**底层 API（高级用户）：**
```java
// 58 行样板代码
var llmRuntime = new LlmRuntime();
llmRuntime.registerAdapter("deepseek", new DeepSeekAdapter());
var toolRegistry = new ToolRegistryImpl();
toolRegistry.register(new BashTool().getDefinition());
// ... 50+ 行配置代码
var agent = new ReactLoopAgent(...);
```

**代码量减少 80%+！**

## 🧑‍💻 开发指南

### 在 IDE 中导入

**IntelliJ IDEA:**
1. File → Open → 选择 `duo-agent/pom.xml`
2. 选择 "Open as Project"
3. Maven 自动导入所有模块

**Eclipse:**
1. File → Import → Maven → Existing Maven Projects
2. 选择 `duo-agent` 目录
3. 勾选所有模块导入

### 运行单元测试

```bash
# 运行全部测试（240 个）
mvn test

# 运行 SDK 测试
mvn test -pl duo-agent-sdk

# 运行 Builder 测试
mvn test -Dtest=DuoAgentBuilderTest
```

### 自定义工具

实现 `ToolDefinition` 接口：

```java
public class MyTool implements ToolProvider {
    @Override
    public ToolDefinition getDefinition() {
        return new ToolDefinition(
            "my_tool",
            "工具描述",
            Map.of("param", Map.of("type", "string")),
            this::execute
        );
    }

    private ToolExecutionResult execute(Map<String, Object> args) {
        // 实现工具逻辑
        return new ToolExecutionResult("执行结果");
    }
}

// 使用
var agent = DuoAgent.builder()
    .model(model)
    .tool(new MyTool().getDefinition())
    .build();
```

## ⚠️ 已知限制

- **Anthropic 格式未支持** - 目前仅提供 DeepSeek（OpenAI 兼容格式）的
  `DuoModel` 实现，Anthropic 格式计划在未来版本支持

## 📊 质量保证

- ✅ **240 个单元测试** - 覆盖核心功能（含流式 API 测试）
- ✅ **四轮 AI 代码审查** - 45 个问题全部修复
- ✅ **100% 工具链稳定性** - 经过 DeepSeek API 实测验证
- ✅ **零依赖** - 纯 Java 21，无第三方库
- ✅ **阿里巴巴 Java 规范** - 全量修复高危违规项

## ⚙️ 系统要求

- **Java 21+** （使用了 Record、Pattern Matching 等特性）
- **Maven 3.8+**
- **操作系统** - Windows / macOS / Linux

## 📄 许可证

Apache License 2.0

## 📚 更多文档

- **[完整文档站](docs/index.md)** - 入门 / 指南 / 高级 / 架构 / 参考，19 篇成体系文档
- [SDK 设计文档](SDK_DESIGN.md) - 架构设计与实现细节
- [SDK API 文档](duo-agent-sdk/README.md) - 底层 API 参考
- [开发交接文档](HANDOFF.md) - 开发历史与决策记录

## 🤝 贡献

欢迎提交 Issue 和 Pull Request！

## 📮 联系方式

如有问题或建议，请提交 Issue。
