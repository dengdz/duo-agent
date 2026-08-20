# 简介

duo-agent 是一个**零依赖的 Java 21 AI Agent SDK**。它让你用极少的代码创建能够调用工具、流式输出、推理思考的智能体（Agent），同时保留完整的底层控制能力。

## 核心特性

### 🚀 极简门面 API

Builder 模式 + 两种对话模式，从"3 行代码跑起来"到"完整观测 Agent 工作过程"逐级递进：

```java
agent.call("问题");                           // 同步：阻塞等待完整回答
agent.stream("问题");                         // 事件流：Flow.Publisher<SessionEvent> 全量事件透传
```

### 🔧 开箱即用的工具链

内置 8 种工具（bash、文件读写、grep/glob 搜索、精确编辑、todo、skill），5 组预设一键启用。模型通过 ReAct 循环自主决定何时调用工具：

```java
DuoAgent.builder()
        .model(model)      // DuoModel 实例（必填）
        .withCodeTools()   // bash + file_read + file_write + grep + glob + edit
        ...
```

### 📡 真流式、可观测

底层 SSE 全链路流式：模型的每一个 token 增量、每一次工具调用、每一步推理决策都以事件形式记录并实时可订阅——`stream()` 事件流全量透传，让你像看 IDE Agent 一样看到完整工作过程。

### 🧠 推理模型一等公民

DeepSeek-R1 等深度推理模型原生支持：独立的推理超时（默认 5 分钟）、思考过程与回答内容分离、不截断长推理输出。

### 🧩 事件溯源会话

对话历史是一个 append-only 事件日志（15 种事件类型，seq 严格连续），支持 JSONL 持久化、崩溃恢复、token 级回放。模型上下文与回放日志同源，不存在状态漂移。

### 🪶 零第三方依赖

纯 Java 21（仅 SLF4J API），手写 JSON 编解码，无任何框架绑定——Spring Boot、纯 Java、Vert.x、Quarkus 一视同仁。

## 适用场景

- **聊天机器人 / 对话式产品**——流式输出 + SSE 桥接直接对接前端
- **编程助手 / 代码 Agent**——工具链 + 事件流渲染完整工作过程
- **自动化任务**——bash/文件工具 + ReAct 循环自主完成任务
- **需要可审计的 AI 应用**——事件日志完整记录每一次决策与工具调用

## 设计哲学

| 原则 | 体现 |
|------|------|
| 事件溯源 | Session 日志是唯一事实源：持久化、回放、上下文派生全部同源 |
| 分层超时 | 连接 / HTTP / 应用层各司其职，推理模式自动放宽 |
| 扩展优于配置 | 4 类 Hook 覆盖全部关键路径，行为外挂不侵入核心 |
| 中立协议 | Flow.Publisher（Reactive Streams）对接所有响应式生态 |

## 下一步

→ [快速开始](quick-start.md)：5 分钟跑起第一个 Agent
