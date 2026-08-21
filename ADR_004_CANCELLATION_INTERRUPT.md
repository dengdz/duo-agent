# ADR 004: 取消打断机制——双通道取消信号贯穿调用链

**状态**: 已实施（0.4.0）
**日期**: 2026-08-21
**决策者**: zhangyl
**背景会话**: 0.4.0 取消打断功能设计会话（dsh 调研 + grill 访谈）

---

## 背景

0.3.0 的 `Agent.cancel()` 只做两件事：清 inbox、把 phase 换成 `Idle`——**不打断任何正在
执行的工作**。具体缺陷（9 处）：

1. LLM 流取消只能靠超时兜底（`barrier.get` 等 60s/5min），无法即时返回
2. 工具执行无取消通道：`ToolDefinition.executor` 是 `Function<Map, Result>`，
   取消信号到不了工具
3. `turn()` 内 step 循环不检查取消，要等整个 turn 自然跑完
4. **双驱动竞态**：cancel 置 `Idle` 后旧驱动未收敛，新 `send(wakeup)` 立即开新 turn，
   两个驱动并发写同一 session
5. `TurnEndReason.Aborted` 存在但从未写入——被取消的 turn 记成 `Completed`/`Error`
6. 取消打断工具后产生悬空 `tool_call` 事件（无配对 result，replay 不完整）
7. 取消无法触达 LLM HTTP 连接：`sendAsync + ofLines` 继续下载，服务器继续生成、
   token 白烧
8. BashTool 直接 `destroyForcibly`（无 grace），且 `/bin/sh -c` 的孙进程
   （真正干活的 make/npm）杀不掉
9. 取消若发生在 LLM 请求中，会被 request-error hook 当作 `INTERRUPTED` 失败拦去重试
   （取消被吞成重试）

模型层（`AgentCancelCause`/`CancelOptions`/`TurnEndReason.Aborted`/
`TurnEndCancelCause`）0.2.0 已照 dsh 备好，本次只补执行层。

## 调研参照（deepseek-harness）

dsh 的取消体系：turn 级 AbortController（reason 首写固化）+ signal 显式传参贯穿调用链
（拒绝 ThreadLocal/ALS）+ 协作式为纲（已启动的调用 drain 到静止，不 race-abandon）+
未启动调用补 sentinel 结果（`ABORTED`/`ABORTED_BEFORE_DISPATCH` 两档）+ 进程边界
两级 kill（SIGTERM 进程组 → 3s grace → SIGKILL）+ cancel-convergence wake latch
（收敛前不开新驱动）。

Java 相对 dsh（单线程事件循环、只能轮询检查点）多一个天然武器：
`Thread.interrupt()` 可即时唤醒阻塞在 `waitFor`/`Future.get`/`sleep` 上的线程。

## 决策

**双通道取消：turn 级显式取消信号（`CancellationSignal`）承载语义，
驱动线程 interrupt 承载即时唤醒；信号显式传参贯穿 LLM 调用与工具执行全链路；
取消后以 sentinel 结果与 `Aborted` turn 终止收尾；进程终止采用
`ProcessHandle.descendants()` 树状两级 kill。**

---

## 核心设计

### 1. CancellationSignal（新类型，`dev.duo.api.agent`）

turn 级取消权威，一个 turn 一个实例，reason 首写固化：

```java
public final class CancellationSignal {
    public void abort(AgentCancelCause cause);   // 首写胜出，重复调用 no-op
    public boolean isCancelled();
    public AgentCancelCause cause();             // 未取消时 null
    public void checkPoint();                    // 已取消则抛 TurnCancelledException
    public AutoCloseable addListener(Runnable);  // 取消时触发（断连/杀进程用）
}
```

- 放 `api.agent` 包（与 `AgentCancelCause` 同包）：`abort` 的参数类型来自
  api.agent，若放 `util` 会形成 util → api.agent 反向依赖，破坏 util
  最底层的分层。工具（tool）、LLM 适配器（api.llm / adapter）依赖
  api.agent 是既有正常方向（零依赖红线指不引第三方库，不受影响）
