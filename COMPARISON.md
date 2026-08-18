# duo-agent vs deepseek-harness 功能对比

> 生成时间：2026-08-18
> 对比范围：当前 `/Users/zhangyl/IdeaProjects/duo-agent` 已实现模块 与 `/Users/zhangyl/IdeaProjects/deepseek-harness` 参考实现

## 总体结论

**duo-agent 是 deepseek-harness 的简化版 Java 移植，核心对话-工具循环已跑通，但大量周边能力尚未实现。**

当前 Java 版本主要保留了：
- 基础 LLM 抽象与 DeepSeek 适配器
- Session 事件日志（仅内存，无持久化）
- ReAct Agent 循环（无拦截器、无 scope/依赖注入）
- 基础工具注册与执行（顺序执行，无并行/排他/取消）
- 简易 System Prompt
- 几个示例工具（todo_write、bash、file_read、file_write）

原版 deepseek-harness 的 Cordis 插件体系、waterfall 拦截器、scope 分层、持久化、Web UI、Code Mode、retry/token-meter 等均未移植。

---

## 模块对比

| 模块 | 原版 deepseek-harness | 当前 duo-agent | 一致性 |
|---|---|---|---|
| **LLM 词汇表** | `packages/llm/llm/src/types.ts`：完整 ContentBlock/FinishReason/TokenUsage/StreamChunk/ToolSchema/GenerateOptions，支持 declaration merging 扩展 | `model/llm/`：核心类型已移植，但无扩展机制，无 image、无 reasoning block | ⚠️ 核心对齐，能力裁剪 |
| **Message 模型** | `packages/llm/llm/src/message.ts`：不可变消息、MessageId、MessageSource（user/plugin/model/tool）、form 概念 | `model/llm/Message.java` + `MessageFactory.java`：保留 role/content/source，但 source 简化，无 form/插件消息 | ⚠️ 基本对齐，功能简化 |
| **BlockAssembler** | `packages/llm/llm/src/assembler.ts`：完整增量组装、max-tokens 丢弃 tool call、stray delta 忽略 | `core/llm/BlockAssembler.java`：算法一致，支持 text/reasoning/tool-call delta、usage/finish | ✅ 核心对齐 |
| **LlmRuntime** | `packages/llm/llm/src/index.ts`：注册适配器、模型发现、prepareCall、stream、waterfall 拦截 | `api/llm/LlmRuntime.java`：仅 `stream` 入口，无注册表/发现/拦截 | ⚠️ 概念对齐，实现极简化 |
| **LlmAdapter** | 抽象类，默认方法最小化，子类覆盖 stream | `api/llm/LlmAdapter.java`：仅 `stream` 接口 | ✅ 基本对齐 |
| **DeepSeekAdapter** | `packages/llm/llm-deepseek/`：fetch + SSE、错误码映射、idle watchdog、序列化、翻译、配置热更新 | `core/llm/deepseek/DeepSeekAdapter.java`：HttpClient + SSE、基础解析、无错误码分类、无 watchdog、无配置热更新 | ⚠️ 协议对齐，鲁棒性不足 |
| **Session 事件日志** | `packages/core/session/src/index.ts`：完整 event log、seq、seed、fork、repair、chunk-rows 压缩、JSON 校验 | `core/session/Session.java`：仅 append-only 内存日志，无 seed/fork/repair/压缩/校验 | ⚠️ 概念对齐，能力大幅简化 |
| **SessionStore** | 管理 live session：create/prepare/enter/announce/fork/flush | `core/session/SessionStore.java`：仅 create/get/list/remove，内存 ConcurrentHashMap | ⚠️ 概念对齐，功能裁剪 |
| **SurfaceManager** | `packages/core/session/src/surface.ts`：维护 model-visible surface，支持 append/replace | `core/session/SurfaceManager.java`：仅维护 append-only surface，无 replace | ⚠️ 部分对齐 |
| **会话事件类型** | 13+ 事件类型，包含 request/header、request/context、todo/write、session/end-seed 等 | `model/session/`：已移植 13 个事件，但无 request/header/context/end-seed | ⚠️ 名称对齐，部分事件缺失 |
| **Agent 接口** | `packages/core/agent/src/runtime-types.ts`：Agent 接口 + 大量 Cordis 事件（agent/created、pre-step、request、request-error、turn-stopping 等） | `api/agent/Agent.java`：仅核心生命周期方法，无事件/拦截器扩展点 | ⚠️ 接口对齐，事件体系缺失 |
| **AgentRegistry** | `packages/core/agent/src/index.ts`：完整注册表、initiator scope (AsyncLocalStorage) | `api/agent/AgentRegistry.java` + `core/agent/AgentRegistryImpl.java`：简单 map 注册，无 scope | ⚠️ 概念对齐，无 scope |
| **Inbox** | `packages/core/agent/src/inbox.ts`：支持 append/prepend/replace/remove/splice，持久化到 session | `core/agent/Inbox.java`：仅 append/claim/clear，无持久化 splice | ⚠️ 核心功能对齐，高级操作缺失 |
| **ReactLoopAgent** | `packages/core/agent-loop/src/agent.ts`：完整 turn/step、pre-step/request waterfall、cancel、maintenance | `core/agent/ReactLoopAgent.java`：基础 turn/step 循环，无拦截器，cancel 仅 turn 边界生效 | ⚠️ 骨架对齐，扩展点缺失 |
| **工具调用执行** | `packages/core/agent-loop/src/tool-calls.ts`：顺序/并行/排他执行、maxParallel、abort 处理、additionalContexts | `ReactLoopAgent.executeStep()`：顺序执行，无并行/排他/取消/附加上下文 | ⚠️ 基础流程对齐，高级调度缺失 |
| **SystemPrompt** | `packages/core/system-prompt/src/index.ts`：scope 分层 section、动态 context、waterfall、变量插值、code mode | `api/llm/SystemPrompt.java` + `core/llm/SystemPromptImpl.java`：静态 prompt + 工具 schema 拼接，无 scope/waterfall/变量 | ⚠️ 概念对齐，功能极简 |
| **ToolRegistry** | `packages/core/tools/src/index.ts`：完整 ToolRuntime，scope shadow、guard/restrict、approval、code mode、around 拦截 | `api/llm/ToolRegistry.java` + `core/llm/ToolRegistryImpl.java`：简单 name→definition map，直接执行 | ⚠️ 概念对齐，执行管线缺失 |
| **工具 schema** | `packages/core/tools/src/schema.ts`：完整 DSL、参数校验、输出 schema、InferArgs | `model/llm/ToolDefinition.java`：仅 name/description/schema/executor，无校验/输出 schema | ⚠️ 概念对齐，验证缺失 |
| **TodoWriteTool** | `packages/todo/tool-todo/src/index.ts`：向 session 发送 todo/write 事件，支持 sessionProjections 投影 | `core/llm/tools/TodoWriteTool.java`：仅内存 list，无 session 事件/投影 | ⚠️ 行为相似，集成度不同 |
| **BashTool / FileTool** | 原版 terminal/bash/file 能力（具体包未在本次探索中详细展开） | 已自行实现 BashTool、FileReadTool、FileWriteTool | ⚠️ 能力对齐，实现独立 |
| **Retry / TokenMeter** | `packages/llm/llm-retry`、`packages/llm/token-meter` | 无 | ❌ 未实现 |
| **持久化** | JSONL/SQLite/ChunkRows 压缩 | 无 | ❌ 未实现 |
| **Web UI** | 前端界面 | 无 | ❌ 未实现 |
| **Code Mode** | 工具 code 模式、SDK 生成、code runtime | 无 | ❌ 未实现 |
| **Scope / DI** | Cordis scope、插件生命周期、waterfall | 无 | ❌ 未实现 |

