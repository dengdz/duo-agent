# SDK 设计

duo-agent SDK 是面向 Java 开发者的 AI Agent 开发框架，提供统一的 LLM 抽象、开箱即用的 ReAct 模式 Agent、灵活的工具系统和完整的会话管理。

## 设计原则

### 1. 最小惊讶原则

SDK 的行为符合 Java 开发者直觉：
- 使用标准 Java 命名约定
- 遵循 Builder 模式创建复杂对象
- 使用 CompletableFuture 处理异步操作
- 使用 AutoCloseable 管理资源

### 2. 渐进式复杂度

提供多层次的 API，从简单到高级：

```java
// 简单：使用默认配置
var agent = DuoAgent.builder()
    .model(model)
    .build();

// 中级：配置常用选项
var agent = DuoAgent.builder()
    .model(model)
    .maxOutputTokens(4096)
    .llmTimeout(Duration.ofSeconds(120))
    .withCodeTools()
    .build();

// 高级：完全自定义 hooks
var hooks = AgentHooks.builder()
    .addRequestHook(customRequestHook)
    .addToolHook(customToolHook)
    .build();
var agent = DuoAgent.builder()
    .model(model)
    .hooks(hooks)
    .build();
```

### 3. 类型安全

充分利用 Java 类型系统：
- 使用 Record 定义不可变数据模型
- 使用 Sealed 接口限制类型层次
- 使用泛型提供类型安全的 API
- 避免使用 Object 和弱类型 Map

### 4. 线程安全

所有公共 API 都是线程安全的：
- 不可变对象优先（Record）
- 可变对象使用并发安全集合（ConcurrentHashMap）
- 适当使用同步机制（synchronized）
- 在 Javadoc 中明确说明线程安全性

---

## 目录结构规范

SDK 采用**分层架构 + 垂直切分**的组织方式：

```
dev.duo/
├── api/              # 公开 API 层（用户直接使用）
│   ├── agent/        # Agent API（含 AgentHooks 扩展点）
│   ├── hook/         # Hook 扩展点接口
│   ├── llm/          # LLM 相关 API
│   └── skill/        # Skill 系统 API
├── core/             # 核心实现层
│   ├── agent/        # ReactLoopAgent 实现
│   ├── compaction/   # 上下文压缩
│   ├── llm/          # LLM 核心逻辑（含 LlmRetryHook）
│   ├── session/      # Session 管理、JSONL 持久化
│   └── skill/        # Skill 加载与注册
├── adapter/          # 适配器层（外部系统集成）
│   ├── openai/       # OpenAI 适配器
│   ├── anthropic/    # Anthropic 适配器
│   └── responses/    # 阿里云百炼 Responses 适配器
├── tool/             # 内置工具层
│   ├── BashTool.java
│   ├── ReadTool.java
│   ├── WriteTool.java
│   ├── EditTool.java
│   └── ...
├── model/            # 数据模型层
│   ├── llm/          # LLM 相关模型
│   └── session/      # Session 相关模型
├── exception/        # 异常定义
└── util/             # 工具类
```

### 各层职责

#### 1. `api/` - 公开 API 层
SDK 用户直接使用的接口，是稳定的公开契约。

**规范**：
- 只放接口（interface）、枚举、不可变记录（record）
- 按功能领域分包：`agent`、`llm`、`skill` 等
- 不依赖 `core` 包，可依赖 `model` 和 `exception`
- 接口要稳定，变更需考虑向后兼容

#### 2. `core/` - 核心实现层
API 的默认实现，内部核心逻辑。

**规范**：
- 实现 `api` 包中的接口
- 按功能领域分包，与 `api` 对应
- 包内可见（package-private）的类不对外暴露
- 可以互相依赖，但要避免循环依赖

#### 3. `adapter/` - 适配器层
集成外部 LLM 服务。

**规范**：
- 每个外部系统一个独立子包
- 实现 `LlmAdapter` 接口
- 适配器之间完全独立，不互相依赖

#### 4. `tool/` - 工具层
Agent 可以调用的工具实现。

**规范**：
- 扁平结构，每个工具一个文件
- 工具类命名：`XxxTool.java`
- 工具之间独立，不互相依赖

#### 5. `model/` - 数据模型层
数据传输对象（DTO）、值对象（Value Object）。

**规范**：
- 优先使用不可变的 Java Record
- 按业务领域分包：`llm`、`session` 等
- 只包含数据，不包含业务逻辑
- 可以被任何层依赖

---

## 扩展点机制

新能力通过 `api/hook` 的四个拦截点外挂实现：

### Hook 类型

1. **PreStepHook**：step 进入决策，可拒绝/改写消息
2. **RequestHook**：环绕请求构造，可改写 GenerateOptions
3. **RequestErrorHook**：失败恢复决策，如重试
4. **ToolExecutionHook**：环绕工具执行，可实现审批/超时/审计

### Hook 链语义

- 先注册者在最外层
- 调用 `chain.proceed()` 委托下游（最终是内置行为）
- 不调用即接管/否决
- `proceed()` 仅可调用一次
- hook 抛异常会传播并导致所在 step 失败（fail loud）

### 示例：压缩 Hook

`CompactionHook` 是第一个纯外挂核心能力：

