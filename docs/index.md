---
layout: home

hero:
  name: duo-agent
  text: 零依赖的 Java 21 AI Agent SDK
  tagline: 几行代码创建能调用工具、流式输出、完整可观测的智能体
  actions:
    - theme: brand
      text: 快速开始
      link: /01-getting-started/quick-start
    - theme: alt
      text: 简介
      link: /01-getting-started/introduction
    - theme: alt
      text: GitHub
      link: https://github.com/dengdz/duo-agent

features:
  - icon: 🚀
    title: 四种对话模式
    details: chat / chatAsync / stream / chatEvents——从同步阻塞到完整事件流逐级递进，前端可渲染完整 Agent 工作过程
  - icon: 🔧
    title: 开箱即用的工具链
    details: bash、文件读写、grep/glob 搜索、精确编辑等 8 种内置工具，5 组预设一键启用，ReAct 循环自主调度
  - icon: 📡
    title: 真流式、可观测
    details: SSE 全链路流式，15 种会话事件（token 级增量、工具往返、思考过程）实时可订阅
  - icon: 🧠
    title: 推理模型一等公民
    details: DeepSeek-R1 原生支持：独立推理超时、思考过程与回答分离、不截断长推理输出
  - icon: 🧩
    title: 事件溯源会话
    details: append-only 事件日志唯一事实源，JSONL 持久化、崩溃恢复、token 级回放
  - icon: 🪶
    title: 零第三方依赖
    details: 纯 Java 21（仅 SLF4J API），不绑定任何框架——Spring Boot、纯 Java、Vert.x 一视同仁
---

## 快速开始

```java
var agent = DuoAgent.builder()
        .apiFormat("openai")
        .baseUrl("https://api.deepseek.com")
        .apiKey(System.getenv("DEEPSEEK_API_KEY"))
        .model("deepseek-chat")
        .contextWindow(128000)
        .withCodeTools()
        .build();

String response = agent.chat("列出当前目录的 Java 文件");
```

→ [5 分钟上手教程](01-getting-started/quick-start.md)

## 文档导航

| 章节 | 内容 |
|------|------|
| [入门](01-getting-started/introduction.md) | duo-agent 是什么、核心特性、适用场景 |
| [指南](02-guide/chat-api.md) | 对话 API、流式、工具、自定义工具、推理模型、SSE 桥接 |
| [高级](03-advanced/hooks.md) | Hook 扩展、自动重试、上下文压缩、持久化、Skill 系统 |
| [架构](04-architecture/overview.md) | 分层设计、事件溯源、ReAct 循环 |
| [参考](05-reference/events.md) | 事件类型速查、已知限制与路线图 |

## 更多资源

- [项目 README](https://github.com/dengdz/duo-agent) —— 特性亮点
- [示例程序](https://github.com/dengdz/duo-agent/tree/main/duo-agent-example) —— 16 个可运行示例
- 232 个单元测试覆盖全部核心功能