- `AgentCancelCause` 随信号传递，收尾时映射为 `TurnEndCancelCause` 持久化
- 首写固化用 `AtomicReference.compareAndSet`（CON-16：volatile 不解决多写）
- 监听器由 `abort()` 的调用方在**锁外**同步执行（见 2 节 cancel 两段式），
  保证断连/杀进程的即时性；监听器异常只记日志不传播（不得让 cancel() 失败）

**TurnCancelledException**（checked，`dev.duo.api.agent`）：携带
`AgentCancelCause`，由 `checkPoint()` 与工具的 interrupt 处理抛出，
`finalizeStep`/`turn()` 显式捕获。**必须为 checked 异常**：`turn()` 现有
`catch (RuntimeException)` 分支会把 unchecked 取消异常误记为 `Error` 收尾；
checked 强制调用方处理，杜绝误捕。取消传播用异常与 JDK
`CancellationException` 同理，不违反 E-02（异常不做流程控制——取消是
异常控制流的正当场景）。

### 2. ReactLoopAgent 改造

**信号生命周期**：`wakeDriver` 开 turn 链时新建 signal 存入 `Running` phase；
**每个 turn 开始时换新 signal**（对齐 dsh 的 per-turn controller——cancel 命中的是
"正在跑的这个 turn"，若 turn 已正常结束而链继续，旧信号不得污染新 turn）。

**cancel() 新语义**（修复缺陷 4/9）——两段式：锁内只做快操作，监听器（耗时 IO）
在锁外执行：

```java
public void cancel(AgentCancelCause cause, CancelOptions opts) {
    CancellationSignal signal;
    Thread driver;
    synchronized (phaseLock) {
        if (!opts.keepInbox()) inbox.clear();   // 清待办必须先于发信号：
        if (phase instanceof Running r) {        // turn 边界取消靠"新 turn claim 空"终止链
            signal = r.signal();
            driver = r.driverThread();
        } else {
            return;                              // Idle：仅清 inbox，不武装后续工作
        }
    }
    signal.abort(cause);        // 锁外：置标记（CAS 首写胜出）
    driver.interrupt();         // 锁外：即时唤醒阻塞原语
    // abort 内部同步触发监听器（断连/杀进程）——持 phaseLock 做这些会阻塞
    // send/wakeDriver/status 等所有竞争者（阿里规约：锁内禁耗时操作）
}
```

**收敛协议**：cancel 后 **phase 保持 `Running`**，由驱动线程自己收敛置 `Idle`。
`runLoop` 的 finally 置 `Idle` 并重放 `wakeLatch`（现有逻辑保留）。
cancel 后 `send(wakeup)` 落入 wakeLatch 分支等待收敛——双驱动竞态消除。
`whenIdle()` 改为循环跟随最新 `activity`（现状只等旧 future，turn 链重启后提前返回，
语义偏差一并修复）。

**已知窗口：turn 边界的 interrupt 残留**。cancel 在锁内 snapshot 旧 signal 后释放锁，
驱动恰在此间隙完成旧 turn、换新 signal 开新 turn——interrupt 到达时新 turn 的
`barrier.get` 立即抛 `InterruptedException`，查新 signal 未取消 → 报一次可重试的
`INTERRUPTED` 失败（走 request-error hook，可 Retry 自愈）。窗口为 turn 边界的
微秒级，危害为一次可自愈失败、无消息丢失、无双驱动，**接受并记录**（记入
limitations.md）。默认取消（清 inbox）下该窗口更窄：新 turn claim 空、链直接终止。
keepInbox 场景其语义本就是"取消当前 turn、保留后续"，turn 间隙的
取消命中新 turn 属契约内行为。

**检查点插入**（缺陷 3）：

- step 循环每次 `inbox.claim` 前
- `streamOnce` 进入前
- `finalizeStep` 每个工具调用 dispatch 前（当前及后续工具全部补 BEFORE_DISPATCH
  sentinel 后终止）

