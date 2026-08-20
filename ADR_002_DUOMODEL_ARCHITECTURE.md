# ADR 002: DuoModel 架构设计

**状态**: 已批准  
**日期**: 2026-08-20  
**决策者**: zhangyl  
**背景会话**: 本次 grilling 会话

---

## 背景

当前 `DuoAgentBuilder` 每次构建都需要重复传入模型配置（apiFormat, baseUrl, apiKey, model, contextWindow 等），存在以下问题：

1. **配置重复** - 同一模型配置无法复用
2. **职责不清** - Agent 构建器承担了模型配置管理的职责
3. **缺少单次调用** - 无法在不创建 Agent 的情况下进行简单的 LLM 推理

## 决策

引入 `DuoModel` 抽象层，将模型配置与 Agent 会话管理分离。

---

## 核心设计

### 1. DuoModel 定位

**DuoModel 是底层能力单元**，负责：
- ✅ 单次 LLM 推理（无状态、无历史）
- ✅ 模型配置封装（apiKey, baseUrl, modelName, contextWindow 等）
- ✅ 为 Agent 提供 Adapter 工厂
- ❌ 不执行工具（只有 Agent 能执行工具）
- ❌ 不支持 Hook（Hook 是 Agent 的编排能力）
- ❌ 不管理 Session（无历史、无状态）
- ❌ 不产生 SessionEvent（只吐模型原生 StreamChunk）

**DuoAgent 是编排运行时**，负责：
- ✅ 多轮对话（Session 管理）
- ✅ ReAct 循环（工具调用、多步推理）
- ✅ Hook 扩展（PreStepHook, RequestErrorHook 等）
- ✅ 上下文压缩、持久化
- ✅ 产生 SessionEvent（完整的 Agent 事件流）

**核心区别**：
- **事件层次** - Model 吐 `StreamChunk`（LLM 原生协议），Agent 吐 `SessionEvent`（编排语义层）
- **状态管理** - Model 无状态，Agent 有 Session
- **工具执行** - Model 不执行工具，Agent 执行工具

Model 只回答「下一步说什么、该调哪个工具」；Agent 解决「如何把这一步融入多轮回合、如何真正执行、如何不丢上下文、如何安全协作、如何崩溃后恢复」。所有「超越单次生成」的能力都只在 Agent 层，Model 保持最小化、可替换。

**详细对比**：

| 维度 | DuoModel | DuoAgent |
|------|----------|----------|
| **推理** | 单步决策，一次调用产出一个回复 | 多步推理循环（思考→行动→观察），有迭代上限 |
| **状态** | 无状态，每次调用独立、跨回合遗忘 | 持有记忆/上下文缓冲，可累积与持久化 |
| **工具** | 不执行工具，只做纯模型推理 | 真正解析并执行工具、回填结果、进入下一轮 |
| **环境交互** | 无法直接作用于世界 | 通过工具集与环境交互 |
| **安全** | 无安全边界 | 权限审批、危险操作暂停等待人类批准 |
| **中断恢复** | 进程重启即失忆、无法续跑 | 支持断点续跑、会话持久化 |
| **事件流** | 返回 `StreamChunk`（LLM 协议层） | 返回 `SessionEvent`（Agent 语义层） |
| **API** | `call()` → String<br>`stream()` → `StreamChunk` | `call()` → String<br>`stream()` → `SessionEvent` |

---

### 2. 架构分层

```
┌─────────────────────────────────────────┐
│  DuoModel (interface)                   │  ← 顶层抽象
│  - call(String): String                 │  ← 阻塞调用返回文本
│  - stream(String): Publisher<StreamChunk> │  ← 返回模型原生 StreamChunk
│  - createAdapter(): LlmAdapter          │  ← 工厂方法（Model 自用）
│  - createAdapter(Duration): LlmAdapter  │  ← 工厂方法（Agent 组装用，超时由组装方传入）
│  - getApiFormat(), getModelName()...    │
└─────────────────────────────────────────┘
            ▲                 ▲
            │                 │
   ┌────────┴────────┐   ┌───┴───────┐
   │ DeepSeekModel   │   │ QwenModel │  ← 各厂家实现
   └─────────────────┘   └───────────┘
            ▲
            │ createAdapter()
            ▼
   ┌─────────────────┐
   │ DeepSeekAdapter │  ← 复用现有 Adapter
   └─────────────────┘
```

