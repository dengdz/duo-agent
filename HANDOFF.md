# Handoff: duo-agent (Java port of DeepSeek Harness)

## 最新交接（2026-08-18）

### 当前目标与已完成工作

本项目已从 `mp-agent` 重命名为 `duo-agent`：

- 项目目录：`/Users/zhangyl/IdeaProjects/duo-agent`
- Maven `artifactId` 与 `name`：`duo-agent`
- 系统身份提示词已改为 `You are an AI agent powered by duo-agent.`
- 本文档和 `COMPARISON.md` 中的项目名、项目路径已同步
- IntelliJ IDEA 的模块名和 Maven 运行配置已同步为 `duo-agent`
- Java 包名 `dev.dsh` 保持不变，这是代码命名空间，不属于项目目录名改名范围

三个内置工具的测试已增加实际结果输出，但生产代码未改动：

- `BashToolTest`：打印命令输出、工作目录结果、非零退出码和缺少参数提示
- `FileToolTest`：打印写入、读取、文件不存在和覆盖后的结果
- `TodoWriteToolTest`：打印正常写入、任务列表替换、非法状态跳过和缺少参数结果

重命名前已执行：

```text
mvn -q -Dtest=BashToolTest,FileToolTest,TodoWriteToolTest test
Tests run: 12, Failures: 0, Errors: 0, Skipped: 0
```

### 当前验证阻塞

重命名后，当前代理会话的 shell 仍绑定旧工作目录 `/Users/zhangyl/IdeaProjects/mp-agent`。旧目录被移动后，工具运行器执行命令时返回：

```text
spawn /bin/zsh ENOENT
```

因此重命名后的 `mvn test` 尚未在本会话中重新执行。代码和配置已通过新路径读取确认，但下一位代理必须在新终端或恢复工作目录后重新验证：

```bash
cd /Users/zhangyl/IdeaProjects/duo-agent
mvn -q test
```

同时检查旧名称残留：

```bash
rg -n --hidden --glob '!.git/**' --glob '!target/**' --glob '!.workbuddy/**' \\
  'mp-agent|mp_agent|MP Agent|mp agent' .
```

预期无输出。

### Git 状态与待提交内容

用户已明确要求提交 Git，但提交尚未完成；交接时不要假设已有新提交。已知本轮相关修改包括：

- `pom.xml`
- `src/main/java/dev/dsh/core/llm/SystemPromptImpl.java`
- `src/test/java/dev/dsh/core/llm/tools/BashToolTest.java`
- `src/test/java/dev/dsh/core/llm/tools/FileToolTest.java`
- `src/test/java/dev/dsh/core/llm/tools/TodoWriteToolTest.java`
- `HANDOFF.md`
- `COMPARISON.md`（此前为未跟踪文件）
- `.idea/workspace.xml`
- `.idea/compiler.xml`
- `.workbuddy/memory/2026-08-18.md`（已有本轮之前的本地修改，提交前需单独确认是否纳入）

提交前应在新目录执行：

```bash
git status --short --branch
git diff --stat
git diff --cached --stat
```

建议提交项目改名、工具测试输出和相关文档；不要未经确认把 `.workbuddy/memory/2026-08-18.md` 一并提交。`COMPARISON.md` 是否纳入提交也应根据其是否属于本次交付范围决定。

建议提交信息：

```text
chore(project): 将项目重命名为 duo-agent
```

如果同时提交工具测试输出，建议使用能覆盖两类变更的说明，例如：

```text
chore(project): 重命名项目并增强工具测试输出
```

### 建议下一步

1. 在新终端打开 `/Users/zhangyl/IdeaProjects/duo-agent`。
2. 执行 `mvn -q test`，确认重命名未影响构建。
3. 检查旧名称残留和 Git 工作区。
4. 仅暂存本次项目改名、测试日志及明确纳入范围的文档。
5. 提交后再继续 Workspace 设计，随后实现 Workspace 作用域的 SessionStore 和 JSONL 持久化。

### 建议技能

- `git-commit-gen`：分析本轮差异、整理暂存区并生成 Conventional Commit
- `handoff`：下一次上下文不足时更新本交接稿
- `grill-with-docs`：在实现 Workspace 前明确 root、cwd、状态目录、生命周期和并发边界
- `mermaid-diagram`：绘制 Workspace、SessionStore、AgentRegistry 和 ToolRuntime 的关系图

