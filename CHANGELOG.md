# Changelog

本文档记录 duo-agent SDK 的版本变更历史。

格式基于 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.0.0/)，
版本号遵循 [语义化版本 2.0.0](https://semver.org/lang/zh-CN/)。

---

## [0.4.0] - 2026-08-21

### Added
- **cancel() 门面方法**：`DuoAgent` 新增 `cancel(cause)` 和 `cancel(cause, options)` 方法，无需通过 `getAgent()` 访问
- **取消指南**：新增 `docs/02-guide/cancellation.md` 完整取消机制文档
- **术语表**：新增 `docs/05-reference/glossary.md` 核心概念速查

### Changed
- **文档完善**：修复 README 自定义工具示例，同步文档站 sidebar 配置
- **引用清理**：移除全部实现来源引用（71 个 JavaDoc 文件）
- **双通道取消机制**：显式 CancellationSignal + 驱动线程 interrupt 组合，支持协作式取消

### Removed
- **ADR 引用**：删除本地架构决策文档引用，添加 `.gitignore` 过滤规则

---

## [0.3.0] - 2026-08-21

### Added
- **多厂商适配**：支持 OpenAI（Chat Completions）、Anthropic（Messages）、阿里云百炼（Responses）三种 API 格式
- **DuoModel 抽象层**：统一的模型接口，按 API 格式分类而非按厂商
- **多厂商指南**：新增 `docs/02-guide/multi-provider.md` 接入文档

### Changed
- **协议分类**：Model 类型系统按 API 格式切分（openai/anthropic/responses），厂商只是配置值
- **适配器重构**：每个 API 格式独立适配器（OpenAIAdapter、AnthropicAdapter、ResponsesAdapter）

---

## [0.2.0] - 2026-08-20

### Added
- **两层 API 重构**：引入 `DuoAgent`（门面 API）和 `Agent`（底层 API）双层设计
- **DuoModel 模型抽象**：统一的 LLM 调用接口，隔离厂商差异
- **Builder 模式**：`DuoAgent.builder()` 简化 Agent 创建流程

### Changed
- **API 分层**：公开 API (`api/`) 与核心实现 (`core/`) 分离，提升扩展性
- **Hook 机制标准化**：PreStepHook、RequestHook、RequestErrorHook、ToolExecutionHook 四类扩展点

### Breaking Changes
- Agent 创建方式从直接构造改为 Builder 模式

---

## [0.1.0] - 2026-08-20

首个正式版本发布。

### Added
- **基础对话 API**：`call()`（同步）和 `stream()`（流式）
- **事件溯源**：Session 日志作为唯一事实源，支持完整历史追溯
- **ReAct 循环**：开箱即用的 ReAct 模式 Agent 实现
- **内置工具**：
  - `BashTool`：执行 Shell 命令
  - `ReadTool`：读取文件
  - `WriteTool`：写入文件
  - `EditTool`：精确字符串替换编辑
  - `GrepTool`：内容搜索
  - `GlobTool`：文件名搜索
  - `SkillTool`：动态 Skill 加载
- **会话管理**：
  - JSONL 持久化（`JsonlSessionPersistence`）
  - 崩溃恢复（`InterruptedTurnRepair`）
  - Inbox 消息队列（next-turn / next-step）
- **上下文压缩**：`CompactionHook` 自动压缩历史消息
- **LLM 重试**：`LlmRetryHook` 自动重试失败请求
- **推理模型支持**：`reasoningEnabled` 和 `reasoningTimeout` 配置
- **响应式流**：基于 Java 9 Flow API 的流式响应（保持零依赖）
- **事件流**：`chatEvents()` 多事件流透传 Agent 工作过程
- **文档站**：VitePress 文档网站（19 篇文档，5 章节结构）

### Technical
- **零三方依赖**：仅依赖 Java 11+ 标准库和 SLF4J
- **自实现 JSON 解析**：无需 Jackson/Gson
- **自实现 HTTP 客户端**：基于 Java 11+ HttpClient
- **线程安全**：所有公共 API 线程安全
- **测试覆盖**：297+ 测试用例

---

## 版本说明

### 版本号规则

- **0.x.x**：早期开发版本，API 可能变化，不保证向后兼容
- **1.0.0+**：稳定版本，遵循语义化版本，保证向后兼容

### Breaking Changes 标记

带有 `!` 标记的提交（如 `feat(sdk)!:`）表示包含破坏性变更（Breaking Changes），升级时需注意 API 兼容性。

---

## 参考链接

- [快速开始](docs/01-getting-started/quick-start.md)
- [API 文档](docs/02-guide/chat-api.md)
- [架构设计](docs/04-architecture/overview.md)
- [GitHub 仓库](https://github.com/zhangyl/duo-agent)
