---
name: duo-code-review
description: 审查 duo-agent 仓库的代码变更、提交或 PR 时使用。将审查者定向到本仓库的标准（零依赖红线、事件溯源不变量、并发约定、文档同步要求）与代码本身看不出来的检查项。语义审查优先于风格：一条有实证的 blocker 胜过一堆风格 nit。当用户说"审查一下"、"review 这个改动"、"看看这次提交有没有问题"时触发。
---

# 审查 duo-agent 代码变更

**本技能是指导，不是完整清单。** 先看清楚变更范围（`git diff --stat` / `git show <commit>`），读足够的上下文代码理解设计意图，再逐项检查。优先级排序：正确性 > 生命周期/并发 > 安全 > 破坏既有行为 > 风格。短而实证的审查优于冗长的 nit 清单。

## 事实来源（读原文，不要凭记忆转述）

- [README.md](../../../README.md) 与 [docs/](../../../docs/index.md)：对外承诺的行为。**文档与代码不一致按 blocker 处理**（本仓库吃过亏：文档审查曾发现 13 处 API 签名错误）。
- [docs/05-reference/limitations.md](../../../docs/05-reference/limitations.md)：已知限制清单。变更若消除了某条限制，文档必须同步删除该条。
- [docs/05-reference/events.md](../../../docs/05-reference/events.md)：15 种事件的字段契约——改事件结构就是改持久化格式。
- 通用 Java 规约（命名/异常/并发/日志）检查：交由 `open-code-review` 插件（`ocr` CLI）执行，`ocr review --audience agent -b "<业务上下文>"`；其对 diff 的行级意见与本节其余检查合并报告。
- [HANDOFF.md](../../../HANDOFF.md)：历史决策记录。与既有决策相抵触时是设计讨论，不是自动否决——但要在报告中显式提出。

## Blocking 级要求

1. **零依赖红线。** `duo-agent-sdk/pom.xml` 不得新增第三方依赖（现有：slf4j-api 强制、logback provided/optional）。引入任何新依赖必须先获得用户明确同意。
2. **文档与代码同步。** 配置项、默认值、异常行为、事件字段、公开 API 签名变更时，同一 diff 内更新 README + docs/ 对应章节。Javadoc 声明的 `@throws` 必须与实现抛出的异常类型精确一致。
3. **事件溯源不变量。** 改动 `SessionEvent` 相关代码时核对：`seq` 严格连续（= 日志下标）；表面事件（`user/message`/`assistant/message`/`tool/result`）必须带 `surfaceOp`，非表面事件禁止携带（`SurfaceManager.validateNext` 强制）；`SessionEventCodec` 对新字段必须保持"null 整体省略、未知字段忽略"的兼容约定。
4. **超时分层不被破坏。** HTTP 层兜底必须 ≥ 应用层最大超时（`max(llmTimeout, reasoningTimeout) + 余量`，见 `DuoAgentBuilder.createAdapter`）；应用层 barrier 用毫秒精度（`toSeconds()` 会把亚秒截断为 0）。
5. **证据存在。** 确认作者跑过覆盖该 diff 的测试（见 [duo-pre-push-checks](../duo-pre-push-checks/SKILL.md)）；审查其语义缺口。

## 手工检查维度

- **意图与接口契约**：每个改动的接口两侧都追一遍。实现是否匹配意图；错误、取消、资源所有权是否处理。
- **并发与生命周期**：本仓库高频模式——虚拟线程驱动（`Thread.ofVirtual`）、`synchronized` 串行派发（`ChatStreamPublisher.drain`）、原子标志（`closed`/`cancelled`/`subscribedOnce`）。检查：发布前竞态、await 期间取消、回调重入、`finally` 清理完整性、迟到回调是否被标志挡住。
- **订阅生命周期**：`AutoCloseable unsubscriber` 必须在 `finally` 关闭（内存泄漏）；`onNext/onComplete/onError` 恰好一次且串行。
- **SLF4J 陷阱**：尾随 Throwable 参数不填充占位符（见 `ReactLoopAgent.whenIdle` 的注释先例），必须显式 `toString()`。
- **能力与消费方匹配**：新增公开方法若只有一个内部调用方，质疑是否应为 private；反之，通用服务（Session/LlmRuntime）上出现消费方专用行为也是泄漏。
- **范围与必要性**：每个新抽象/状态机/选项/兼容路径映射到当前生产消费方。挑战无关功能与投机泛型。
- **配置默认值**：每个默认值问"什么当前消费方证据或先例支持它"。没有证据时要求显式决策或推迟。
- **测试强度**：断言应在预期回归上失败；验证外部状态（session 事件、流接收顺序）而非复述实现。mock 适配器必须异步回放（对齐真实 adapter 的 `sendAsync` 语义——同步 mock 会让超时逻辑永不生效，本仓库踩过）。
- **中文注释与文档**：新注释是否泄漏推理过程（审查编号残留、"本次修复"、变更叙述）？用 [duo-trim-cot-leakage](../duo-trim-cot-leakage/SKILL.md) 判定。

## 报告发现

陈述：缺陷、位置（文件:行号）、影响、证据。局部缺陷给到最紧的 diff 范围；跨切面问题（架构/范围）用总评。**blocker 与 suggestion 分离**，已由绿灯测试覆盖的问题不再重复。收到审查意见时逐条技术性核实或反驳，不做表演性认同。

审查输出分级沿用本仓库惯例：错误（编译不过/行为矛盾）/ 不准确（措辞偏差）/ 遗漏（应有未覆盖）/ 确认（抽查一致项）。
