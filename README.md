# Duo Agent

**零依赖的 Java AI Agent SDK**，让你用 6 行代码就能创建强大的 AI Agent。

## ✨ 特性

- 🚀 **极简 API** - Builder 模式，开箱即用
- 🔧 **内置工具链** - bash、文件操作、代码编辑、搜索等
- 🔌 **零依赖** - 纯 Java 21，无第三方依赖
- 🧪 **高质量** - 206 个单元测试，100% 工具链稳定性
- 🎯 **ReAct 架构** - 成熟的推理-行动循环模式

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
        // 3 行代码创建 Agent
        var agent = DuoAgent.builder()
                .deepseek("deepseek-chat")
                .withFileTools()
                .build();

        // 1 行代码开始对话
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
        .deepseek("deepseek-chat")
        .withCodeTools()  // bash + file + grep + edit
        .systemPrompt("你是专业的 Java 代码助手")
        .build();

agent.chat("帮我重构 UserService.java 中的重复代码");
```

### 完整配置

```java
var agent = DuoAgent.builder()
        .deepseek("deepseek-v4-flash", "your-api-key")
        .timeout(Duration.ofSeconds(120))
        .maxTokens(8000)
        .systemPrompt("你是专业的代码审查助手")
        .withCodeTools()  // 代码工具集
        .tool(new CustomTool())  // 自定义工具
        .build();
```

### 异步对话

```java
CompletableFuture<String> future = agent.chatAsync("分析项目依赖");
future.thenAccept(System.out::println);
```

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
| `.withAllBuiltinTools()` | 全部内置工具 | 全能助手 |

也可以手动添加工具：
```java
.tool(new CustomTool().getDefinition())
```

## 📦 项目结构

```
duo-agent/
├── duo-agent-sdk/          # SDK 核心模块
│   ├── src/main/java/      # SDK 源码
│   └── src/test/java/      # SDK 单元测试（206 个测试）
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
# HelloWorld 示例（最简单）
mvn exec:java -pl duo-agent-example -Dexec.mainClass="com.example.HelloWorldExample"

# 基础示例（展示底层 API）
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
- `HelloWorldExample.java` - 最简示例（6 行代码）
- `BasicAgentExample.java` - 底层 API 演示
- `ToolCallingExample.java` - 工具调用演示
- `DeepSeekToolsDemo.java` - 稳定性测试（100% 成功率）

### API 对比

**新 Builder API（推荐）：**
```java
// 6 行代码
var agent = DuoAgent.builder()
    .deepseek("deepseek-chat")
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

**代码量减少 90%！**

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
# 运行全部测试（206 个）
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
    .deepseek("deepseek-chat")
    .tool(new MyTool().getDefinition())
    .build();
```

## 📊 质量保证

- ✅ **206 个单元测试** - 覆盖核心功能
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
