# Hook 扩展点

Hook 是 duo-agent 的行为扩展机制：不修改核心循环，在关键路径上外挂你的逻辑。四类 Hook 分别覆盖"进入 step 前 / 构造请求时 / 请求失败后 / 工具执行时"四个切面。

## 统一语义

| 规则 | 说明 |
|------|------|
| 链式调用 | 同类 Hook 按注册序组成链（先注册者在外层），waterfall 模式 |
| `proceed()` 恰好一次 | Hook 必须调用一次 `chain.proceed(...)` 进入下一层；不调用即**接管**（短路） |
| 异常 fail-loud | Hook 抛异常直接导致当前 step 失败（不静默吞掉） |

## 注册方式

`builder.hooks()` 返回共享的 `AgentHooks.Builder`（挂载点在 Builder 内部，无需回传）：

```java
var builder = DuoAgent.builder()
        /* ...基础配置... */;

builder.hooks()                                   // 返回共享的 AgentHooks.Builder
       .addPreStepHook(new MyCompactionHook())
       .addRequestErrorHook(new LlmRetryHook())
       .addToolHook(new ApprovalHook());

var agent = builder.build();                      // hooks 随 build 一并组装
```

## 四类 Hook

### 1. PreStepHook（step 进入前）

**时机**：每个 step 认领输入之后、调用模型之前。

```java
public class MyPreStepHook implements PreStepHook {
    @Override
    public PreStepDecision decide(PreStepContext ctx, Chain chain) throws Exception {
        // ctx.messages()：本 step 已认领的消息；ctx.session()：会话（可读日志与表面）
        var decision = chain.proceed();                    // 下游决策（默认：原样进入）
        if (decision instanceof PreStepDecision.Enter enter) {
            // 可改写进入的消息批次（如注入提示、裁剪上下文）
            return new PreStepDecision.Enter(enter.messages());
        }
        return decision;
    }
}
```

| 能力 | 用途 |
|------|------|
| 改写进入 step 的消息批次 | 上下文压缩（内置 `CompactionHook` 就是这个） |
| 返回 `new PreStepDecision.Reject(...)` 拒绝 | turn 以 `Blocked` 结束，**不消耗模型调用** |

### 2. RequestHook（请求构造）

**时机**：每次模型请求组装时。**不能改历史消息**，只能改写 `GenerateOptions`。

```java
public class ModelSwitchHook implements RequestHook {
    @Override
    public GenerateOptions onRequest(RequestContext ctx, Chain chain) throws Exception {
        var o = chain.proceed();                           // 默认组装的请求
        if (ctx.turn() > 5) {                              // 对话后期切换模型
            // record 不可变：按需重建（字段顺序同完整构造器）
            return new GenerateOptions(o.provider(), "deepseek-reasoner",
                    o.messages(), o.system(), o.tools(), o.temperature(),
                    o.maxTokens(), o.stop(), o.purpose(), o.reasoningEnabled());
        }
        return o;
    }
}
```

典型用途：按对话阶段动态切换模型、按 token 压力调整参数。

### 3. RequestErrorHook（失败恢复）

**时机**：LLM 调用失败时（网络错误、HTTP 5xx/429、超时）。

```java
public class MyRetryHook implements RequestErrorHook {
    @Override
    public RequestErrorAction onRequestError(RequestErrorContext ctx, Chain chain) throws Exception {
        // ctx.failure() 携带结构化失败（message/code/status）
        return chain.proceed();                    // 默认 Fail
        // 或：return new RequestErrorAction.Retry();   触发重试
    }
}
```

- 返回 `new RequestErrorAction.Retry()` → 循环重新派生消息、重构请求再试
- 返回 `chain.proceed()`（默认 `Fail`）→ step 失败
- 循环层有**每 step 10 次重试硬上限**，防止无限重试
- 内置实现：[`LlmRetryHook`](retry.md)（指数退避重试）

### 4. ToolExecutionHook（工具执行环绕）

**时机**：每次工具执行前后。

```java
public class ApprovalHook implements ToolExecutionHook {
    @Override
    public ToolExecutionResult around(ToolCallContext ctx, Chain chain) throws Exception {
        if ("bash".equals(ctx.toolName()) && isDangerous(ctx.arguments())) {
            return new ToolExecutionResult(new RuntimeException("该命令需要人工审批，已拒绝"));
        }
        var result = chain.proceed();                    // 执行工具
        auditLog.record(ctx.toolName(), result);          // 审计
        return result;
    }
}
```

典型用途：**审批与权限控制**（危险命令拦截）、审计日志、指标采集、结果改写（超时结构化错误）。

## 内置 Hook 一览

| Hook | 类型 | 功能 | 文档 |
|------|------|------|------|
| `LlmRetryHook` | request-error | 指数退避 + 抖动 + 尊重 Retry-After | [LLM 自动重试](retry.md) |
| `CompactionHook` | pre-step | 上下文超阈值自动摘要压缩 | [上下文压缩](compaction.md) |

> ⚠️ 内置 Hook **不会自动注册**——需要显式 `addXxxHook` 挂载，duo-agent 不替你做策略决定。

## 验证你的 Hook

参考示例 `HookVerificationExample`（无需 API Key，用 MockEchoAdapter）：演示四类 Hook 的调用时机与短路语义。
