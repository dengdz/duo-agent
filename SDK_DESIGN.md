# Duo Agent SDK 设计文档

## 目标定位

Duo Agent SDK 是一个面向 Java 开发者的 AI Agent 开发框架，提供：

1. **LLM 抽象层**：统一的 LLM 调用接口，支持多种 LLM 提供商
2. **Agent 框架**：开箱即用的 ReAct 模式 Agent 实现
3. **工具系统**：灵活的工具注册和执行机制
4. **会话管理**：完整的对话历史和事件流管理

## SDK 化改造要点

### 1. 模块结构调整

#### 当前状态 ✅
```
dev.duo/
├── api/              # 公共 API - SDK 用户主要使用
├── core/             # 核心实现 - SDK 内部实现
├── adapter/          # LLM 适配器 - 可扩展
├── tool/             # 内置工具 - 开箱即用
├── model/            # 数据模型 - 公共接口
├── util/             # 工具类 - 内部使用
└── exception/        # 异常定义 - 公共接口
```

#### 包可见性建议

**公共 API（SDK 用户直接使用）：**
- `dev.duo.api.*` - 全部 public
- `dev.duo.model.*` - 全部 public（数据模型）
- `dev.duo.exception.*` - 全部 public（异常类型）
- `dev.duo.adapter.deepseek.DeepSeekAdapter` - public（示例适配器）
- `dev.duo.tool.*` - 全部 public（内置工具）

**内部实现（SDK 用户不应直接使用）：**
- `dev.duo.core.*` - 部分 public，部分 package-private
  - `ReactLoopAgent`, `Session`, `ToolRegistryImpl`, `SystemPromptImpl` 等核心实现类保持 public
  - 内部工具类可以设为 package-private
- `dev.duo.adapter.deepseek.DeepSeekSseParser` - package-private（内部实现）
- `dev.duo.adapter.deepseek.DeepSeekRequestBuilder` - package-private（内部实现）
- `dev.duo.adapter.deepseek.DeepSeekJsonExtractor` - package-private（内部实现）

### 2. 依赖管理

#### 当前依赖分析 ✅

**强制依赖（compile scope）：**
- `slf4j-api 2.0.9` - 日志接口 ✅

**可选依赖（provided/optional）：**
- `logback-classic 1.4.11` - 日志实现，SDK 用户可选择 ✅

**测试依赖（test scope）：**
- `junit-jupiter 5.11.0` ✅

**零三方依赖原则：**
- ✅ 仅依赖 Java 标准库和 SLF4J
- ✅ 不依赖 Jackson、Gson 等 JSON 库（自己实现了 JsonParser）
- ✅ 不依赖 HTTP 客户端库（使用 Java 11+ HttpClient）

### 3. 版本管理

#### 版本号规范

采用语义化版本 `MAJOR.MINOR.PATCH[-QUALIFIER]`：

- **0.1.0**：当前开发版本
- **0.1.0**：第一个正式版本
- **0.2.0**：添加新功能（向后兼容）
- **1.0.0**：API 稳定版本

#### 兼容性承诺

- `0.x.x` 版本：API 可能变化，不保证向后兼容
- `1.0.0+` 版本：遵循语义化版本，保证向后兼容

### 4. 文档要求

#### Javadoc 覆盖

**必须有 Javadoc 的类/接口：**
- 所有 `dev.duo.api` 包下的接口和类
- 所有 `dev.duo.model` 包下的公共类
- 所有 `dev.duo.exception` 包下的异常类
- 所有 `dev.duo.tool` 包下的工具类
- 所有 `dev.duo.adapter` 包下的公共适配器类

**Javadoc 内容要求：**
- 类级别：说明用途、使用场景、示例代码
- 方法级别：参数说明、返回值说明、异常说明
- 使用 `@since` 标记版本
- 使用 `@deprecated` 标记废弃 API

#### 示例代码

在 `src/test/java` 下创建 `examples` 包，提供完整示例：
- `BasicAgentExample.java` - 基本 Agent 使用
- `CustomToolExample.java` - 自定义工具
- `CustomAdapterExample.java` - 自定义 LLM 适配器
- `StreamingExample.java` - 流式响应处理

### 5. 测试要求

#### 测试覆盖率

- 核心 API：80%+ 覆盖率
- 工具类：70%+ 覆盖率
- 适配器：60%+ 覆盖率（需要 mock 外部 API）

#### 测试分类

```java
@Tag("unit")        // 单元测试，不依赖外部资源
@Tag("integration") // 集成测试，需要真实 API Key
@Tag("example")     // 示例代码测试
```

### 6. 发布流程

#### 本地安装

```bash
mvn clean install
```

安装到本地 Maven 仓库后，其他项目可以引用：

