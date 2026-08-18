# Duo Agent

一个用于构建 AI Agent 的 Java SDK 及示例项目。

## 项目结构

```
duo-agent/
├── duo-agent-sdk/          # SDK 核心模块
│   ├── src/main/java/      # SDK 源码
│   └── src/test/java/      # SDK 单元测试
├── duo-agent-example/      # 示例/调试模块
│   └── src/main/java/      # 使用示例
└── pom.xml                 # 父 POM
```

## 快速开始

### 1. 构建项目

```bash
# 构建整个项目（包括 SDK 和示例）
mvn clean install

# 仅构建 SDK
cd duo-agent-sdk && mvn clean install
```

### 2. 设置环境变量

```bash
export DEEPSEEK_API_KEY=your_api_key_here
```

### 3. 运行示例

```bash
# 运行基础示例
cd duo-agent-example
mvn exec:java -Dexec.mainClass="com.example.BasicAgentExample"

# 运行工具调用示例
mvn exec:java -Dexec.mainClass="com.example.ToolCallingExample"
```

## 模块说明

### duo-agent-sdk

核心 SDK 模块，提供：
- LLM 抽象层和适配器
- Agent 框架（ReAct 模式）
- 工具系统
- 会话管理

**使用方式：**

```xml
<dependency>
    <groupId>dev.duo</groupId>
    <artifactId>duo-agent-sdk</artifactId>
    <version>0.1.0-SNAPSHOT</version>
</dependency>
```

### duo-agent-example

示例项目，演示如何使用 SDK：
- `BasicAgentExample.java` - 基础对话示例
- `ToolCallingExample.java` - 工具调用示例

## 开发指南

### 在 IDE 中导入

**IntelliJ IDEA:**
1. File → Open → 选择 `duo-agent/pom.xml`
2. 选择 "Open as Project"
3. Maven 会自动导入所有模块

**Eclipse:**
1. File → Import → Maven → Existing Maven Projects
2. 选择 `duo-agent` 目录
3. 勾选所有模块后导入

### 调试 SDK

1. 在 `duo-agent-example` 中编写测试代码
2. 直接运行或调试示例类
3. SDK 的修改会自动生效（模块依赖）

### 添加新示例

在 `duo-agent-example/src/main/java/com/example/` 下创建新类：

```java
package com.example;

import dev.duo.api.agent.AgentOptions;
import dev.duo.api.llm.LlmRuntime;
// ...

public class MyExample {
    public static void main(String[] args) {
        // 使用 SDK 的代码
    }
}
```

## 系统要求

- Java 21 或更高版本
- Maven 3.8+ 

## 许可证

Apache License 2.0

## 更多文档

- [SDK 设计文档](SDK_DESIGN.md)
- [API 文档](duo-agent-sdk/README.md)