---

## 关键差异详解

### 1. 架构基础设施：Cordis 缺失

原版基于 Cordis 框架，具备：
- 插件化生命周期（`apply(ctx, config)`）
- Scope 分层与依赖注入
- `waterfall` / `serial` / `emit` 事件拦截
- 配置 schema（Schemastery）
- 反向销毁链

duo-agent 完全跳过这些：
- 无插件体系
- 无 DI 容器
- 无拦截器扩展点
- 配置通过环境变量或代码硬编码

**影响**：`agent/pre-step`、`agent/request`、`agent/request-error`、`agent/turn-stopping` 等 waterfall 事件未实现，HANDOFF.md 中列为未完成工作。

### 2. Session 事件日志：仅内存，无持久化

原版：
- 所有事件序列化验证后写入日志
- 支持 fork、seed、repair
- `chunk-rows.ts` 压缩连续同类型 delta
- `json.ts` 验证无损 JSON

duo-agent：
- `Session` 只是内存中的 `List<SessionEvent>`
- 无 JSONL/SQLite 后端
- 无 seed/fork/repair
- 每次进程重启丢失历史

### 3. 工具执行管线：简单直接 vs 完整调度

原版 `ToolRuntime`：
- 支持 `executionMode`：parallel / exclusive / sequential
- `maxParallelToolCalls` 限制
- `tools/pre-execute`、`tools/execute`、`tools/post-execute` around 拦截
- 取消时 drain 已启动调用，合成未启动调用的错误结果
- 输出 schema 校验与呈现

