# 取消与中断

Agent 执行中取消当前对话轮：断开 LLM 流、终止工具进程、立即返回，不白烧 token。

## 何时需要取消

- 用户在聊天界面点了"停止生成"
- 请求超时后放弃等待（不等 60s/5min 超时兜底）
- 巡批任务发现前置条件失效，及时止损

## 基本用法

`cancel()` 是门面方法，与 `call()` / `stream()` 同级：

```java
import dev.duo.api.agent.AgentCancelCause;

var agent = DuoAgent.builder().model(model).withCodeTools().build();

// 发起一个耗时对话（流式）
agent.stream("帮我做一次全仓库代码分析").subscribe(...);

// 需要停止时：取消当前 turn
agent.cancel(AgentCancelCause.User());
```

- 同步 `call()` 场景：从其他线程调用 `cancel()`，阻塞中的 `call()` 立刻返回 `"(Agent 已取消)"`
- 重复调用安全：取消信号首写固化，后续 `cancel()` 是幂等空操作

## 取消后会发生什么

| 环节 | 行为 |
|------|------|
| LLM 流 | 断连监听器触发，HTTP 连接立即断开（服务端可能短暂继续生成，但客户端不再接收） |
| bash 工具 | 两级 kill：SIGTERM → 3 秒宽限 → SIGKILL（含子进程树） |
| 自定义工具 | 收到取消信号 + 线程中断，需自行检查（见下文） |
| 事件日志 | turn 以 `TurnEndReason.Aborted` 收尾；悬空 `tool_call` 配对哨兵结果 |
| 订阅者 | `stream()` 以 `onComplete` 结束，不发 `onError` |

**哨兵结果两档**（维持 tool_call/tool_result 配对完整性）：

- `ABORTED_BEFORE_DISPATCH`——工具尚未开始执行，无副作用
- `ABORTED`——已开始执行被中断，副作用可能已发生

## keepInbox：取消但保留排队消息

默认取消会清空 Inbox 中排队/转向中的消息。传入 `CancelOptions(true)` 保留它们，取消的 turn 收尾后自动开新 turn 处理：

```java
import dev.duo.api.agent.CancelOptions;

// 用户连发两条消息后想撤销第一条的影响：取消当前，但第二条照常执行
agent.cancel(AgentCancelCause.User(), new CancelOptions(true));
```

## 取消后再对话

取消只终止当前 turn，不销毁 Agent。下一次调用是全新的一轮，与被取消的轮次无关：

```java
agent.cancel(AgentCancelCause.User());      // 取消当前
agent.call("1+1=?");                        // 新 turn，正常执行
```

## 自定义工具响应取消

框架把取消信号传进 executor（`ToolExecution.cancellation()`），长时阻塞操作需周期检查：

```java
private ToolExecutionResult execute(ToolExecution execution) throws TurnCancelledException {
    execution.cancellation().checkPoint();   // 已取消则抛 TurnCancelledException
    // ... 业务逻辑
}
```

完整模式（实例状态、中断捕获、检查点位置）见[自定义工具](tools-custom.md#编写好工具的实践)。

## 深入了解

- turn 级流程与事件配对：[ReAct 循环](../04-architecture/react-loop.md)
- 可运行演示：`duo-agent-example` 的 `CancellationDemo`（三场景）与 `CancelAndResume`
