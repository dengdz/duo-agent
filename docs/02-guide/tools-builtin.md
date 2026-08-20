# 内置工具

duo-agent 内置 8 种工具，模型通过 ReAct 循环自主决定何时调用。Builder 提供 5 组预设，也可以逐个组合。

## 工具预设

```java
DuoAgent.builder()
        .model(model)            // 必填：DuoModel 实例（模型配置见 DeepSeekModel.builder()）
        .withFileTools()          // file_read + file_write
        .withSearchTools()        // grep + glob
        .withEditTools()          // edit
        .withCodeTools()          // bash + file + search + edit（代码任务推荐）
        .withAllBuiltinTools()    // withCodeTools + todo_write
        ...
        .build();
```

> 💡 预设与自定义工具混用时，按**工具名去重（last-wins）**：后添加的定义覆盖先添加的——显式工具可以覆盖预设。

> ⚠️ `withAllBuiltinTools()` **不含 skill 工具**——它需要 SkillRegistry，参见 [Skill 系统](../03-advanced/skills.md)。

## 工具能力详解

### bash

执行 shell 命令（Unix `sh -c` / Windows `cmd`）。

```json
{ "command": "find . -name '*.java' | wc -l", "cwd": "/path", "timeout": 30 }
```

| 参数 | 必填 | 说明 |
|------|------|------|
| `command` | ✅ | 要执行的命令 |
| `cwd` | 可选 | 工作目录（默认当前目录） |
| `timeout` | 可选 | 秒，默认 30，范围 [1, 300] |

特性：stdout/stderr 合并返回；输出超 100KB 截断；专用线程池（防管道缓冲死锁）；超时强杀进程；非零退出码带标识前缀。

### file_read

读取文本文件。

```json
{ "path": "src/main/java/Hello.java" }
```

超过 200KB 拒绝读取（返回结构化错误，提示分段处理）。

### file_write

写入文件（**覆盖语义**），自动创建父目录。

```json
{ "path": "output/result.md", "content": "文件内容..." }
```

### grep

按 Java 正则搜索文件内容。

```json
{ "pattern": "public\\s+record", "path": ".", "include": "*.java" }
```

| 参数 | 必填 | 说明 |
|------|------|------|
| `pattern` | ✅ | Java 正则（`Pattern.compile` 语法，非法正则报错） |
| `path` | 可选 | 搜索根目录（默认当前目录） |
| `include` | 可选 | 文件名过滤 glob，如 `*.java`、`*.{js,jsx}`（不支持取反） |

特性：返回带行号的匹配行（按文件分组）；最多内联 250 条（超出报告总数并建议缩小范围）；单行预览截断 500 字符；自动跳过 `.git` 等 VCS 目录与二进制文件。

### glob

按 glob 模式查找文件。

```json
{ "pattern": "**/*.java", "path": "." }
```

模式语义：不含 `/` 的模式（如 `*.java`）匹配任意深度的文件名；含 `/` 的模式匹配相对路径。结果按修改时间排序（新在前），最多 100 条。

### edit

精确编辑文件，两种命令：

```json
// 精确替换（old_str 必须在文件中唯一匹配）
{ "command": "str_replace", "path": "a.txt",
  "old_str": "旧内容", "new_str": "新内容" }

// 按行号插入（insert_line=0 表示文件开头）
{ "command": "insert", "path": "a.txt",
  "insert_line": 10, "new_str": "插入的行" }
```

`new_str` 省略即删除 `old_str`。`old_str` 多处匹配时工具会列出全部冲突行号并要求扩大上下文——失败信息包含诊断建议（提示先 `file_read`）。

### todo_write

维护任务列表（整表替换）。

```json
{ "todos": [
    { "content": "分析需求", "status": "completed" },
    { "content": "编写实现", "status": "in_progress" },
    { "content": "补充测试", "status": "pending" }
] }
```

帮助模型在长任务中保持计划性。注意：当前版本 todo 状态仅存内存，不写入会话事件日志（见 [已知限制](../05-reference/limitations.md)）。

### skill

按名称加载技能完整正文（配合 SkillRegistry 使用，不在工具预设中）。

```json
{ "name": "code-review" }
```

## 在对话中的表现

启用工具后，模型的 ReAct 循环：

```
你：列出当前目录的 Java 文件并统计数量
模型 → 调用 glob(pattern="**/*.java")
       ← 工具返回文件列表
模型 → 调用 bash(command="... | wc -l")
       ← 工具返回 166
模型：当前目录共有 166 个 Java 文件……
```

整个过程在 `stream()` 中完整可见（`tool/call` → `tool/result` 事件成对出现）。

## 下一步

→ [自定义工具](tools-custom.md)：用 `ToolDefinition` 扩展你自己的能力