**取消不被重试吞掉**（缺陷 9）：新增内部异常 `TurnCancelledException`，
在 `executeStep` 的 request-error 分发**之前**短路——取消是终态语义，
不进入 `StepLlmException`/`dispatchRequestError` 链。

**LLM 流取消**（缺陷 1/7）：`streamOnce` 的 `barrier.get` 收到 interrupt 后
查 `signal.isCancelled()` 区分「用户取消」与「意外中断」（后者保持现有
`INTERRUPTED` 失败语义，可被 hook 重试）；取消路径 `closed.set(true)` 丢弃迟到
chunk 并抛 `TurnCancelledException`。

**turn 收尾**（缺陷 5）：`turn()` 捕获 `TurnCancelledException` →
`reason = Aborted(cause 映射)` → finally 写 `turn/end` → 链终止。
被取消 turn 的 assistant 消息与 sentinel 工具结果保留 surface 投影，
下一 turn 模型可见（对齐 dsh）。

### 3. 工具接口改造（缺陷 2，breaking）

```java
@FunctionalInterface
public interface ToolExecutor {
    ToolExecutionResult execute(ToolExecution execution);
}

public record ToolExecution(
        Map<String, Object> arguments,
        CancellationSignal cancellation      // 必填，无 null 默认
) {}

// 便利适配：不关心取消的工具保持 lambda 简洁
public static ToolExecutor of(Function<Map<String, Object>, ToolExecutionResult> fn);
```

- `ToolDefinition.executor` 字段类型 `Function` → `ToolExecutor`
- 9 个内置工具同步迁移：IO/进程类（BashTool）主动消费信号；纯计算类用
  `ToolExecutor.of` 包装零改动
- `ToolExecutionHook.ToolCallContext` 增加 `CancellationSignal cancellation` 字段
  （未来审批 hook 需要 answer 与取消 race）
- `ToolRegistryImpl.execute` 签名透传 signal

**取消取代成功**（dsh 竞态防护）：工具正常返回但 signal 已取消 →
框架将结果替换为 ABORTED sentinel（防「取消后还把成功结果写给模型」）。

### 4. Sentinel 结果两档（缺陷 6）

| 档位 | 触发 | 模型文案（常量） | 教导语义 |
|------|------|---------|---------|
| `ABORTED` | body 已启动（dispatch 后被打断） | `Error: tool call aborted` | 副作用可能已发生，先验证外部状态再重试 |
| `ABORTED_BEFORE_DISPATCH` | 未启动（检查点拦下 / 同批后续） | `Error: tool call aborted before dispatch` | 确定未执行，可直接重试 |

- **配对的第一理由是协议层硬约束**：assistant 消息（含 tool_call blocks）在
  step 流结束时已写入 session；若跳过的工具不补配对的 tool result 消息，
  下一请求 `deriveMessages` 出的消息序列违反三协议共同要求
  （tool_call 必须跟配对 tool result），服务端直接 400。事件溯源 replay
  完整性是第二理由
- **每个 `tool_call` 事件必有配对 `tool_result`**（含 `SurfaceOp.Append`——
  表面事件必带 surfaceOp 的事件溯源不变量）：dispatch 前被检查点拦下的调用
  同样写入 `tool_call` 事件 + sentinel result，与 dsh 一致
- sentinel 结果**必须以 `isError=true` 构造**：`ToolExecutionResult(String)`
  便利构造是 `isError=false`，直接复用会让模型把 sentinel 当正常输出——
  实现时用 `new ToolExecutionResult(true, List.of(new ContentBlock.Text(...)))`
- 工具抛 `TurnCancelledException`（catch interrupt → 杀进程 → 抛）由
  `finalizeStep` 统一转换为 sentinel，工具自身不写取消文案
- sentinel **不注入 inbox**（正常路径 tool result 注入 inbox 是为驱动下一 step；
  取消路径 turn 即将终止，无需驱动；session surface 投影已让下一 turn 可见）
