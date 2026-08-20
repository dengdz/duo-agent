# 上下文压缩

长对话会不断膨胀模型上下文，最终超出窗口限制导致请求失败。`CompactionHook` 在 step 之间自动检测压力并把较早的历史**替换为一条摘要**——对话可以无限继续。

> ⚠️ 内置 Hook **不自动注册**。由于 `CompactionHook` 需要 `llmRuntime` 与 `systemPrompt` 的引用（摘要调用复用它们以保持前缀缓存有效），而门面 Builder 不暴露这两个内部组件，因此**压缩需手动组装底层 `ReactLoopAgent`**（而非 `DuoAgent.builder()`）。完整可运行代码参见示例 `CompactionRealExample`，要点：

```java
var systemPrompt = new SystemPromptImpl("你是一个智能助手", false);
var llmRuntime = new LlmRuntime();
llmRuntime.registerAdapter("openai", new DeepSeekAdapter(apiKey, baseUrl));

// hooks 经 AgentOptions 传入（ReactLoopAgent 6 参构造器）
var agentOptions = new AgentOptions(
        "openai", "deepseek-chat", null, null,
        AgentHooks.builder()
                .addPreStepHook(new CompactionHook(
                        llmRuntime,                      // 摘要用的 LLM 运行时
                        systemPrompt,                    // 复用的系统提示（保持前缀缓存有效）
                        "openai",                        // 摘要 provider
                        "deepseek-chat",                 // 摘要 model
                        new CompactionConfig(
                                24000,                   // thresholdTokens：触发阈值
                                8000,                    // retainTokens：保留尾部下限
                                2,                       // maxAttempts：一次触发最多压缩次数
                                Duration.ofSeconds(60)))) // summarizationTimeout：摘要超时
                .build());

var agent = new ReactLoopAgent(
        new SessionId("agent-1"), agentOptions, session,
        llmRuntime, systemPrompt, toolRegistry);
```

## 工作原理

```
对话进行中（表面 token 持续增长）
        │
        ▼  step 间隙检测：表面估算 token ≥ thresholdTokens
        │
        ▼  选定压缩区间 [开头, 中段切点]
        │     · 保留尾部至少 retainTokens
        │     · 切点不拆散工具配对（call 与 result 不分离）
        │
        ▼  LLM 摘要：复用对话自身的 system prompt 与消息前缀
        │     （不使前缀缓存失效）
        │
        ▼  写入摘要 checkpoint 消息，SurfaceOp.Replace 替换区间
        │     · 原始事件保留在日志中（事实源不变，仅模型可见表面收窄）
        │
        ▼  对话继续
```

### 关键设计

| 设计 | 说明 |
|------|------|
| **摘要复用上下文前缀** | 摘要请求使用与原对话相同的 system prompt 和消息前缀——DeepSeek 的前缀缓存不被打断，摘要调用更便宜 |
| **工具配对保护** | 压缩区间边界不会切断 `tool/call` 与 `tool/result` 的配对（切点自动前移到平衡位置） |
| **事务化** | 以 `compaction/start` / `compaction/end` 事件包裹，持久化日志可审计 |
| **失败放行** | 摘要 LLM 调用失败**只记录并放行**——压缩是优化，不阻塞对话继续 |

## 配置说明

| 参数 | 建议 |
|------|------|
| `thresholdTokens` | 模型上下文窗口的 60~80%（给单次请求留余量） |
| `retainTokens` | 保留最近对话的完整度：太小丢上下文、太大压缩收益低 |
| `maxAttempts` | 一次触发最多循环压缩几轮（长历史可能一轮压不够） |
| `summarizationTimeout` | 摘要调用超时，默认 60 秒 |

## 事件可观测

压缩过程在 `chatEvents()` 中完整可见：

```
compaction/start   (compactionId, turn)
compaction/end     (compactionId, turn, error?)
```

同时被压缩区间的 `user/message`（摘要 checkpoint）携带 `SurfaceOp.Replace(startSeq, endSeq)`——`deriveMessages()` 派生模型上下文时自动应用替换。

## 示例

- `CompactionExample`（无需 API Key）：Mock 场景演示攒历史 → 触发 → 表面被摘要替换
- `CompactionRealExample`（需 API Key）：真实 DeepSeek 对话 + 真实 LLM 摘要全流程
