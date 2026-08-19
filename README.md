# Duo Agent

**零依赖的 Java AI Agent SDK**，让你用几行代码就能创建强大的 AI Agent。

## ✨ 特性

- 🚀 **极简 API** - Builder 模式，开箱即用
- 🔧 **内置工具链** - bash、文件操作、代码编辑、搜索等
- 🔌 **零依赖** - 纯 Java 21，无第三方依赖
- 🧪 **高质量** - 213 个单元测试，100% 工具链稳定性
- 🎯 **ReAct 架构** - 成熟的推理-行动循环模式
- 🧠 **推理模式** - 支持 DeepSeek-R1 等深度推理模型

## 🚀 快速开始（5 分钟）

### 1. 添加依赖

**Maven:**
```xml
<dependency>
    <groupId>dev.duo</groupId>
    <artifactId>duo-agent-sdk</artifactId>
    <version>0.1.0-SNAPSHOT</version>
</dependency>
```

**Gradle:**
```gradle
implementation 'dev.duo:duo-agent-sdk:0.1.0-SNAPSHOT'
```

### 2. 设置 API Key

```bash
export DEEPSEEK_API_KEY=your_api_key
```

### 3. 编写第一个 Agent

```java
import dev.duo.api.DuoAgent;

public class HelloWorld {
    public static void main(String[] args) {
        var agent = DuoAgent.builder()
                .apiFormat("openai")                              // API 格式
                .baseUrl("https://api.deepseek.com")              // 基础 URL
                .apiKey(System.getenv("DEEPSEEK_API_KEY"))        // API Key
                .model("deepseek-chat")                           // 模型名称
                .contextWindow(128000)                            // 上下文窗口（可选）
                .withFileTools()
                .build();

        String response = agent.chat("当前目录有哪些 Java 文件？");
        System.out.println(response);
    }
}
```

**就这么简单！** Agent 会自动调用工具完成任务。

## 📖 更多示例

### 代码助手 Agent

```java
var agent = DuoAgent.builder()
        .apiFormat("openai")
        .baseUrl("https://api.deepseek.com")
        .apiKey(System.getenv("DEEPSEEK_API_KEY"))
        .model("deepseek-chat")
        .withCodeTools()  // bash + file + grep + edit
        .systemPrompt("你是专业的 Java 代码助手")
        .build();

agent.chat("帮我重构 UserService.java 中的重复代码");
```

### 推理模型 Agent（DeepSeek-R1）

```java
var agent = DuoAgent.builder()
        .apiFormat("openai")
        .baseUrl("https://api.deepseek.com")
        .apiKey(System.getenv("DEEPSEEK_API_KEY"))
        .model("deepseek-reasoner")
        .contextWindow(64000)
        .enableReasoning(true)      // 启用深度推理
        .timeout(Duration.ofMinutes(5))  // 推理耗时较长，建议加大超时
        .withCodeTools()
        .build();
```

> **注意：** 推理模型建议不要设置 `maxOutputTokens`，由模型决定输出长度，
> 避免 `<think>` 推理过程占用 token 导致回答被截断。

### 完整配置

```java
var agent = DuoAgent.builder()
        .apiFormat("openai")                          // 必填：API 格式（目前仅支持 "openai"）
        .baseUrl("https://api.deepseek.com")          // 必填：API 基础 URL
        .apiKey("your-api-key")                       // 必填：API 密钥
        .model("deepseek-chat")                       // 必填：模型名称
        .contextWindow(128000)                        // 可选：上下文窗口
        .maxOutputTokens(8000)                        // 可选：输出限制（默认不限制）
        .enableReasoning(false)                       // 可选：推理模式（默认关闭）
        .timeout(Duration.ofSeconds(120))             // 可选：LLM 超时（默认 60 秒）
        .systemPrompt("你是专业的代码审查助手")
        .withCodeTools()                              // 代码工具集
        .tool(new CustomTool().getDefinition())       // 自定义工具
        .build();
```

### 异步对话

```java
CompletableFuture<String> future = agent.chatAsync("分析项目依赖");
future.thenAccept(System.out::println);
```

> **线程安全提示：** 同一 DuoAgent 实例共享底层 Session，不是线程安全的。
> 如需并发处理多个请求，请为每个请求创建独立的 Agent 实例。

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
│   └── src/test/java/      # SDK 单元测试（213 个测试）
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

### 设置环境变量

```bash
export DEEPSEEK_API_KEY=your_api_key
```

### 运行示例

```bash
# Builder API 示例（推荐，最简单）
mvn exec:java -pl duo-agent-example -Dexec.mainClass="com.example.HelloWorldExample"

# 底层 API 示例（手动组装组件）
mvn exec:java -pl duo-agent-example -Dexec.mainClass="com.example.QuickStartExample"

# 基础示例
mvn exec:java -pl duo-agent-example -Dexec.mainClass="com.example.BasicAgentExample"

# 工具调用示例
mvn exec:java -pl duo-agent-example -Dexec.mainClass="com.example.ToolCallingExample"
```

## 🏗️ 架构设计

### 核心模块

**duo-agent-sdk** - 核心 SDK 模块，提供：
- 🔌 **LLM 抽象层** - 统一的 LLM 接口（支持 DeepSeek，可扩展 OpenAI 等）
- 🤖 **Agent 框架** - ReAct 模式实现（Reasoning + Acting 循环）
- 🛠️ **工具系统** - 内置 bash、文件操作、grep、glob、edit 等工具
- 📝 **会话管理** - 事件溯源的对话历史管理
- 🎯 **Builder API** - 简化的创建接口（本次新增）

**duo-agent-example** - 示例项目：
- `HelloWorldExample.java` - Builder API 快速开始（推荐）
- `QuickStartExample.java` - 底层 API 演示（手动组装组件）
- `BasicAgentExample.java` - 基础示例
- `ToolCallingExample.java` - 工具调用演示
- `DeepSeekToolsDemo.java` - 工具链稳定性测试

### API 对比

**新 Builder API（推荐）：**
```java
var agent = DuoAgent.builder()
    .apiFormat("openai")
    .baseUrl("https://api.deepseek.com")
    .apiKey(System.getenv("DEEPSEEK_API_KEY"))
    .model("deepseek-chat")
    .withFileTools()
    .build();
String response = agent.chat("任务描述");
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
# 运行全部测试（213 个）
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
    .apiFormat("openai")
    .baseUrl("https://api.deepseek.com")
    .apiKey(System.getenv("DEEPSEEK_API_KEY"))
    .model("deepseek-chat")
    .tool(new MyTool().getDefinition())
    .build();
```

## ⚠️ 已知限制

- **chatStream() 未实现** - 当前调用会抛出 `UnsupportedOperationException`，
  等待 DeepSeekAdapter SSE streaming 支持后开放
- **Anthropic 格式未支持** - `apiFormat()` 目前仅接受 `"openai"`，
  Anthropic 格式计划在未来版本支持
- **reasoningTimeout 未生效** - 推理模式下的超时配置暂未应用到 LLM 调用，
  如需更长超时请使用 `.timeout(Duration)`

## 📊 质量保证

- ✅ **213 个单元测试** - 覆盖核心功能
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

- [SDK 设计文档](SDK_DESIGN.md) - 架构设计与实现细节
- [SDK API 文档](duo-agent-sdk/README.md) - 底层 API 参考
- [开发交接文档](HANDOFF.md) - 开发历史与决策记录

## 🤝 贡献

欢迎提交 Issue 和 Pull Request！

## 📮 联系方式

如有问题或建议，请提交 Issue。
