# Duo Agent SDK 架构与目录规范

## 目录结构原则

SDK 采用**分层架构 + 垂直切分**的组织方式：

```
dev.duo
├── api/              # 公开 API 层（用户直接使用的接口）
│   ├── agent/        # Agent 相关 API（含 AgentHooks 扩展点集合）
│   ├── hook/         # 循环扩展点：PreStepHook/RequestHook/RequestErrorHook/ToolExecutionHook
│   └── llm/          # LLM 相关 API
├── core/             # 核心实现层（内部实现）
│   ├── agent/        # Agent 实现
│   ├── compaction/   # 压缩：估算/配对平衡/选区/压缩 hook（挂 PreStepHook）
│   ├── llm/          # LLM 核心逻辑（含内置 LlmRetryHook）
│   └── session/      # Session 管理（含 JSONL 持久化/事件编解码/崩溃修复）
├── adapter/          # 适配器层（外部系统集成）
│   └── deepseek/     # DeepSeek LLM 适配器（单次请求 + 结构化失败，重试走 hook）
├── tool/             # 工具层（Agent 可用的工具）
├── model/            # 数据模型层
│   ├── llm/          # LLM 相关模型
│   └── session/      # Session 相关模型
├── exception/        # 异常定义
└── util/             # 工具类
```

### 扩展点（hook）机制

新能力一律通过 `api/hook` 的四个拦截点外挂实现，不修改 `ReactLoopAgent`：
`PreStepHook`（step 进入决策，可拒绝/改写消息）、`RequestHook`（环绕请求构造，可改写
GenerateOptions）、`RequestErrorHook`（失败恢复决策，如重试）、`ToolExecutionHook`
（环绕工具执行，可实现审批/超时/审计）。链语义：先注册者在最外层，`chain.proceed()`
委托下游（最终是循环内置行为），不调用即接管/否决，`proceed()` 仅可调用一次；
hook 抛异常会传播并导致所在 step 失败（fail loud）。

第一个纯外挂核心能力是 `core/compaction` 的 `CompactionHook`（压缩）：step 间压力
触发（token 估算超阈值）→ 保尾选区（工具配对不拆散）→ 复用对话前缀的摘要调用 →
`SurfaceOp.Replace` 把选中表面范围替换为摘要 checkpoint（原事件保留在日志，回放保真）；
事务以 compaction/start 为持久锁、成败各落一条 compaction/end，失败不阻塞对话。

## 各层职责与扩展规范

### 1. `api/` - 公开 API 层
**职责**：SDK 用户直接使用的接口，是稳定的公开契约

**规范**：
- 只放接口（interface）、枚举、不可变记录（record）
- 按功能领域分包：`agent`、`llm`、`memory`、`planning` 等
- 不依赖 `core` 包，可依赖 `model` 和 `exception`
- 接口要稳定，变更需考虑向后兼容

**扩展示例**：
```
api/
├── agent/           # 现有：Agent、AgentFactory 等
├── llm/             # 现有：LlmAdapter、ToolRegistry 等
├── memory/          # 新增：记忆管理 API
│   ├── Memory.java
│   ├── MemoryStore.java
│   └── MemoryQuery.java
└── planning/        # 新增：规划能力 API
    ├── Planner.java
    ├── PlanStep.java
    └── PlanExecutor.java
```

### 2. `core/` - 核心实现层
**职责**：API 的默认实现，内部核心逻辑

**规范**：
- 实现 `api` 包中的接口
- 按功能领域分包，与 `api` 对应
- 包内可见（package-private）的类不对外暴露
- 可以互相依赖，但要避免循环依赖

**扩展示例**：
```
core/
├── agent/           # 现有：ReactLoopAgent
│   ├── ReactLoopAgent.java
│   └── PlanBasedAgent.java         # 新增：基于规划的 Agent
├── llm/             # 现有：SystemPromptImpl、ToolRegistryImpl
├── session/         # 现有：Session、InMemorySessionStore
├── memory/          # 新增：记忆实现
│   ├── VectorMemoryStore.java
│   └── SemanticMemory.java
└── planning/        # 新增：规划实现
    ├── ReActPlanner.java
    └── TreeOfThoughtPlanner.java
```