**与现有架构的关系**：
- `DuoModel` 提供两个工厂重载：`createAdapter()`（Model 自用，按自身超时配置）与
  `createAdapter(Duration httpTimeout)`（Agent 组装用，HTTP 兜底超时由组装方计算传入）
- `DuoAgent` 通过 `model.createAdapter(httpTimeout)` 获取 Adapter 并注册到 `LlmRuntime`，
  维持「HTTP 兜底 ≥ 应用层最大超时」红线——该约束只能在组装时刻由组装方保证
- 不暴露敏感配置（apiKey, baseUrl），保持封装性
- Model 层吐 `StreamChunk`（LLM 原生协议），Agent 层吐 `SessionEvent`（编排语义层）

---

### 3. API 设计

#### DuoModel 接口

```java
package dev.duo.api;

import dev.duo.api.llm.LlmAdapter;
import dev.duo.model.llm.StreamChunk;
import java.time.Duration;
import java.util.concurrent.Flow;

/**
 * 底层模型能力单元 - 无状态的单次 LLM 推理。
 * <p>
 * 职责：
 * <ul>
 *   <li>单次调用（阻塞/流式）</li>
 *   <li>模型配置封装</li>
 *   <li>为 Agent 提供 Adapter 工厂</li>
 * </ul>
 * <p>
 * <b>不支持</b>：工具执行、Hook、Session 管理、SessionEvent（这些是 Agent 的职责）
 * </p>
 * <p>
 * <b>层次定位</b>：Model 层只吐模型原生响应单元（{@link StreamChunk}），
 * 不产生 Agent 的 {@code SessionEvent}。
 * </p>
 * <p>
 * <b>线程安全</b>：实现实例线程安全，可被多线程共享。每次 call/stream
 * 创建独立的底层 Adapter，并发调用互不干扰。
 * </p>
 */
public interface DuoModel {
    
    /**
     * 同步阻塞调用 - 发送 prompt 并等待完整响应文本。
     * <p>
     * 无状态：每次调用独立，不保留历史。<br>
     * 无工具：纯模型推理，不执行工具。<br>
     * 无事件：不产生 SessionEvent，只返回最终文本。
     * </p>
     *
     * @param prompt 用户输入
     * @return 模型的完整响应文本
     */
    String call(String prompt);
    
    /**
     * 流式调用 - 返回模型原生响应单元流。
     * <p>
     * 返回的是 LLM 底层协议的 {@link StreamChunk}：
     * <ul>
     *   <li>{@link StreamChunk.TextDelta} - 文本增量</li>
     *   <li>{@link StreamChunk.ReasoningDelta} - 推理过程（如 DeepSeek-R1）</li>
     *   <li>{@link StreamChunk.ContentBlockStart} - 内容块开始</li>
     *   <li>{@link StreamChunk.ContentBlockEnd} - 内容块结束</li>
     * </ul>
     * <p>
     * <b>注意</b>：这不是 Agent 的 {@code SessionEvent}，而是模型原生的流式响应。
     * Agent 的事件流请使用 {@link DuoAgent#stream(String)}。
     * </p>
     * <p>
     * <b>行为契约</b>（与 {@link DuoAgent#stream(String)} 对齐）：
     * <ul>
     *   <li><b>冷发布者</b> - 调用本方法不发起请求，subscribe 时才开始调用</li>
     *   <li><b>背压</b> - 底层适配器为推式回调，{@code request(n)} 无法控制 chunk
     *       到达节奏；消费慢于生产时增量在内部缓冲（上限与 Agent 层一致），
     *       溢出以 {@code onError} 终止订阅</li>
     *   <li><b>单订阅</b> - 每次调用返回的 Publisher 仅支持订阅一次，
     *       重复订阅收到 onError</li>
     *   <li><b>取消</b> - {@code cancel()} 停止推送，底层单次调用继续执行完毕</li>
     * </ul>
     * </p>
     *
     * @param prompt 用户输入
     * @return 模型原生响应单元流（冷发布者，订阅时才发起调用）
     */
    Flow.Publisher<StreamChunk> stream(String prompt);
    
    /**
     * 创建 LlmAdapter（无参工厂，Model 自用）。
     * <p>
     * 供 {@link #call(String)} / {@link #stream(String)} 内部使用。
     * HTTP 兜底超时按 Model 自身配置计算（60s 或 reasoningTimeout + 1 分钟）。
     * </p>
     *
     * @return 新的 LlmAdapter 实例
     */
    LlmAdapter createAdapter();
    
    /**
     * 创建 LlmAdapter（带参工厂，Agent 组装用）。
     * <p>
     * <b>为什么需要这个重载</b>：Model 在 build 时不知道自己会被装进什么
     * 超时配置的 Agent，「HTTP 兜底超时 ≥ 应用层最大超时」这条红线只能在
     * 组装时刻由组装方（DuoAgentBuilder）计算应用层最大超时后显式传入，
     * 不能由 Model 单方面决定。否则 Agent 设置更大的 llmTimeout 时，
     * HTTP 层会先于应用层 barrier 掐断 SSE 流。
     * </p>
     *
     * @param httpTimeout 组装方计算的 HTTP 兜底超时（应用层最大超时 + 1 分钟余量）
     * @return 新的 LlmAdapter 实例
     */
    LlmAdapter createAdapter(Duration httpTimeout);
    
    /**
     * 获取 API 格式（如 "openai"）。
     */
    String getApiFormat();
    
    /**
     * 获取模型名称（如 "deepseek-chat"）。
     */
    String getModelName();
    
    /**
     * 获取系统提示词（可选，未设置时为 null）。
     * <p>
     * Agent 组装时的优先级：Agent 显式 systemPrompt &gt; 本值 &gt; 内置默认。
     * </p>
     */
    String getSystemPrompt();
    
    /**
     * 获取上下文窗口大小。
     */
    Integer getContextWindow();
    
    /**
     * 获取最大输出 token 数（可选）。
     */
    Integer getMaxOutputTokens();
    
    /**
     * 是否启用推理模式。
     */
    boolean isReasoningEnabled();
    
    /**
     * 获取推理超时时间。
     */
    java.time.Duration getReasoningTimeout();
}
```

