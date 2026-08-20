# Skill 系统

Skill 是"按需加载的操作手册"：以 Markdown 文件（SKILL.md 约定）形式存放领域知识或操作流程，模型通过 `skill` 工具按名称加载，注入对话上下文。

> ⚠️ Skill 系统当前**无门面入口**（工具预设明确不含 skill），需手动组装。完整可运行代码参见示例 `SkillExample` / `RealSkillTest` / `DeepSeekSkillDemo`。

## 核心组件

```
SkillProvider（SPI）──discover/load──► SkillRegistry（合并、rank 裁决）
                                            │
                     ┌──────────────────────┼──────────────────────┐
                     ▼                      ▼                      ▼
              SkillTool（工具）    SkillCatalogSection      你的代码
              模型按名加载正文     目录注入 system prompt     registry.listAll()
```

| 组件 | 职责 |
|------|------|
| `SkillProvider` | SPI：`discover()` 列举候选、`load(name)` 加载完整定义。来源不限：文件系统、数据库、网络 |
| `FilesystemSkillProvider` | 内置实现：递归扫描目录下的 `*.md`（SKILL.md 约定），解析 frontmatter |
| `SkillRegistry` | 合并多个 Provider 的候选；**重名按 rank 裁决**（越小越优先：project < user < bundled） |
| `SkillTool` | 名为 `skill` 的工具：模型传入名称，返回技能完整正文 |
| `SkillCatalogSection` | 把可用技能目录注入 system prompt（`<available_skills>` 摘要 + 使用指引），让模型知道有哪些技能可加载 |

## SKILL.md 格式

```markdown
---
name: code-review
description: 执行代码审查的流程与检查清单
---

# 代码审查流程

1. 先看变更范围（git diff）
2. 按检查清单逐项审查：
   ...
```

frontmatter 约定（`---` 分隔、单行 `key: value`）：

| 字段 | 说明 |
|------|------|
| `name` | 技能名，**kebab-case**（如 `code-review`） |
| `description` | 一句话描述（写入目录，模型据此决定是否加载） |

正文即技能内容——加载后完整注入模型上下文，写清楚操作步骤即可。

## 组装示例

```java
// 1. Provider：扫描文件系统（rank 越小优先级越高，在 Provider 构造时指定）
var provider = new FilesystemSkillProvider(
        Path.of(".agents/skills"),     // 扫描根目录
        SkillSource.PROJECT,           // 来源标识
        "my-project-skills",           // 提供者名称
        100);                          // rank（重名裁决用）

// 2. Registry：注册（rank 已随 Provider 携带）
var registry = new SkillRegistry();
registry.register(provider);

// 3. skill 工具（交给模型）
var skillTool = new SkillTool(registry);

// 4. 目录注入 system prompt（让模型知道有什么可用）
var systemPrompt = new SystemPromptImpl("你是一个智能助手", false);
systemPrompt.section(SkillCatalogSection.create(registry));

// 5. 手动组装 Agent
var toolRegistry = new ToolRegistryImpl();
toolRegistry.register(skillTool.getDefinition());
toolRegistry.register(new BashTool().getDefinition());
/* ... 其余组件同常规组装 ... */
```

组装后模型的行为：看到目录 → 判断需要 → 调用 `skill(name="code-review")` → 按加载的流程执行任务。

## rank 优先级

多个 Provider 提供同名技能时按 **rank 数值**裁决（越小越优先）。rank 完全由调用方在 Provider 构造时指定——常见的分层约定：

| 约定层级 | 建议 rank | 场景 |
|---------|----------|------|
| 项目级 | 小（如 100） | 项目内 `.agents/skills/` 覆盖全局定义 |
| 用户级 | 中（如 200） | 用户个人技能 |
| 内置 | 大（如 300） | 默认兜底 |

## 设计建议

- **技能正文面向模型写作**：步骤化、自包含，避免引用外部未加载的资源
- **description 是触发器**：写清楚"什么任务该用这个技能"
- **目录保持精简**：所有技能的 name+description 都会注入 system prompt（常驻上下文成本），正文按需加载
