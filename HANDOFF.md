# Handoff: mp-agent (Java port of DeepSeek Harness)

## 项目状态

Java 21 + Maven 多模块项目，位于 `/Users/zhangyl/IdeaProjects/mp-agent`。已完成 deepseek-harness 核心管线的移植，**76 个测试，0 失败**（3 个真实 API 测试因缺少 `DEEPSEEK_API_KEY` 被跳过）。

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
| SessionStore | `core/session/SessionStore.java` | `packages/core/session/src/index.ts` | ✅ |
| SurfaceManager | `core/session/surface/SurfaceManager.java` | `packages/core/session/src/surface.ts` | ✅ |
| 会话事件类型 | `model/session/` (SessionEvent 系 13 个文件) | `packages/core/session/src/types.ts` | ✅ |
| Agent 接口 | `api/agent/Agent.java` | `packages/core/agent/src/runtime-types.ts` | ✅ |
| AgentRegistry | `api/agent/AgentRegistry.java` | `packages/core/agent/src/index.ts` | ✅ |
| Inbox | `core/agent/Inbox.java` | `packages/core/agent/src/inbox.ts` | ✅ |
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

依赖方向：`model → api → core`

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