duo-agent `ToolRegistryImpl`：
- 简单 `Map<String, ToolDefinition>`
- `execute(name, args)` 直接调用 `tool.executor().apply(args)`
- 顺序执行，无并行、无取消、无拦截、无输出校验

### 4. System Prompt：静态 vs 动态组装

原版：
- 按 scope 注册 prompt section
- `system-prompt/assemble` waterfall
- 变量插值 `{{variable}}`
- `code` 模式注入 SDK/折叠声明

duo-agent：
- 构造时传入固定 prompt 字符串
- 把 tools schema 拼接进去
- 无变量、无 waterfall、无 scope、无 code 模式

### 5. DeepSeek 适配器：功能子集

原版：
- 错误码映射（AUTH、RATE_LIMIT、QUOTA_EXCEEDED、SERVER）
- idle watchdog 处理流空闲超时
- `resolveApiKey` 从 credentials/env 读取
- `stream_options.include_usage`
- 配置热更新（`registration.replace`）

duo-agent：
- HttpClient 长连接 SSE
- 手动 parseChunk 解析 JSON
- 仅检查 HTTP 200
- API key 从环境变量读取
- 无 watchdog、无错误码分类、无热更新

### 6. 额外实现但未参考原版的模块

duo-agent 自行添加了：
- `dev.dsh.util.JsonParser`：零依赖 JSON 解析器（原版用标准 JSON.parse）
- `BashTool` / `FileReadTool` / `FileWriteTool`：功能参考了原版能力，但独立实现
- 阿里巴巴 Java 规范 review 修复

---

## 当前是否“按参考实现”的判定

| 维度 | 判定 |
|---|---|
| **核心概念与数据结构** | ✅ 是。StreamChunk、ContentBlock、FinishReason、Message、SessionEvent、Agent、ToolRegistry 等概念与命名基本来自 deepseek-harness |
| **分层架构** | ✅ 是。`model/api/core/util/exception` 的分层与原版 `packages/llm`、`packages/core` 的职责对应 |
| **ReAct 对话-工具循环** | ✅ 是。turn/step、inbox、工具结果回注 next-step 的逻辑与原版一致 |
| **完整功能覆盖** | ❌ 否。Cordis 基础设施、持久化、拦截器、Web UI、Code Mode、retry、token-meter 等均未实现 |
| **实现质量** | ⚠️ 基础功能已验证（76 tests / 0 failures），但鲁棒性、扩展性、持久化能力远不及原版 |

---

## 建议的后续方向

如果目标是**忠实复刻 deepseek-harness**：
1. 补齐 `agent/pre-step`、`agent/request`、`agent/request-error`、`agent/turn-stopping` 拦截器体系
2. 实现 Session 持久化（JSONL + SQLite）与 `chunk-rows` 压缩
3. 实现 `ToolRuntime` 的并行/排他执行与取消语义
4. 增强 `SystemPrompt` 的 scope/waterfall/变量能力
5. 补齐 DeepSeekAdapter 的错误码、watchdog、配置热更新
6. 引入 model discovery、retry、token-meter 等 LLM 周边能力

如果目标是**保留一个最小可运行的 Java Agent 内核**：
- 当前状态已可用，可继续优先做 Session 持久化和 Web UI