### 3. `adapter/` - 适配器层
**职责**：集成外部系统（LLM、向量数据库、搜索引擎等）

**规范**：
- 每个外部系统一个独立子包
- 实现 `api` 包中的适配器接口（如 `LlmAdapter`）
- 子包内部可以有多个辅助类
- 适配器之间完全独立，不互相依赖

**扩展示例**：
```
adapter/
├── deepseek/        # 现有：DeepSeek LLM 适配器
│   ├── DeepSeekAdapter.java
│   ├── DeepSeekRequestBuilder.java
│   ├── DeepSeekSseParser.java
│   └── DeepSeekJsonExtractor.java
├── openai/          # 新增：OpenAI 适配器
│   ├── OpenAiAdapter.java
│   └── OpenAiClient.java
├── anthropic/       # 新增：Anthropic 适配器
│   └── AnthropicAdapter.java
├── vectordb/        # 新增：向量数据库适配器
│   ├── pinecone/
│   │   └── PineconeVectorStore.java
│   └── weaviate/
│       └── WeaviateVectorStore.java
└── search/          # 新增：搜索引擎适配器
    ├── google/
    └── bing/
```

### 4. `tool/` - 工具层
**职责**：Agent 可以调用的工具实现

**规范**：
- 扁平结构，每个工具一个文件
- 工具类命名：`XxxTool.java`
- 实现统一的工具接口（可以在 `api.llm` 中定义 `Tool` 接口）
- 工具之间独立，不互相依赖

**扩展示例**：
```
tool/
├── BashTool.java           # 现有：执行 Shell 命令
├── FileReadTool.java       # 现有：读取文件
├── FileWriteTool.java      # 现有：写入文件
├── TodoWriteTool.java      # 现有：TODO 管理
├── WebSearchTool.java      # 新增：网页搜索
├── CodeAnalysisTool.java   # 新增：代码分析
├── DatabaseQueryTool.java  # 新增：数据库查询
└── HttpRequestTool.java    # 新增：HTTP 请求
```

**工具太多时可以分类**：
```
tool/
├── file/           # 文件操作类
│   ├── FileReadTool.java
│   ├── FileWriteTool.java
│   └── FileSearchTool.java
├── system/         # 系统操作类
│   ├── BashTool.java
│   └── EnvVarTool.java
├── web/            # 网络操作类
│   ├── WebSearchTool.java
│   └── HttpRequestTool.java
└── data/           # 数据处理类
    ├── DatabaseQueryTool.java
    └── JsonParseTool.java
```

### 5. `model/` - 数据模型层
**职责**：数据传输对象（DTO）、值对象（Value Object）

**规范**：
- 优先使用不可变的 Java Record
- 按业务领域分包：`llm`、`session`、`memory`、`planning` 等
- 只包含数据，不包含业务逻辑
- 可以被任何层依赖

**扩展示例**：
```
model/
├── llm/            # 现有：Message、ToolDefinition 等
├── session/        # 现有：SessionEvent、TodoItem 等
├── memory/         # 新增：记忆相关模型
│   ├── MemoryEntry.java
│   ├── MemoryVector.java
│   └── MemoryMetadata.java
└── planning/       # 新增：规划相关模型
    ├── Plan.java
    ├── PlanNode.java
    └── ExecutionResult.java
```

### 6. `exception/` - 异常层
**职责**：自定义异常类

**规范**：
- 扁平结构，除非异常特别多
- 异常命名：`XxxException.java`
- 继承适当的父异常（RuntimeException 或 Exception）

**扩展示例**：
```
exception/
├── AgentCreationException.java    # 现有
├── AgentLoopException.java        # 现有
├── LlmException.java              # 现有
├── SessionException.java          # 现有
├── MemoryException.java           # 新增
├── PlanningException.java         # 新增
└── ToolExecutionException.java    # 新增
```

### 7. `util/` - 工具类层
**职责**：通用工具类、辅助方法

**规范**：
- 扁平结构，纯静态方法或无状态类
- 命名清晰，如：`JsonParser`、`StringUtils`、`DateUtils`
- 不依赖业务逻辑