#### DeepSeekModel 实现

```java
package dev.duo.model.deepseek;

import dev.duo.api.DuoModel;
import dev.duo.api.llm.LlmAdapter;
import dev.duo.api.llm.StreamCallback;
import dev.duo.adapter.deepseek.DeepSeekAdapter;
import dev.duo.model.llm.GenerateOptions;
import dev.duo.model.llm.Message;
import dev.duo.model.llm.MessageFactory;
import dev.duo.model.llm.StreamChunk;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicReference;

/**
 * DeepSeek 模型实现。
 */
public final class DeepSeekModel implements DuoModel {
    
    private final String apiKey;
    private final String baseUrl;
    private final String modelName;
    private final String systemPrompt;
    private final Integer contextWindow;
    private final Integer maxOutputTokens;
    private final Double temperature;
    private final boolean reasoningEnabled;
    private final Duration reasoningTimeout;
    private final Duration requestTimeout;
    
    // 私有构造器，通过 Builder 创建
    private DeepSeekModel(Builder builder) {
        this.apiKey = builder.apiKey;
        this.baseUrl = builder.baseUrl;
        this.modelName = builder.modelName;
        this.systemPrompt = builder.systemPrompt;
        this.contextWindow = builder.contextWindow;
        this.maxOutputTokens = builder.maxOutputTokens;
        this.temperature = builder.temperature;
        this.reasoningEnabled = builder.reasoningEnabled;
        this.reasoningTimeout = builder.reasoningTimeout;
        this.requestTimeout = calculateRequestTimeout();
    }
    
    public static Builder builder() {
        return new Builder();
    }
    
    @Override
    public String call(String prompt) {
        var adapter = createAdapter();
        
        // 1. 组装消息
        var message = MessageFactory.userMessage(prompt);
        var messages = List.of(message);
        
        // 2. 创建 GenerateOptions
        var options = new GenerateOptions(
            getApiFormat(),      // provider
            getModelName(),      // model
            messages,
            this.systemPrompt,   // system prompt
            null,                // tools（Model 不支持工具）
            this.temperature,
            this.maxOutputTokens,
            null,                // stop
            null,                // purpose
            this.reasoningEnabled
        );
        
        // 3. 同步调用并拼接结果
        var result = new StringBuilder();
        var latch = new CountDownLatch(1);
        var error = new AtomicReference<Throwable>();
        
        adapter.stream(options, new StreamCallback() {
            @Override
            public void onChunk(StreamChunk chunk) {
                if (chunk instanceof StreamChunk.TextDelta text) {
                    result.append(text.text());
                }
            }
            
            @Override
            public void onComplete() {
                latch.countDown();
            }
            
            @Override
            public void onError(Throwable t) {
                error.set(t);
                latch.countDown();
            }
        });
        
        // 4. 等待完成（带 HTTP 兜底超时，防适配器异常时永久挂死）
        try {
            if (!latch.await(requestTimeout)) {
                throw new RuntimeException("LLM 调用超时（HTTP 兜底 " + requestTimeout + "）");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("调用被中断", e);
        }
        
        if (error.get() != null) {
            throw new RuntimeException("LLM 调用失败", error.get());
        }
        
        return result.toString();
    }
    
    @Override
    public Flow.Publisher<StreamChunk> stream(String prompt) {
        return subscriber -> {
            var adapter = createAdapter();
            
            // 组装消息和请求
            var message = MessageFactory.userMessage(prompt);
            var messages = List.of(message);
            var options = new GenerateOptions(
                getApiFormat(),
                getModelName(),
                messages,
                this.systemPrompt,
                null,  // tools
                this.temperature,
                this.maxOutputTokens,
                null,  // stop
                null,  // purpose
                this.reasoningEnabled
            );
            
            // 执行流式调用
            adapter.stream(options, new StreamCallback() {
                @Override
                public void onChunk(StreamChunk chunk) {
                    subscriber.onNext(chunk);  // 透传 StreamChunk
                }
                
                @Override
                public void onComplete() {
                    subscriber.onComplete();
                }
                
                @Override
                public void onError(Throwable t) {
                    subscriber.onError(t);
                }
            });
        };
    }
    
    @Override
    public LlmAdapter createAdapter() {
        return new DeepSeekAdapter(apiKey, baseUrl, requestTimeout);
    }
    
    @Override
    public LlmAdapter createAdapter(Duration httpTimeout) {
        // Agent 组装路径：HTTP 兜底超时由组装方计算传入，
        // 维持「HTTP 兜底 ≥ 应用层最大超时」红线
        return new DeepSeekAdapter(apiKey, baseUrl, httpTimeout);
    }
    
    @Override
    public String getApiFormat() {
        return "openai";
    }
    
    @Override
    public String getModelName() {
        return modelName;
    }
    
    @Override
    public Integer getContextWindow() {
        return contextWindow;
    }
    
    @Override
    public Integer getMaxOutputTokens() {
        return maxOutputTokens;
    }
    
    @Override
    public boolean isReasoningEnabled() {
        return reasoningEnabled;
    }
    
    @Override
    public Duration getReasoningTimeout() {
        return reasoningTimeout;
    }
    
    private Duration calculateRequestTimeout() {
        var appTimeout = reasoningEnabled ? reasoningTimeout : Duration.ofSeconds(60);
        return appTimeout.plusMinutes(1);
    }
    
    /**
     * DeepSeek 模型构建器。
     */
    public static final class Builder {
        private String apiKey;
        private String baseUrl = "https://api.deepseek.com";
        private String modelName;
        private String systemPrompt;
        private Integer contextWindow;
        private Integer maxOutputTokens;
        private Double temperature;
        private boolean reasoningEnabled = false;
        private Duration reasoningTimeout = Duration.ofMinutes(5);
        
        public Builder apiKey(String apiKey) {
            this.apiKey = apiKey;
            return this;
        }
        
        public Builder baseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
            return this;
        }
        
        public Builder model(String modelName) {
            this.modelName = modelName;
            return this;
        }
        
        public Builder systemPrompt(String systemPrompt) {
            this.systemPrompt = systemPrompt;
            return this;
        }
        
        public Builder contextWindow(int tokens) {
            this.contextWindow = tokens;
            return this;
        }
        
        public Builder maxOutputTokens(int tokens) {
            this.maxOutputTokens = tokens;
            return this;
        }
        
        public Builder temperature(double temperature) {
            this.temperature = temperature;
            return this;
        }
        
        public Builder enableReasoning(boolean enable) {
            this.reasoningEnabled = enable;
            return this;
        }
        
        public Builder reasoningTimeout(Duration timeout) {
            this.reasoningTimeout = timeout;
            return this;
        }
        
        public DuoModel build() {
            validateConfig();
            return new DeepSeekModel(this);
        }
        
        private void validateConfig() {
            if (apiKey == null || apiKey.isBlank()) {
                throw new IllegalStateException("未设置 API Key");
            }
            if (modelName == null || modelName.isBlank()) {
                throw new IllegalStateException("未设置模型名称");
            }
        }
    }
}
```