---

## 项目状态

Java 21 + Maven 多模块项目，位于 `/Users/zhangyl/IdeaProjects/duo-agent`。已完成 deepseek-harness 核心管线的移植，**76 个测试，0 失败**（3 个真实 API 测试因缺少 `DEEPSEEK_API_KEY` 被跳过）。

## 已完成模块

| 模块 | 包路径 | 对应原版 | 状态 |
|---|---|---|---|
| LLM 词汇表 | `model/llm/` | `packages/llm/llm/src/types.ts` | ✅ |
| 消息模型 | `model/llm/` (Message, MessageFactory, MessageSource) | `packages/llm/llm/src/message.ts` | ✅ |
| BlockAssembler | `core/llm/BlockAssembler.java` | `packages/llm/llm/src/assembler.ts` | ✅ |
| LlmRuntime | `api/llm/LlmRuntime.java` | `packages/llm/llm/src/index.ts` | ✅ |
| LlmAdapter | `api/llm/LlmAdapter.java` | `packages/llm/llm/src/index.ts` | ✅ |
| DeepSeekAdapter | `core/llm/deepseek/DeepSeekAdapter.java` | `packages/llm/llm-deepseek/` | ✅ |
| Session 事件日志 | `core/session/Session.java` | `packages/core/session/src/index.ts` | ✅ |
| SessionStore | `api/agent/SessionStore.java`（接口）+ `core/session/InMemorySessionStore.java`（内存实现） | `packages/core/session/src/index.ts` | ✅ |
| SurfaceManager | `core/session/surface/SurfaceManager.java` | `packages/core/session/src/surface.ts` | ✅ |
| 会话事件类型 | `model/session/` (SessionEvent 系 13 个文件) | `packages/core/session/src/types.ts` | ✅ |
| Agent 接口 | `api/agent/Agent.java` | `packages/core/agent/src/runtime-types.ts` | ✅ |
| AgentRegistry | `api/agent/AgentRegistry.java` | `packages/core/agent/src/index.ts` | ✅ |
| Inbox | `api/agent/Inbox.java` | `packages/core/agent/src/inbox.ts` | ✅ |
| ReactLoopAgent | `core/agent/ReactLoopAgent.java` | `packages/core/agent-loop/src/agent.ts` | ✅ |
| SystemPrompt | `api/llm/SystemPrompt.java` + `core/llm/SystemPromptImpl.java` | `packages/core/system-prompt/src/index.ts` | ✅ |
| ToolRegistry | `api/llm/ToolRegistry.java` + `core/llm/ToolRegistryImpl.java` | `packages/core/tools/src/index.ts` | ✅ |
| TodoWriteTool | `core/llm/tools/TodoWriteTool.java` | `packages/todo/` | ✅ |
| BashTool | `core/llm/tools/BashTool.java` | 原版 terminal/bash 能力 | ✅ |
| FileReadTool | `core/llm/tools/FileReadTool.java` | 原版 file 能力 | ✅ |
| FileWriteTool | `core/llm/tools/FileWriteTool.java` | 原版 file 能力 | ✅ |

## 当前问题

**DeepSeekAdapter 工具调用解析已修复并验证**（通过新增的无真实 API 依赖的单元测试）。主要改动：
1. `hasToolCalls` 标志在首条工具调用 chunk 到达时立即设置。
2. 工具调用 id/name/arguments 从 `choices[0].delta.tool_calls[0]` 嵌套结构中正确提取，避免误取消息级 `id`。
3. 每轮 `stream()` 调用前重置解析状态，防止同一适配器复用时泄漏。
4. `ToolCallDelta` 随 arguments 分片实时发射，`BlockEnd` + `Finish` 在 `finish_reason` 到达时发射。

## 未完成的工作

1. **agent/pre-step/request/turn-stopping 拦截器**：对应原版 `packages/core/agent/src/runtime-types.ts` 中的 waterfall 事件
2. **Session 持久化**：JSONL/SQLite 后端
3. **Web UI 前端**
4. **真实 API 回归测试**：需要 `DEEPSEEK_API_KEY` 环境变量运行 `工具调用往返测试`。该测试现已断言 `todos` 非空，可捕捉参数解析回归。

## 已知坑位（已修复，记录备查）

