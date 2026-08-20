# LLM 自动重试

`LlmRetryHook` 是内置的 request-error Hook：网络抖动、限流、服务端错误时自动重试，指数退避并尊重服务端的 `Retry-After` 指示。

> ⚠️ 内置 Hook **不自动注册**，需要显式挂载：

```java
var builder = DuoAgent.builder()
        /* ...基础配置... */;

builder.hooks().addRequestErrorHook(new LlmRetryHook());   // 显式启用重试

var agent = builder.build();
```

## 重试策略

| 维度 | 行为 |
|------|------|
| **可重试判定** | HTTP 5xx、429（限流）、TIMEOUT、TRANSPORT（网络故障） |
| **退避算法** | 指数退避：500ms 起、每次 ×2（因子可配） |
| **抖动** | 每次退避乘以 0.5 ~ 1.5 的随机因子（防止并发风暴） |
| **Retry-After** | 服务端返回 `Retry-After` 时，实际等待取 `max(计算退避, Retry-After)`（视为下限） |
| **重试上限** | 默认 3 次（可配置） |
| **计数隔离** | 按 (agent, turn, step) 粒度独立计数 |

```java
// 默认：3 次重试、500ms 起、×2 退避
new LlmRetryHook();

// 自定义：最多 5 次、1s 起、×3 退避
new LlmRetryHook(5, Duration.ofSeconds(1), 3.0);
```

> 💡 **实例共享注意**：`LlmRetryHook` 内部按 agentId 维护连续失败计数——同一实例可安全挂到多个 Agent，但不要在每次请求时新建（计数会失效）。

## 与循环层上限的关系

ReactLoopAgent 对每个 step 有 **10 次重试的硬上限**（防止 Hook 配置错误导致无限重试）。`LlmRetryHook` 的重试计数耗尽或不可重试错误直接 `Fail`，step 失败并体现在 `turn/end` 事件的 `Error` 原因中。

## 失败可见性

重试过程在日志中留痕（`Agent ... 请求失败（...），第 N 次重试`），最终失败：

- `chat()` → `IllegalStateException`
- `chatEvents()` → `turn/end` 事件 `reason=Error(LlmFailure)`，含结构化错误码

## 什么不值得重试

4xx（除 429）如 401 认证失败、400 参数错误——重试同样会失败，`LlmRetryHook` 直接放弃，错误快速返回。
