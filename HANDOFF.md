# Handoff: mp-agent (Java port of DeepSeek Harness)

## 项目状态

Java 21 + Maven 多模块项目，位于 `/Users/zhangyl/IdeaProjects/mp-agent`。已完成 deepseek-harness 核心管线的移植，**67 个测试，0 失败**（3 个真实 API 测试因缺少 `DEEPSEEK_API_KEY` 被跳过）。

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
4. **真实 API 回归测试**：需要 `DEEPSEEK_API_KEY` 环境变量运行 `工具调用往返测试`

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

## Git 历史

```
89352db feat(system-prompt)
7a2ffab feat(tools) + 工具执行集成
28d205a SystemPrompt 集成到 ReactLoopAgent
b515020 feat(deepseek): 真实 DeepSeek API 适配器
5edf04b refactor: 分层重构 model/api/core
1495755 refactor: 阿里巴巴规范修复
34275d1 feat(agent-loop)
990cabf feat(agent)
304d1d5 feat(session)
0bd725d feat(llm)
```