- **工具参数解析**：`ReactLoopAgent.parseJsonArgs()` 原先用扁平正则，会把 `{"todos":[...]}` 的数组整个当成字符串塞进 map，导致 `TodoWriteTool` 里 `(List) args.get("todos")` 抛出 `ClassCastException`（被 ToolRegistryImpl 兜底成"错误结果"），工具看似执行了却记 0 条。已新增零依赖 `dev.dsh.util.JsonParser` 做真正的嵌套解析，并让 `TodoWriteTool` 消费 `List<Map<String,Object>>`。

## 架构分层

```
model/   ← 纯数据模型，无业务逻辑
api/     ← 接口/抽象类，定义契约
core/    ← 具体实现
exception/ ← 业务异常
util/    ← 通用工具
```

依赖方向：`core → api → model`、`* → util`（util 为最底层）。已按阿里规范 PRJ-01 治理：`Inbox`/`SessionStore` 契约下沉至 `api/agent/`，存储实现为 `core/session/InMemorySessionStore`；`api` 仅保留对 `core/session/Session` 的 **2 处明示豁免引用**（Session 是库内领域类型，待持久化时拆接口）。

## 关键技术决策

- 简化版 Cordis 语义：跳过 ScopedLayers、waterfall、emit/serial 分发
- Inbox 使用 `Message` 通用类型（非限定 `UserMessage`）
- ReactLoopAgent 跳过 `agent/pre-step/request/turn-stopping` 拦截器
- 系统测试使用 `@EnabledIfEnvironmentVariable(named = "DEEPSEEK_API_KEY", ...)` 门禁

## 阿里巴巴 Java 规范 Review

已做一次全量 review，高危/常见违规项已修复，其余作为建议记录：

### 已修复
- 移除所有 `java.util.*` 与 `dev.dsh.*` 通配 import，改为显式导入。
- `SessionStore.counter` 改为 `AtomicInteger`，修复并发创建会话 ID 的竞态条件。
- `BashTool` 使用 try-with-resources 关闭 `Process` 输入流；修正字节/字符截断判断。
- `BashTool`/`FileReadTool`/`FileWriteTool` 抽取工具参数 key 为常量。
- `DeepSeekAdapter` 用 `Collectors.joining()` 替代流内字符串拼接；修复 `forEach` 块缩进。
- `DeepSeekAdapter.extractJsonObject` 支持跳过字符串内的 `{`/`}`，避免 JSON 字符串值误匹配。
- `ReactLoopAgent` 移除冗余同包导入；`turn()` 内使用本地 `step` 避免重复读取 volatile `phase`。
- 修复多个源文件与测试文件中超长行（>120 字符）。

### 仍建议
- `ReactLoopAgent.cancel()` 目前只在 turn 边界生效，不会中断正在运行的 LLM 调用；如需即时取消，应保存 `CompletableFuture` 并调用取消/中断。
- `DeepSeekAdapter.extractJsonString` 仍是简单字符扫描，未处理字段名出现在 JSON 字符串值内的情况；当前 DeepSeek 响应结构可用，但建议后续替换为正规 JSON 库或增强扫描。
- 部分边界处直接 `catch (Exception e)` 后包成业务结果，符合工具执行边界场景，但内部异常建议保留栈跟踪日志。
- 测试方法名使用中文在阿里巴巴规范中无明确禁止，但建议后续新测试使用英文方法名 + `@DisplayName`。

## Git 历史

```
e397682 style(alibaba): 按阿里巴巴 Java 开发规范 review 并修复高危/常见违规项
28a4be1 docs: HANDOFF.md 记录工具参数解析修复
9cc9571 fix(tools): 修复工具参数解析——嵌套 JSON 被当成字符串导致工具执行失败
9d6477f docs: 更新 HANDOFF.md 状态与测试统计
2500c67 feat(tools): 添加 bash、file_read、file_write 工具
8aa69f9 fix(deepseek): 修复并验证工具调用解析
89352db feat(system-prompt)
7a2ffab feat(tools) + 工具执行集成
28d205a SystemPrompt 集成到 ReactLoopAgent
b515020 feat(deepseek): 真实 DeepSeek API 适配器
5edf04b refactor: 分层重构 model/api/core
34275d1 feat(agent-loop)
990cabf feat(agent)
304d1d5 feat(session)
0bd725d feat(llm)
```