- step 间压力触发（token 估算超阈值）
- 保尾选区（工具配对不拆散）
- 复用对话前缀的摘要调用
- `SurfaceOp.Replace` 把选中表面范围替换为摘要 checkpoint
- 事务以 `compaction/start` 为持久锁、成败各落一条 `compaction/end`

---

## 依赖管理

### 零三方依赖原则

SDK 保持最小依赖：

**强制依赖（compile scope）**：
- `slf4j-api 2.0.9` - 日志接口

**可选依赖（provided/optional）**：
- `logback-classic 1.4.11` - 日志实现，SDK 用户可选择

**测试依赖（test scope）**：
- `junit-jupiter 5.11.0`

**不依赖**：
- ✅ 不依赖 Jackson、Gson 等 JSON 库（自己实现了 JsonParser）
- ✅ 不依赖 HTTP 客户端库（使用 Java 11+ HttpClient）

---

## 版本管理

### 版本号规范

采用语义化版本 `MAJOR.MINOR.PATCH`：

- **0.1.0**：初始版本
- **0.2.0**：DuoModel 两层 API
- **0.3.0**：多厂商适配
- **0.4.0**：cancel() 门面 API
- **1.0.0**：API 稳定版本

### 兼容性承诺

- `0.x.x` 版本：API 可能变化，不保证向后兼容
- `1.0.0+` 版本：遵循语义化版本，保证向后兼容

---

## 文档要求

### Javadoc 覆盖

**必须有 Javadoc 的类/接口**：
- 所有 `dev.duo.api` 包下的接口和类
- 所有 `dev.duo.model` 包下的公共类
- 所有 `dev.duo.exception` 包下的异常类
- 所有 `dev.duo.tool` 包下的工具类

**Javadoc 内容要求**：
- 类级别：说明用途、使用场景、示例代码
- 方法级别：参数说明、返回值说明、异常说明
- 使用 `@since` 标记版本
- 使用 `@deprecated` 标记废弃 API

---

## 典型扩展场景

### 场景 1：添加新的 LLM 提供商（如 Gemini）

```
1. adapter/gemini/GeminiAdapter.java       # 实现 LlmAdapter 接口
2. adapter/gemini/GeminiClient.java        # HTTP 客户端
3. adapter/gemini/GeminiSseParser.java     # SSE 解析器
```

### 场景 2：添加新工具

```
tool/WebSearchTool.java                    # 单个文件即可
```

### 场景 3：添加自定义 Hook

```java
public class ApprovalHook implements ToolExecutionHook {
    @Override
    public ToolExecutionResult around(ToolCallContext context, Chain next) throws Exception {
        if (needsApproval(context.toolName())) {
            // 等待用户确认
            waitForApproval(context);
        }
        return next.proceed();
    }
}
```

---

## 命名规范

- **接口**：名词，如 `Agent`、`LlmAdapter`、`Skill`
- **实现类**：接口名 + 策略/技术，如 `ReactLoopAgent`、`JsonlSessionPersistence`
- **适配器**：服务商名 + Adapter，如 `OpenAIAdapter`、`AnthropicAdapter`
- **工具类**：功能 + Tool，如 `BashTool`、`ReadTool`
- **模型类**：领域名词，如 `Message`、`SessionEvent`、`ToolDefinition`
- **异常类**：领域 + Exception，如 `LlmException`、`AgentCreationException`

---

## 依赖关系图

```
┌─────────┐
│   api   │ ◄─── 用户直接使用
└────┬────┘
     │
     ▼
┌─────────┐      ┌──────────┐
│  core   │ ◄──► │  model   │
└────┬────┘      └────┬─────┘
     │                │
     │                │
     ▼                ▼
┌─────────┐      ┌───────────┐
│ adapter │      │   tool    │
└─────────┘      └───────────┘
     │                │
     └────────┬───────┘
              ▼
         ┌─────────┐
         │  util   │
         └─────────┘
              ▲
              │
         ┌─────────┐
         │exception│
         └─────────┘
```

**依赖规则**：
- `api` 只依赖 `model` 和 `exception`
- `core` 可以依赖 `api`、`model`、`exception`、`util`
- `adapter` 可以依赖 `api`、`model`、`exception`、`util`
- `tool` 可以依赖 `api`、`model`、`exception`、`util`
- `model` 只依赖 `model`（内部）和 `exception`
- `exception` 不依赖任何业务包
- `util` 不依赖任何业务包

---

## 使用场景

### 1. 聊天机器人

```java
var chatbot = DuoAgent.builder()
    .model(model)
    .build();

var response = chatbot.call("你好，请介绍一下自己");
System.out.println(response);
```

### 2. 自动化任务执行

```java
var agent = DuoAgent.builder()
    .model(model)
    .withCodeTools()
    .build();

agent.call("请读取 data.txt 文件，统计行数，并将结果写入 result.txt");
```

### 3. 流式响应

```java
agent.stream("分析这段代码的性能问题").subscribe(
    chunk -> System.out.print(chunk.text()),
    error -> System.err.println("Error: " + error.getMessage()),
    () -> System.out.println("\n[完成]")
);
```

---

## 参考链接

- [分层设计](./overview.md)
- [事件溯源](./event-sourcing.md)
- [ReAct 循环](./react-loop.md)
- [Hook 扩展](../03-advanced/hooks.md)