- 档位错误码 0.4.0 **不进 `ToolExecutionResult` 结构化字段**（现状无 code 字段，
  模型文案已足够教导；ABORTED / ABORTED_BEFORE_DISPATCH 作为固定文案常量 +
  测试断言概念，避免过度设计）
- 终结在 `finalizeStep`，未来并行执行复用同一套转换

### 5. LlmAdapter 取消通道（缺陷 7，default 方法缓解 breaking）

```java
public abstract class LlmAdapter {
    public abstract void stream(GenerateOptions options, StreamCallback callback);

    /** 取消感知重载：signal 触发时断开 HTTP 连接（服务器停止生成）。 */
    public void stream(GenerateOptions options, StreamCallback callback,
                       CancellationSignal signal) {
        stream(options, callback);   // 默认忽略：旧适配器不感知取消，行为不变
    }
}
```

- 三协议适配器（ChatCompletions / Anthropic / Responses；DeepSeekAdapter 为
  委托薄壳自动获得）覆写三参重载：`signal.addListener` → 关闭 `ofLines`
  响应体流 / `cancel(true)` sendAsync future（JDK HttpClient 关闭 body 流
  即取消请求）；MockEchoAdapter 同步快速完成，沿用 default 忽略取消
- `LlmRuntime.stream` 增加对应三参重载，两参保留
- `AbstractDuoModel` 直连路径（call/stream）不传 signal：调用线程阻塞在 latch 上，
  interrupt 调用线程已覆盖该场景的取消
- **需查证**：`BodyHandlers.ofLines()` 流的并发 `close()` 语义（实施时以协议级
  测试验证断连可靠性，不可靠则回退 `cancel(true)` future 路线）

### 6. BashTool 进程树两级 kill（缺陷 8）

**采用 `ProcessHandle.descendants()` 树状遍历，不用 setsid / 负 PID kill**：

- macOS 无 `setsid` 二进制；负 PID kill 会误杀 JVM 同组进程——两者都不可移植
- 纯 JDK 跨平台：`process.toHandle().descendants()` 枚举全部后代

```
终止流程（超时与取消共用）：
1. 对 [自身 + 全部后代] 逐个 destroy()          ← SIGTERM，可清理退出
2. 等待 grace 3s
3. 仍存活者 destroyForcibly()                    ← SIGKILL 兜底
```

取消路径：`catch InterruptedException` → 恢复中断位 → 执行两级 kill →
抛 `TurnCancelledException`（框架接管 sentinel）。输出收集线程（BASH_EXECUTOR）
无需 interrupt：进程死后管道关闭，`readNBytes` 自然返回。

已知限制：遍历后代期间孙进程再 fork 的瞬时竞态可能漏杀（bash 场景可接受，
记入 limitations.md）。

### 7. status() 语义

保持 `RUNNING` 直到驱动收敛（不加 `CANCELLING` 状态）——取消到收敛通常毫秒级，
且「RUNNING = 有驱动线程在写 session」是唯一自洽语义。

---

## 约束与权衡

### 零依赖红线

✅ `CancellationSignal` 自研（JDK 原子类 + 监听器列表），进程 kill 用 JDK
`ProcessHandle`，无第三方库。

### 事件溯源不变量

✅ 取消路径每个 `tool_call` 必有配对 sentinel `tool_result`；
`turn/end` 携带 `Aborted(TurnEndCancelCause)`；replay/fork 无悬空引用。

### 超时分层红线

✅ 不改动现有超时分层；取消是超时之外的独立通道，interrupt 后由 signal 查询区分归因。

### Breaking 面（0.4.0 发布说明需列出）

| 变更 | 缓解 |
|------|------|
| `ToolDefinition.executor` 类型 `Function` → `ToolExecutor` | `ToolExecutor.of()` 适配旧 lambda |
| `LlmAdapter` 新增三参 `stream` 重载 | default 实现，旧适配器零改动 |
| `ToolCallContext` 新增字段 | record 构造变化，编译期暴露 |

### 协作式立场（对齐 dsh）