```xml
<dependency>
    <groupId>dev.duo</groupId>
    <artifactId>duo-agent-sdk</artifactId>
    <version>0.1.0</version>
</dependency>
```

#### 发布到 Maven Central（未来）

需要配置：
1. GPG 签名
2. Sonatype OSSRH 账号
3. Maven 发布插件

```bash
mvn clean deploy -P release
```

## API 设计原则

### 1. 最小惊讶原则

SDK 的行为应该符合 Java 开发者的直觉：
- 使用标准 Java 命名约定
- 遵循 Builder 模式创建复杂对象
- 使用 CompletableFuture 处理异步操作
- 使用 AutoCloseable 管理资源

### 2. 渐进式复杂度

提供多层次的 API：
- **简单场景**：开箱即用的默认配置
- **中级场景**：提供常用配置选项
- **高级场景**：允许完全自定义

示例：

```java
// 简单：使用默认配置
var agent = AgentBuilder.create()
    .withLlm("deepseek", "deepseek-chat")
    .build();

// 中级：配置常用选项
var agent = AgentBuilder.create()
    .withLlm("deepseek", "deepseek-chat")
    .withTimeout(Duration.ofSeconds(120))
    .withMaxTokens(4096)
    .withTools(new BashTool(), new FileReadTool())
    .build();

// 高级：完全自定义
var agent = new ReactLoopAgent(
    sessionId,
    options,
    customSession,
    customLlmRuntime,
    customSystemPrompt,
    customToolRegistry
);
```

### 3. 类型安全

充分利用 Java 类型系统：
- 使用 Record 定义不可变数据模型
- 使用 Sealed 接口限制类型层次
- 使用泛型提供类型安全的 API
- 避免使用 Object 和弱类型 Map

### 4. 线程安全

所有公共 API 都应该是线程安全的：
- 不可变对象优先（Record）
- 可变对象使用并发安全集合（ConcurrentHashMap）
- 适当使用同步机制（synchronized、Lock）
- 在 Javadoc 中明确说明线程安全性

## 后续改进计划

### 短期（0.1.x）

1. **Builder 模式重构**
   - 提供 `AgentBuilder` 简化 Agent 创建
   - 提供 `ToolDefinitionBuilder` 简化工具定义

2. **示例代码补充**
   - 添加完整的示例项目
   - 补充 Javadoc 示例代码

3. **测试补充**
   - 提高测试覆盖率到 80%+
   - 添加集成测试

### 中期（0.2.x）

1. **更多 LLM 支持**
   - OpenAI 适配器
   - Claude 适配器
   - 本地模型适配器（Ollama）

2. **工具生态**
   - HTTP 请求工具
   - 数据库查询工具
   - 搜索引擎工具

3. **观测性增强**
   - 结构化日志
   - Metrics 支持
   - Trace 支持

### 长期（1.0.x）

1. **Spring Boot Starter**
   - 自动配置
   - 配置属性绑定
   - Health Check

2. **多 Agent 协作**
   - Agent 间通信
   - 任务分发
   - 结果聚合

3. **持久化支持**
   - Session 持久化到数据库
   - 对话历史导出/导入

## 使用场景

### 1. 聊天机器人

```java
var chatbot = AgentBuilder.create()
    .withLlm("deepseek", "deepseek-chat")
    .withSystemPrompt("你是一个友好的助手")
    .build();

while (true) {
    var userInput = scanner.nextLine();
    chatbot.followup(MessageFactory.createUserMessage(userInput)).get();
    var response = session.getLastAssistantMessage();
    System.out.println(response);
}
```

### 2. 自动化任务执行

```java
var automationAgent = AgentBuilder.create()
    .withLlm("deepseek", "deepseek-chat")
    .withTools(
        new BashTool(),
        new FileReadTool(),
        new FileWriteTool()
    )
    .build();

automationAgent.followup(MessageFactory.createUserMessage(
    "请读取 data.txt 文件，统计行数，并将结果写入 result.txt"
)).get();
```

### 3. 代码助手

```java
var codeAssistant = AgentBuilder.create()
    .withLlm("deepseek", "deepseek-coder")
    .withSystemPrompt("你是一个专业的代码助手")
    .withTools(new CodeAnalysisTool(), new GitTool())
    .build();

codeAssistant.followup(MessageFactory.createUserMessage(
    "分析当前项目的代码质量，并提供改进建议"
)).get();
```

## 总结

通过以上改造，Duo Agent SDK 将成为一个：

1. **易用的**：提供简洁的 API 和丰富的文档
2. **可扩展的**：支持自定义 LLM 适配器和工具
3. **高质量的**：遵循最佳实践，测试覆盖率高
4. **生产就绪的**：线程安全，性能优化，错误处理完善

其他项目可以通过简单的 Maven 依赖引入 SDK，快速构建自己的 AI Agent 应用。