#### DuoAgentBuilder 改造

```java
public final class DuoAgentBuilder {
    
    private DuoModel model;  // 新增：接收 Model 对象
    
    // 删除旧的 API（破坏性变更）
    // ❌ apiFormat(String)
    // ❌ apiKey(String)
    // ❌ baseUrl(String)
    // ❌ model(String)  ← 原来是字符串
    // ❌ contextWindow(int)
    // ❌ maxOutputTokens(int)
    // ❌ enableReasoning(boolean)
    // ❌ reasoningTimeout(Duration)
    
    /**
     * 设置模型（新 API）。
     */
    public DuoAgentBuilder model(DuoModel model) {
        this.model = Objects.requireNonNull(model, "model 不能为 null");
        return this;
    }
    
    // Agent 特有配置（保留）
    public DuoAgentBuilder timeout(Duration timeout);
    
    /**
     * 设置系统提示词（可选）。
     * <p>
     * <b>优先级：Agent 显式 systemPrompt &gt; Model systemPrompt &gt; 内置默认。</b>
     * 本 Builder 的默认值为 null（不再是内置默认文案），build() 时按上述
     * 优先级解析——避免内置默认静默覆盖 Model 精心设置的 prompt。
     * </p>
     */
    public DuoAgentBuilder systemPrompt(String prompt);
    
    public DuoAgentBuilder tool(ToolDefinition tool);
    public DuoAgentBuilder withCodeTools();
    // ...
    
    public DuoAgent build() {
        if (model == null) {
            throw new IllegalStateException("未设置 Model，请调用 .model(DuoModel)");
        }
        
        // 0. 解析 systemPrompt：Agent 显式 > Model > 内置默认
        var resolvedPrompt = this.systemPrompt != null
                ? this.systemPrompt
                : Objects.requireNonNullElse(model.getSystemPrompt(), DEFAULT_PROMPT);
        
        // 1. 计算应用层最大超时（Agent llmTimeout 与 Model reasoningTimeout 取较大者），
        //    经带参工厂交给 Model 创建 Adapter。「HTTP 兜底 ≥ 应用层最大超时」红线
        //    只能在组装时刻由组装方保证——Model build 时不知道 Agent 的超时配置
        var appTimeout = this.timeout.compareTo(model.getReasoningTimeout()) > 0
                ? this.timeout : model.getReasoningTimeout();
        var adapter = model.createAdapter(appTimeout.plusMinutes(1));
        
        // 2. 注册到 LlmRuntime
        var llmRuntime = new LlmRuntime();
        llmRuntime.registerAdapter(model.getApiFormat(), adapter);
        
        // 3. 创建 AgentOptions（使用 Model 的配置）
        var agentOptions = new AgentOptions(
            model.getApiFormat(),
            model.getApiFormat(),  // provider
            model.getModelName(),
            model.getContextWindow(),
            model.getMaxOutputTokens(),
            model.isReasoningEnabled(),
            model.getReasoningTimeout(),
            this.timeout,  // Agent 特有超时
            this.hooksBuilder.build()
        );
        
        // 4. 创建 Agent
        // ...
    }
}
```