**扩展示例**：
```
util/
├── CallId.java              # 现有
├── JsonParser.java          # 现有
├── MessageId.java           # 现有
├── HttpUtils.java           # 新增：HTTP 工具
├── VectorUtils.java         # 新增：向量计算
└── PromptTemplateEngine.java # 新增：提示模板引擎
```

## 典型扩展场景

### 场景 1：添加新的 Agent 策略（如：Plan-and-Execute）

```
1. api/agent/PlanBasedAgentOptions.java    # 定义配置选项
2. core/agent/PlanBasedAgent.java          # 实现 Agent 接口
3. core/planning/ReActPlanner.java         # 规划器实现
4. model/planning/Plan.java                # 规划数据模型
5. exception/PlanningException.java        # 规划异常
```

### 场景 2：添加新的 LLM 提供商（如：OpenAI）

```
1. adapter/openai/OpenAiAdapter.java       # 实现 LlmAdapter 接口
2. adapter/openai/OpenAiClient.java        # HTTP 客户端
3. adapter/openai/OpenAiConfig.java        # 配置类（可选）
```

### 场景 3：添加记忆能力（Memory）

```
1. api/memory/Memory.java                  # 记忆 API 接口
2. api/memory/MemoryStore.java             # 存储接口
3. core/memory/SemanticMemory.java         # 语义记忆实现
4. core/memory/EpisodicMemory.java         # 情节记忆实现
5. adapter/vectordb/pinecone/              # 向量数据库适配器
6. model/memory/MemoryEntry.java           # 记忆条目模型
```

### 场景 4：添加新工具

```
tool/WebSearchTool.java                    # 单个文件即可
```

## 依赖关系图

```
┌─────────┐
│   api   │ ◄─── 用户直接使用
└────┬────┘
     │
     ▼
┌─────────┐      ┌──────────┐
│  core   │ ◄──► │  model   │
└────┬────┘      └────┬─────┘
     │                │
     │                │
     ▼                ▼
┌─────────┐      ┌───────────┐
│ adapter │      │   tool    │
└─────────┘      └───────────┘
     │                │
     └────────┬───────┘
              ▼
         ┌─────────┐
         │  util   │
         └─────────┘
              ▲
              │
         ┌─────────┐
         │exception│
         └─────────┘
```

**依赖规则**：
- `api` 只依赖 `model` 和 `exception`
- `core` 可以依赖 `api`、`model`、`exception`、`util`
- `adapter` 可以依赖 `api`、`model`、`exception`、`util`
- `tool` 可以依赖 `api`、`model`、`exception`、`util`
- `model` 只依赖 `model`（内部）和 `exception`
- `exception` 不依赖任何业务包
- `util` 不依赖任何业务包

## 扩展清单（Checklist）

添加新功能前，问自己：

1. **这是公开 API 吗？** → 放 `api/`
2. **这是 API 的实现吗？** → 放 `core/`
3. **这是外部系统集成吗？** → 放 `adapter/`
4. **这是 Agent 的工具吗？** → 放 `tool/`
5. **这是数据模型吗？** → 放 `model/`
6. **这是自定义异常吗？** → 放 `exception/`
7. **这是通用工具类吗？** → 放 `util/`

## 命名规范

- **接口**：名词，如 `Agent`、`Memory`、`Planner`
- **实现类**：接口名 + 策略/技术，如 `ReactLoopAgent`、`VectorMemoryStore`
- **适配器**：服务商名 + Adapter，如 `DeepSeekAdapter`、`OpenAiAdapter`
- **工具类**：功能 + Tool，如 `WebSearchTool`、`FileReadTool`
- **模型类**：领域名词，如 `Message`、`MemoryEntry`、`Plan`
- **异常类**：领域 + Exception，如 `LlmException`、`MemoryException`

## 何时创建新包

- **功能相关的类 ≥ 3 个**：创建独立子包
- **功能相关的类 < 3 个**：放在父包扁平结构中
- **适配器的辅助类**：放在适配器子包内，不提升到 `adapter/` 根目录

## 保持清晰的要点

1. **职责单一**：每个包有明确的职责
2. **层次分明**：API → Core → Adapter/Tool 三层结构
3. **低耦合**：适配器之间、工具之间完全独立
4. **易扩展**：新增功能不破坏现有结构
5. **易理解**：新人能快速找到代码位置