取消不 race-abandon：已启动的工具 body 会收尾（杀进程、抛出、转换 sentinel）
后驱动才收敛；`whenIdle()` 返回即代表一切副作用已静止。

---

## 测试策略

- **取消打断 bash**：`sleep 300` 执行中 cancel → 秒级返回（不等 300s 超时）→
  事件序列断言（tool_call + ABORTED sentinel + turn/end Aborted）→ 进程树确实死亡
- **取消后消息序列合法**：取消 turn 的 `deriveMessages` 产出满足协议配对
  （每个 tool_call block 有配对 tool result 消息）——防下一请求 400
- **取消打断 LLM 流**：mock 适配器挂起流（不回调）→ cancel → 驱动即时返回 →
  断连监听器被调用（mock 适配器须异步回放，对齐 sendAsync 语义）
- **取消不被重试吞**：request-error hook 配置 Retry → 取消仍立即终止（不走重试）
- **双驱动竞态**：cancel 后立即 `followup` → 断言无并发 turn（turn 序号连续，
  事件序列无交错）
- **turn 边界残留自愈**：cancel 与 turn 切换交错的场景，新 turn 至多以一次
  可重试 `INTERRUPTED` 失败收敛，followup 消息不丢失
- **keepInbox 语义**：取消后保留消息在新 turn 消费
- **sentinel 两档**：dispatch 前拦截（BEFORE_DISPATCH）与 dispatch 中打断（ABORTED）
  各一例；`turn/end` reason 正确；sentinel 事件带 `surfaceOp` 且 `isError=true`
- **意外中断 vs 取消**：无 signal.abort 的纯 interrupt 保持 `INTERRUPTED` 失败语义
- **CancellationSignal 单元**：并发 abort 首写胜出、监听器异常不传播、
  checkPoint 抛出
- **回归**：现有 284 测试全绿

---

## 实施阶段

1. `CancellationSignal` + `TurnCancelledException`（api.agent）+ 单元测试
   （首写固化、监听器异常不传播、checkPoint 抛出）
2. `ReactLoopAgent`：phase 携带 signal/driverThread、cancel 新语义、检查点、
   `TurnCancelledException`、whenIdle 循环
3. 工具接口迁移：`ToolExecutor`/`ToolExecution` + 9 内置工具 + `ToolRegistryImpl`
   + `ToolCallContext`
4. sentinel 转换 + 事件收尾（finalizeStep/turn）
5. 适配器取消通道（三协议适配器覆写三参重载 + LlmRuntime 重载 +
   MockEchoAdapter 沿用 default 忽略即可）+ ofLines close 查证
6. BashTool 进程树两级 kill
7. 测试补齐 + limitations.md 更新 + 文档站同步

## Java 实现规范要求（阿里巴巴 Java 开发手册）

- **常量收口（C-01）**：kill grace 时长（3s）、两档 sentinel 文案、监听器相关
  默认值一律有名常量，禁止字面量直写
- **锁规约**：`phaseLock` 内只做快操作（字段读写、CAS、interrupt）；断连/杀进程
  等耗时 IO 一律锁外执行；锁的获取与 try 块之间不留可抛异常代码（CON-07）
- **异常设计（E-04/E-07/E-08）**：`TurnCancelledException` 为 checked，携带
  cause；`BashTool` catch `InterruptedException` 必须转化为进程清理 +
  `TurnCancelledException`，不允许捕获后吞掉；方法 `throws` 声明与实际抛出
  精确匹配
- **日志（L-04/L-06/L-08）**：参数化占位符输出；cancel 路径用 WARN 记摘要
  （用户行为非系统错误），堆栈留 DEBUG；SLF4J 尾随 Throwable 参数不填充
  占位符——cause 需显式 `toString()`（仓库已有先例注释）
- **并发（CON-01/CON-02/CON-16）**：`CancellationSignal` 公开方法全部线程安全
  并在 Javadoc 声明；不新增裸线程（沿用已命名的 driverExecutor/BASH_EXECUTOR）；
  首写固化用 CAS 而非 volatile 赋值（多写场景）