#### DuoAgent 接口更新

```java
public interface DuoAgent {
    /**
     * 同步阻塞调用 - 等待完整响应文本。
     */
    String call(String message);
    
    /**
     * 流式调用 - 返回 SessionEvent 完整事件流。
     * <p>
     * 包含完整的 Agent 工作过程：
     * <ul>
     *   <li>turn/start, step/start - 对话轮和步骤边界</li>
     *   <li>user/message - 用户输入</li>
     *   <li>assistant/chunk - 助手响应增量（包装了底层 StreamChunk）</li>
     *   <li>assistant/message - 完整响应消息</li>
     *   <li>tool/call, tool/result - 工具调用与结果</li>
     *   <li>step/end, turn/end - 步骤和对话轮结束</li>
     * </ul>
     * </p>
     *
     * @param message 用户消息
     * @return SessionEvent 完整事件流（冷发布者，订阅时才发起对话）
     */
    Flow.Publisher<SessionEvent> stream(String message);
    
    /**
     * 获取底层 Agent 实例（高级用户）。
     */
    Agent getAgent();
    
    /**
     * 获取会话实例（高级用户）。
     */
    Session getSession();
}
```

---

### 4. 使用示例

#### 场景 1：Model 单次调用

```java
// 创建 Model
var model = DeepSeekModel.builder()
    .apiKey(System.getenv("DEEPSEEK_API_KEY"))
    .baseUrl("https://api.deepseek.com")
    .model("deepseek-chat")
    .systemPrompt("你是一个技术助手")
    .contextWindow(128000)
    .build();

// 阻塞调用
String answer = model.call("解释什么是事件溯源");
System.out.println(answer);

// 流式调用（获取原生 StreamChunk）
model.stream("写一个快速排序算法").subscribe(new Flow.Subscriber<StreamChunk>() {
    private Flow.Subscription subscription;
    
    public void onSubscribe(Flow.Subscription s) {
        subscription = s;
        s.request(Long.MAX_VALUE);
    }
    
    public void onNext(StreamChunk chunk) {
        switch (chunk) {
            case StreamChunk.TextDelta text -> 
                System.out.print(text.text());  // 打印文本增量
            case StreamChunk.ReasoningDelta reasoning -> 
                System.out.println("\n[思考] " + reasoning.text());  // 推理过程
            default -> {}
        }
    }
    
    public void onError(Throwable t) {
        t.printStackTrace();
    }
    
    public void onComplete() {
        System.out.println("\n=== 完成 ===");
    }
});
```

