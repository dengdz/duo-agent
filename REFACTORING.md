# 多模块项目重构完成总结

## 项目结构

```
duo-agent/                          # 父项目（多模块）
├── pom.xml                         # 父 POM，管理依赖版本
├── README.md                       # 项目总览文档
├── SDK_DESIGN.md                   # SDK 设计文档
│
├── duo-agent-sdk/                  # SDK 核心模块
│   ├── pom.xml                     # SDK 模块 POM
│   ├── README.md                   # SDK 使用文档
│   ├── src/main/java/dev/duo/     # SDK 源码（85 个文件）
│   │   ├── api/                    # 公共 API
│   │   ├── core/                   # 核心实现
│   │   ├── adapter/                # LLM 适配器
│   │   ├── tool/                   # 内置工具
│   │   ├── model/                  # 数据模型
│   │   ├── util/                   # 工具类
│   │   └── exception/              # 异常定义
│   └── src/test/java/              # 单元测试（74 个测试）
│
└── duo-agent-example/              # 示例/调试模块
    ├── pom.xml                     # Example 模块 POM
    ├── src/main/java/com/example/ # 示例代码
    │   ├── BasicAgentExample.java      # 基础对话示例
    │   └── ToolCallingExample.java     # 工具调用示例
    └── src/main/resources/
        └── logback.xml             # 日志配置
```

## 构建产物

### SDK 模块
```bash
duo-agent-sdk/target/
├── duo-agent-sdk-0.1.0.jar          # 主 JAR（170KB）
├── duo-agent-sdk-0.1.0-sources.jar  # 源码 JAR（79KB）
└── duo-agent-sdk-0.1.0-javadoc.jar  # 文档 JAR（872KB）
```

### Example 模块
```bash
duo-agent-example/target/
└── duo-agent-example-0.1.0.jar      # 可执行 JAR（包含所有依赖）
```

## 模块依赖关系

```
duo-agent-parent (pom)
    ├── duo-agent-sdk (jar)
    │   ├── slf4j-api (compile)
    │   └── logback-classic (provided, optional)
    │
    └── duo-agent-example (jar)
        ├── duo-agent-sdk (compile) ← 依赖 SDK
        └── logback-classic (compile)
```

## 使用方式

### 1. 构建整个项目

```bash
cd /Users/zhangyl/IdeaProjects/duo-agent
mvn clean install
```

**输出：**
- SDK 安装到本地 Maven 仓库：`~/.m2/repository/dev/duo/duo-agent-sdk/0.1.0/`
- Example 也会安装（但通常只用于开发调试）

### 2. 只构建 SDK

```bash
cd duo-agent-sdk
mvn clean install
```

### 3. 运行示例

```bash
# 设置 API Key
export DEEPSEEK_API_KEY=your_api_key

# 运行基础示例
cd duo-agent-example
mvn exec:java -Dexec.mainClass="com.example.BasicAgentExample"

# 或直接运行可执行 JAR
java -jar target/duo-agent-example-0.1.0.jar
```

### 4. 在其他项目中使用 SDK

创建新项目 `my-agent-app/pom.xml`：

```xml
<project>
    <modelVersion>4.0.0</modelVersion>
    <groupId>com.mycompany</groupId>
    <artifactId>my-agent-app</artifactId>
    <version>1.0.0</version>
    
    <properties>
        <maven.compiler.source>21</maven.compiler.source>
        <maven.compiler.target>21</maven.compiler.target>
    </properties>
    
    <dependencies>
        <!-- 引用 SDK -->
        <dependency>
            <groupId>dev.duo</groupId>
            <artifactId>duo-agent-sdk</artifactId>
            <version>0.1.0</version>
        </dependency>
        
        <!-- 日志实现 -->
        <dependency>
            <groupId>ch.qos.logback</groupId>
            <artifactId>logback-classic</artifactId>
            <version>1.4.11</version>
        </dependency>
    </dependencies>
</project>
```

## 开发工作流

### 修改 SDK 代码

1. 在 `duo-agent-sdk/src/main/java/` 下修改代码
2. 运行 `mvn install` 安装到本地仓库
3. duo-agent-example 会自动使用最新版本

### 调试 SDK

1. 在 `duo-agent-example/src/main/java/` 下编写测试代码
2. 直接运行/调试示例类
3. IDE 会自动关联到 SDK 源码（支持断点调试）

### 添加新示例

```java
package com.example;

import dev.duo.api.agent.AgentOptions;
// ...

public class CustomExample {
    public static void main(String[] args) throws Exception {
        // 使用 SDK 的代码
    }
}
```

## 在 IDE 中使用

### IntelliJ IDEA

1. **导入项目：**
   - File → Open
   - 选择 `/Users/zhangyl/IdeaProjects/duo-agent/pom.xml`
   - 选择 "Open as Project"
   - Maven 自动识别多模块结构

2. **项目结构：**
   ```
   duo-agent
   ├── duo-agent-sdk
   └── duo-agent-example
   ```

3. **运行示例：**
   - 右键 `BasicAgentExample.java`
   - 选择 "Run 'BasicAgentExample.main()'"
   - 或使用调试模式

4. **调试 SDK：**
   - 在 SDK 代码中设置断点
   - 调试运行示例代码
   - 会自动进入 SDK 断点

### Eclipse

1. **导入项目：**
   - File → Import → Maven → Existing Maven Projects
   - 选择 `/Users/zhangyl/IdeaProjects/duo-agent`
   - 勾选所有模块

2. **同 IntelliJ 操作类似**

## 优势总结

### ✅ 模块化清晰
- SDK 与示例分离
- 职责明确，易于维护

### ✅ 开发高效
- SDK 修改立即在示例中生效
- 不需要手动拷贝 JAR
- IDE 支持跨模块导航和调试

### ✅ 易于发布
- SDK 独立打包发布
- 示例代码不会包含在 SDK 中
- 生成标准的 Maven 构件（jar + sources + javadoc）

### ✅ 依赖管理规范
- 父 POM 统一管理版本
- 子模块继承版本配置
- 避免版本冲突

## 已修复的问题

1. ✅ POM 配置优化（groupId 统一、版本语义化）
2. ✅ 依赖管理（logback 改为可选依赖）
3. ✅ 多模块结构重构
4. ✅ 示例代码补充（2 个示例）
5. ✅ 文档完善（README + SDK_DESIGN）
6. ✅ 构建验证（编译通过、所有测试通过）

## 后续改进建议

### 短期
1. 补充更多示例（自定义工具、自定义适配器）
2. 完善 Javadoc
3. 添加集成测试

### 中期
1. 添加 Builder 模式简化 API
2. 支持更多 LLM（OpenAI、Claude）
3. 发布到 Maven Central

### 长期
1. Spring Boot Starter
2. 多 Agent 协作
3. 持久化支持