- **Javadoc（COM-01~03）**：新增公共类型完整 Javadoc 含 `@author`/`@date`/
  线程安全声明；`checkPoint()`/`abort()` 的时序语义写明

## 需查证项（实现时确认，不猜 API）

1. `BodyHandlers.ofLines()` 响应体流并发 `close()` 是否可靠断连
   （不可靠则改 `sendAsync` future `cancel(true)`）
2. `ProcessHandle.descendants()` 在 macOS/Windows 的瞬时进程可见性
   （fork 竞态漏杀的实际窗口）
3. interrupt 到达时驱动线程正处于 session.append（非阻塞点）的穿越路径——
   检查点密度是否足够，还是需要 append 前检查

---

## 决策日志

| 问题 | 决策 | 理由 |
|------|------|------|
| 取消信号形态？ | **双通道：signal + interrupt** | signal 承载语义（cause/检查点/监听），interrupt 即时唤醒阻塞原语（Java 优势，dsh 没有）；纯轮询唤醒慢，纯 interrupt 无原因且未来并行执行不够用 |
| 工具接口怎么改？ | **直接改签名 + `ToolExecutor.of` 适配器** | 0.2.0/0.3.0 已有 breaking 先例；双字段兼容让复杂度永久化；魔法 key 污染参数空间 |
| LLM 断连深度？ | **真断 HTTP 连接**（default 重载缓解 breaking） | 付费 API 取消后服务器继续生成、token 白烧；应用层放弃只解决驱动返回不解决连接 |
| sentinel 档位？ | **两档 ABORTED / BEFORE_DISPATCH** | dsh 验证过的语义：模型区分「副作用可能已发生（先验证）」与「确定未执行（可重试）」 |
| 进程终止策略？ | **`ProcessHandle.descendants()` 树状两级 kill** | macOS 无 setsid、负 PID kill 误杀 JVM 同组进程；纯 JDK 跨平台；孙进程不泄漏 |
| cancel 后 phase？ | **保持 Running，驱动收敛置 Idle** | 消除双驱动竞态；对齐 dsh cancel-convergence + wake latch（duo-agent 已有 latch 基建） |
| 取消与 request-error 关系？ | **取消短路，不进重试链** | 取消是终态语义；现状缺陷会把取消吞成重试 |
| 取消时模型还看到结果吗？ | **本 turn 终止，下一 turn 可见 sentinel** | 模型需要知道被打断的事实才能正确续作；对齐 dsh surface 投影 |
| status 加 CANCELLING？ | **不加** | 取消到收敛毫秒级；RUNNING = 有驱动在写 session 是唯一自洽语义 |
| CancellationSignal 放哪个包？ | **api.agent（自审修订）** | 放 util 会形成 util → api.agent 反向依赖（abort 参数类型），破坏 util 最底层分层 |
| cancel 的锁策略？ | **两段式：锁内快操作 + 锁外监听器（自审修订）** | 断连/杀进程是耗时 IO，持 phaseLock 执行会阻塞 send/wakeDriver/status（阿里规约锁内禁耗时操作） |
| TurnCancelledException 勾选类型？ | **checked（自审修订）** | turn() 现有 catch (RuntimeException) 会把 unchecked 取消误记为 Error 收尾；checked 强制处理 |
| turn 边界 interrupt 残留？ | **接受为已知窗口（自审修订）** | 微秒级窗口、一次可重试失败、无消息丢失无双驱动；清 inbox 语义下窗口自然闭合 |

---

## 参考资料

- [ADR 002: DuoModel 架构设计](./ADR_002_DUOMODEL_ARCHITECTURE.md)
- [ADR 003: 多厂商模型适配](./ADR_003_MULTI_PROTOCOL_MODELS.md)
- deepseek-harness `2026-07-16-explicit-turn-cancellation.md` /
  `2026-07-19-cooperative-tool-cancellation.md` /
  `2026-08-07-cancel-convergence-wake-latch.md`——取消体系三篇设计笔记
- WHATWG AbortSignal 语义（reason 首写固化、监听器模型）