#### 场景 2：Model 传入 Agent（配置复用）

```java
// 创建 Model（复用配置）
var model = DeepSeekModel.builder()
    .apiKey(System.getenv("DEEPSEEK_API_KEY"))
    .model("deepseek-chat")
    .systemPrompt("你是一个通用助手")  // Model 级 systemPrompt（Agent 未显式设置时生效）
    .contextWindow(128000)
    .build();

// 创建 Agent 1（代码助手）
var codeAgent = DuoAgent.builder()
    .model(model)  // 复用 Model 配置
    .systemPrompt("你是一个代码助手")  // Agent 显式 systemPrompt，优先级最高
    .withCodeTools()
    .build();

// 创建 Agent 2（文档助手）
var docAgent = DuoAgent.builder()
    .model(model)  // 复用同一 Model
    .systemPrompt("你是一个文档助手")
    .withFileTools()
    .build();

// 多轮对话
String result1 = codeAgent.call("分析当前目录的代码");
String result2 = codeAgent.call("继续分析测试覆盖率");  // 有历史上下文

// 流式事件（Agent 特有）
docAgent.stream("编写 README").subscribe(new Flow.Subscriber<SessionEvent>() {
    public void onNext(SessionEvent event) {
        switch (event) {
            case SessionEventTurnStart start -> 
                System.out.println("=== 开始对话轮 ===");
            case SessionEventAssistantChunk chunk -> {
                if (chunk.chunk() instanceof StreamChunk.TextDelta text) {
                    System.out.print(text.text());
                }
            }
            case SessionEventToolCall call -> 
                System.out.println("\n[工具] " + call.name());
            case SessionEventToolResult result -> 
                System.out.println("[结果] " + result.message());
            case SessionEventTurnEnd end -> 
                System.out.println("\n=== 结束：" + end.reason() + " ===");
            default -> {}
        }
    }
    // ...
});
```

#### 场景 3：不同模型的 Agent

```java
// 快速模型（普通对话）
var fastModel = DeepSeekModel.builder()
    .apiKey(System.getenv("DEEPSEEK_API_KEY"))
    .model("deepseek-chat")
    .contextWindow(128000)
    .temperature(0.7)
    .build();

// 推理模型（复杂任务）
var reasoningModel = DeepSeekModel.builder()
    .apiKey(System.getenv("DEEPSEEK_API_KEY"))
    .model("deepseek-reasoner")
    .contextWindow(64000)
    .enableReasoning(true)
    .reasoningTimeout(Duration.ofMinutes(5))
    .build();

// 为不同任务创建不同 Agent
var chatAgent = DuoAgent.builder()
    .model(fastModel)
    .systemPrompt("你是聊天助手")
    .build();

var analysisAgent = DuoAgent.builder()
    .model(reasoningModel)
    .systemPrompt("你是代码分析专家，深度思考后给出建议")
    .withCodeTools()
    .build();

// 快速回答
String quickAnswer = chatAgent.call("今天天气怎么样？");

// 深度分析（会看到推理过程）
analysisAgent.stream("分析这个架构的潜在问题").subscribe(subscriber -> {
    // 可以看到 ReasoningDelta 思考过程
});
```

