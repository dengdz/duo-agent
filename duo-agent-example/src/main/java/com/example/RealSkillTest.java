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
import dev.duo.model.llm.ToolExecution;
import dev.duo.model.llm.ContentBlock;
import dev.duo.model.llm.Message;
import dev.duo.model.session.SessionId;
import dev.duo.tool.SkillTool;
import dev.duo.util.MessageId;

import java.nio.file.Path;

/**
 * 真实技能目录测试：从项目 .agents/skills 加载并演示。
 *
 * @author zhangyl
 * @date 2026-08-19
 */
public class RealSkillTest {

    public static void main(String[] args) throws Exception {
        System.out.println("=== 真实技能目录测试 ===\n");

        // 1. 创建注册表
        var registry = new SkillRegistry();

        // 2. 注册项目级技能目录
        var projectSkillsDir = Path.of(".agents/skills");
        if (!java.nio.file.Files.exists(projectSkillsDir)) {
            System.out.println("❌ 技能目录不存在: " + projectSkillsDir.toAbsolutePath());
            System.out.println("\n请先运行以下命令创建技能：");
            System.out.println("  mkdir -p .agents/skills");
            System.out.println("  # 然后创建 SKILL.md 文件");
            return;
        }

        registry.register(new FilesystemSkillProvider(
                projectSkillsDir,
                SkillSource.PROJECT,
                "project",
                100
        ));

        // 3. 列举发现的技能
        var candidates = registry.listAll();
        System.out.println("📚 发现 " + candidates.size() + " 个技能:\n");
        
        if (candidates.isEmpty()) {
            System.out.println("❌ 未发现任何技能");
            System.out.println("\n技能文件格式要求：");
            System.out.println("---");
            System.out.println("name: skill-name");
            System.out.println("description: Skill description");
            System.out.println("---");
            System.out.println("Skill content here");
            return;
        }

        for (var candidate : candidates) {
            System.out.printf("  ✓ %s\n", candidate.name());
            System.out.printf("    描述: %s\n", candidate.description());
            System.out.printf("    来源: %s (rank: %d)\n", candidate.source(), candidate.rank());
            System.out.printf("    路径: %s\n\n", candidate.path());
        }

        // 4. 创建 Agent 并注入技能目录
        var toolRegistry = new ToolRegistryImpl();
        var skillTool = new SkillTool(registry);
        toolRegistry.register(skillTool.getDefinition());

        var systemPrompt = new SystemPromptImpl();
        systemPrompt.section(SkillCatalogSection.create(registry));

        // 5. 验证系统提示包含技能目录
        var assembly = systemPrompt.assemble();
        System.out.println("=== 系统提示预览 ===\n");
        
        var catalogSection = assembly.sections().stream()
                .filter(s -> s.name().equals("skill:catalog"))
                .findFirst();
        
        if (catalogSection.isPresent()) {
            System.out.println(catalogSection.get().text());
        } else {
            System.out.println("❌ 未找到技能目录 section");
        }

        // 6. 加载第一个技能的完整内容
        if (!candidates.isEmpty()) {
            var firstSkillName = candidates.get(0).name();
            System.out.println("\n\n=== 加载技能: " + firstSkillName + " ===\n");
            
            var skill = registry.load(firstSkillName);
            if (skill != null) {
                System.out.println(skill.content());
            }
        }

        // 7. 测试 SkillTool
        System.out.println("\n\n=== SkillTool 测试 ===\n");
        
        for (var candidate : candidates) {
            var result = skillTool.getDefinition().executor().execute(ToolExecution.of(
                    java.util.Map.of("name", candidate.name())
            ));
            
            if (!result.isError()) {
                var block = result.content().get(0);
                if (block instanceof ContentBlock.Text text) {
                    System.out.println("✓ 工具加载 " + candidate.name() + " 成功");
                    System.out.println("  内容长度: " + text.text().length() + " 字符\n");
                }
            }
        }

        System.out.println("\n✅ 真实技能目录测试完成");
    }
}
