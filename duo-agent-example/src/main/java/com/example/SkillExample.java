package com.example;

import dev.duo.api.agent.AgentOptions;
import dev.duo.api.llm.LlmRuntime;
import dev.duo.api.skill.SkillSource;
import dev.duo.core.agent.ReactLoopAgent;
import dev.duo.core.llm.MockEchoAdapter;
import dev.duo.core.llm.SystemPromptImpl;
import dev.duo.core.llm.ToolRegistryImpl;
import dev.duo.core.skill.FilesystemSkillProvider;
import dev.duo.core.skill.SkillCatalogSection;
import dev.duo.core.skill.SkillRegistry;
import dev.duo.core.session.Session;
import dev.duo.model.llm.ContentBlock;
import dev.duo.model.llm.Message;
import dev.duo.model.session.SessionId;
import dev.duo.tool.SkillTool;
import dev.duo.util.MessageId;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Skill 端到端示例：演示从注册到模型调用的完整流程。
 * <p>
 * 流程：
 * 1. 创建临时 skills 目录并写入 SKILL.md
 * 2. 注册 FilesystemSkillProvider
 * 3. 注册 SkillTool
 * 4. 注入 SkillCatalogSection
 * 5. 模拟模型调用 skill 工具
 * 6. 验证返回完整 content
 * </p>
 *
 * @author zhangyl
 * @date 2026-08-19
 */
public class SkillExample {

    public static void main(String[] args) throws Exception {
        // 1. 创建临时 skills 目录
        var tempDir = Files.createTempDirectory("skills-demo");
        System.out.println("临时技能目录: " + tempDir);

        try {
            // 写入测试技能文件
            var skillFile = tempDir.resolve("code-review.md");
            Files.writeString(skillFile, """
                    ---
                    name: code-review
                    description: Use when reviewing code for best practices and style
                    ---
                    # Code Review Skill
                    
                    When reviewing code, check the following:
                    
                    1. **Naming**: Use clear, descriptive names
                    2. **Comments**: Add comments for complex logic
                    3. **Error Handling**: Handle exceptions properly
                    4. **Testing**: Ensure adequate test coverage
                    
                    Always be constructive and specific in feedback.
                    """);

            var skillFile2 = tempDir.resolve("git-commit.md");
            Files.writeString(skillFile2, """
                    ---
                    name: git-commit
                    description: Generate conventional commit messages
                    ---
                    # Git Commit Skill
                    
                    Follow Conventional Commits format:
                    
                    ```
                    <type>(<scope>): <subject>
                    
                    <body>
                    ```
                    
                    Types: feat, fix, docs, style, refactor, test, chore
                    """);

            // 2. 创建 Skill 注册表并注册 Provider
            var registry = new SkillRegistry();
            registry.register(new FilesystemSkillProvider(tempDir, SkillSource.PROJECT, "demo", 100));

            // 验证 discover
            var candidates = registry.listAll();
            System.out.println("\n发现 " + candidates.size() + " 个技能:");
            for (var candidate : candidates) {
                System.out.println("  - " + candidate.name() + ": " + candidate.description());
            }

            // 3. 创建运行时环境
            var toolRegistry = new ToolRegistryImpl();
            var skillTool = new SkillTool(registry);
            toolRegistry.register(skillTool.getDefinition());

            var systemPrompt = new SystemPromptImpl();
            systemPrompt.section(SkillCatalogSection.create(registry));

            var llmRuntime = new LlmRuntime();
            var adapter = new MockEchoAdapter();
            llmRuntime.registerAdapter("mock", adapter);

            // 4. 创建 Agent
            var sessionId = new SessionId("skill-demo");
            var session = new Session(sessionId);
            var options = new AgentOptions("mock", "mock-model", null, null);
            var agent = new ReactLoopAgent(sessionId, options, session, llmRuntime, systemPrompt, toolRegistry);

            // 5. 模拟用户请求（触发技能加载）
            System.out.println("\n\n=== 模拟对话 ===\n");
            System.out.println("用户: 帮我 review 一下这段代码");
            
            agent.followup(createUserMessage("帮我 review 一下这段代码"));
            agent.whenIdle();

            // 6. 验证系统提示包含技能目录
            var assembly = systemPrompt.assemble();
            var hasCatalog = assembly.sections().stream()
                    .anyMatch(s -> s.name().equals("skill:catalog"));
            System.out.println("\n系统提示包含技能目录: " + hasCatalog);

            // 7. 手动加载技能（模拟模型调用 skill 工具）
            System.out.println("\n\n=== 直接加载技能 ===\n");
            var skill = registry.load("code-review");
            if (skill != null) {
                System.out.println("技能名称: " + skill.name());
                System.out.println("技能来源: " + skill.source());
                System.out.println("\n完整内容:\n" + skill.content());
            }

            // 8. 测试 SkillTool
            System.out.println("\n\n=== 通过 SkillTool 加载 ===\n");
            var toolResult = skillTool.getDefinition().executor().apply(
                    java.util.Map.of("name", "git-commit")
            );
            
            if (!toolResult.isError()) {
                var block = toolResult.content().get(0);
                if (block instanceof dev.duo.model.llm.ContentBlock.Text text) {
                    System.out.println("工具返回:\n" + text.text());
                }
            }

            System.out.println("\n\n✅ Skill 功能验证完成");

        } finally {
            // 清理临时目录
            Files.walk(tempDir)
                    .sorted(java.util.Comparator.reverseOrder())
                    .forEach(path -> {
                        try {
                            Files.delete(path);
                        } catch (Exception e) {
                            // ignore
                        }
                    });
            System.out.println("\n临时目录已清理");
        }
    }

    private static Message.UserMessage createUserMessage(String text) {
        return new Message.UserMessage(
                MessageId.random(),
                java.util.List.of(new ContentBlock.Text(text)),
                new dev.duo.model.llm.MessageSource.User()
        );
    }
}