#### 场景 4：自定义异步封装

```java
// Model 不提供 callAsync，用户根据需要自己封装
var model = DeepSeekModel.builder()...build();

// 方式 1：使用 ForkJoinPool.commonPool()
CompletableFuture<String> future1 = CompletableFuture.supplyAsync(() -> 
    model.call("问题1")
);

// 方式 2：使用自定义线程池
ExecutorService executor = Executors.newFixedThreadPool(4);
CompletableFuture<String> future2 = CompletableFuture.supplyAsync(() -> 
    model.call("问题2"), 
    executor
);

// 方式 3：并发调用多个模型
var futures = List.of(
    CompletableFuture.supplyAsync(() -> model.call("总结文章")),
    CompletableFuture.supplyAsync(() -> model.call("提取关键词")),
    CompletableFuture.supplyAsync(() -> model.call("生成标题"))
);

CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
    .thenRun(() -> {
        futures.forEach(f -> System.out.println(f.join()));
    });
```

---

## 版本管理策略

### 分支策略

1. **创建 `0.1.0` 分支** - 保留当前稳定 API
   ```bash
   git checkout -b release/0.1.0
   git push origin release/0.1.0
   ```

2. **在 `main` 分支进行重构** - 引入 DuoModel（破坏性变更）
   - 删除 `DuoAgentBuilder` 的旧 API（apiFormat, apiKey, baseUrl 等）
   - 新增 `model(DuoModel)` API
   - 更新文档和示例

3. **版本号管理**：
   - `0.1.0` 分支：当前稳定版本，仅接受 bugfix
   - `main` 分支：0.2.0-SNAPSHOT，破坏性变更
   - `0.2.0` 正式发布后，`0.1.0` 分支进入维护模式

### 迁移指南（0.1.0 → 0.2.0）

**旧代码（0.1.0）**：
```java
var agent = DuoAgent.builder()
    .apiFormat("openai")
    .baseUrl("https://api.deepseek.com")
    .apiKey(System.getenv("DEEPSEEK_API_KEY"))
    .model("deepseek-chat")
    .contextWindow(128000)
    .withCodeTools()
    .build();

String result = agent.chat("分析代码");
```

**新代码（0.2.0）**：
```java
// 1. 先创建 Model
var model = DeepSeekModel.builder()
    .apiKey(System.getenv("DEEPSEEK_API_KEY"))
    .baseUrl("https://api.deepseek.com")
    .model("deepseek-chat")
    .contextWindow(128000)
    .build();

// 2. 传入 Agent
var agent = DuoAgent.builder()
    .model(model)
    .withCodeTools()
    .build();

// 3. 方法名变更
String result = agent.call("分析代码");  // chat() → call()
```

**方法名变更**：

| 0.1.0 | 0.2.0 | 说明 |
|-------|-------|------|
| `agent.chat(msg)` | `agent.call(msg)` | 阻塞调用 |
| `agent.chatAsync(msg)` | ❌ 删除 | 自己封装：`CompletableFuture.supplyAsync(() -> agent.call(msg))` |
| `agent.stream(msg)` | `agent.stream(msg)` | 保持不变，但返回 `SessionEvent` 而非 `String` |
| `agent.chatEvents(msg)` | ❌ 删除 | 合并到 `stream()`，`stream()` 现在返回完整事件 |

**旧 `stream()` 用户迁移（只要打字机文本的场景）**：

```java
// 0.1.0：Publisher<String>，直接就是文本增量
agent.stream(msg).subscribe(new Flow.Subscriber<String>() {
    public void onNext(String chunk) { System.out.print(chunk); }
    // ...
});

// 0.2.0：Publisher<SessionEvent>，需过滤出文本增量
agent.stream(msg).subscribe(new Flow.Subscriber<SessionEvent>() {
    public void onNext(SessionEvent event) {
        if (event instanceof SessionEventAssistantChunk c
                && c.chunk() instanceof StreamChunk.TextDelta t) {
            System.out.print(t.text());
        }
    }
    // ...
});
```

---

## 未来扩展

### 支持其他厂家

