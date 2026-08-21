# duo-agent

Agent SDK：两层 API（DuoModel 模型抽象 / DuoAgent 智能体组装）、三协议适配、
事件溯源会话日志。本文件是术语表，只收录本上下文特有的概念。

## Language

### 会话与驱动

**Turn**:
一次用户输入驱动的完整循环（模型调用 × N 步 + 工具执行），事件日志中
`turn/start` 与 `turn/end` 之间的全部事件。
_Avoid_: 轮次、round

**Turn 链（activity）**:
一次驱动唤醒连续执行的多个 turn；turn 正常结束且 inbox 有待办时链继续，每个 turn 换新取消信号。
_Avoid_: 会话循环

**取消收敛（cancel convergence）**:
cancel 发出后到驱动线程完成收尾（写 sentinel、写 turn/end、置 Idle）的过程；
收敛完成前不开新驱动，期间的新消息落入 wake latch。
_Avoid_: 取消完成、取消生效

### 取消

**CancellationSignal（取消信号）**:
turn 级取消权威对象：cause 首写固化、检查点抛出、监听器触发断连/杀进程。
一个 turn 一个实例，显式传参贯穿 LLM 调用与工具执行，不用 ThreadLocal。
_Avoid_: AbortSignal、cancel flag、中断标志

**双通道取消**:
显式 signal 承载语义 + 驱动线程 interrupt 即时唤醒阻塞原语的组合；
interrupt 后凭 signal 查询区分「用户取消」与「意外中断」。
_Avoid_: 抢占式取消（本项目的取消是协作式立场，interrupt 只是唤醒手段）

**Sentinel 工具结果**:
取消后为保持 `tool_call`/`tool_result` 事件配对而写入的占位错误结果；
分 ABORTED（body 已启动）与 ABORTED_BEFORE_DISPATCH（未启动）两档。
_Avoid_: 假结果、占位结果

### 模型与协议

**API 格式（api format）**:
模型端点的协议三分类（Chat Completions / Anthropic Messages / Responses），
是 Model 类型系统的切分维度；厂商只是配置值。
_Avoid_: 提供方类型、厂商协议