```java
// 千问 Model
var qwenModel = QwenModel.builder()
    .apiKey(...)
    .model("qwen-max")
    .contextWindow(200000)
    .build();

// GLM Model
var glmModel = GLMModel.builder()
    .apiKey(...)
    .model("glm-4-plus")
    .contextWindow(128000)
    .build();
```

### 高级特性（可选）

1. **Model 缓存/池化** - 复用 HTTP 连接
2. **Model 监控** - 统计调用次数、token 使用量
3. **Model 切换** - Agent 运行时切换模型（如：思考用推理模型，回答用快速模型）

---

## 约束与权衡

### 零依赖红线

✅ **符合** - DuoModel 不引入新的第三方依赖，复用现有 `LlmAdapter`。

### 超时分层红线（HTTP 兜底 ≥ 应用层最大超时）

✅ **经带参工厂维持** - `createAdapter(Duration httpTimeout)` 重载专门为此设计：
Agent 组装时计算 `max(Agent llmTimeout, Model reasoningTimeout) + 1 分钟` 后传入。
无参 `createAdapter()` 仅限 Model 自用的单次调用路径（应用层超时即 Model 自身配置）。
禁止 Agent 组装路径使用无参工厂——否则 Agent 设置更大的 llmTimeout 时，
HTTP 层会先于应用层 barrier 掐断 SSE 流式回复。

### 事件溯源不变量

✅ **无影响** - Model 层不涉及 Session 和事件日志，不影响事件溯源。

### 测试策略

- **Model 单元测试** - 测试单次调用逻辑
- **Agent 集成测试** - 测试 Model 传入 Agent 的场景
- **Adapter 复用** - 现有 `DeepSeekAdapterTest` 继续有效

---

## 决策日志

| 问题 | 决策 | 理由 |
|------|------|------|
| Model 是接口还是具体类？ | **接口（按厂家实现）** | DeepSeekModel, QwenModel, GLMModel 各自实现，未来扩展性强 |
| Model 要不要支持工具？ | **不支持** | 工具执行是 Agent 职责，Model 保持纯推理 |
| Model 要不要支持 Hook？ | **不支持** | Hook 是编排能力，属于 Agent 层 |
| Model 要不要支持 system prompt？ | **支持** | 单次调用也需要角色设定和输出格式控制 |
| 如何向后兼容？ | **破坏性变更 + 分支管理** | 0.1.0 分支保持稳定，main 重构为 0.2.0 |
| Model 和 Adapter 的关系？ | **工厂方法模式** | Model 提供 `createAdapter()`，不暴露敏感配置 |
| API 方法命名？ | **call() / stream()** | `call` = 阻塞调用，`stream` = 流式调用，统一 Model 和 Agent |
| stream() 返回什么？ | **Model 返回 StreamChunk，Agent 返回 SessionEvent** | Model 吐原生协议，Agent 吐编排语义 |
| 要不要 callAsync()？ | **不提供** | 用户自己封装 `CompletableFuture.supplyAsync(model::call)`，保持 API 简洁 |
| 要不要 temperature 等参数？ | **支持** | temperature, maxOutputTokens, systemPrompt 是常用参数 |
| createAdapter 的超时如何分层？ | **带参工厂 `createAdapter(Duration)`** | Model build 时不知道 Agent 的 llmTimeout，「HTTP 兜底 ≥ 应用层最大超时」只能在组装时刻由组装方计算传入，否则 Agent 设大超时会被 HTTP 层提前掐断 |
| systemPrompt 优先级？ | **Agent 显式 > Model > 内置默认** | 两个来源 + 一个默认值必须定序；Builder 默认值改为 null，避免内置文案静默覆盖 Model 的 prompt |
| Model.stream() 行为契约？ | **冷发布者 + 内部缓冲背压 + 单订阅** | 底层适配器是推式回调，request(n) 控制不了到达节奏，与 Agent 层一致靠内部缓冲（溢出 onError）；契约与 DuoAgent.stream 对齐 |
| Model 线程安全？ | **实例线程安全，可共享** | 每次 call/stream 创建独立 Adapter，并发调用互不干扰 |

---

## 参考资料

- [SDK_DESIGN.md](./SDK_DESIGN.md) - 现有架构设计
- [HANDOFF.md](./HANDOFF.md) - 项目决策历史
- 本次 grilling 会话 - 设计讨论过程
