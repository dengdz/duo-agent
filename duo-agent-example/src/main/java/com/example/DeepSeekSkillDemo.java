package com.example;

import dev.duo.api.agent.AgentOptions;
import dev.duo.api.llm.LlmRuntime;
import dev.duo.api.skill.SkillSource;
import dev.duo.adapter.deepseek.DeepSeekAdapter;
import dev.duo.core.agent.ReactLoopAgent;
import dev.duo.core.llm.SystemPromptImpl;
import dev.duo.core.llm.ToolRegistryImpl;
import dev.duo.core.skill.FilesystemSkillProvider;
import dev.duo.core.skill.SkillCatalogSection;
import dev.duo.core.skill.SkillRegistry;
import dev.duo.core.session.Session;
import dev.duo.model.llm.ContentBlock;
import dev.duo.model.llm.Message;
import dev.duo.model.llm.ToolProviderResult;
import dev.duo.model.llm.ToolSchema;
import dev.duo.model.session.SessionEventTurnEnd;
import dev.duo.model.session.SessionId;
import dev.duo.tool.SkillTool;
import dev.duo.util.MessageId;

import java.io.BufferedReader;
import java.io.FileReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * 真实 DeepSeek API + Skill 集成演示。
 * <p>
 * 需要 DEEPSEEK_API_KEY 环境变量或 .env 文件。
 * </p>
 *
 * @author zhangyl
 * @date 2026-08-19
 */
public class DeepSeekSkillDemo {

    public static void main(String[] args) throws Exception {
        System.out.println("=== DeepSeek + Skill 集成演示 ===\n");

        // 1. 读取 API Key
        String apiKey = loadApiKey();
        if (apiKey == null) {
            System.out.println("❌ 未找到 DEEPSEEK_API_KEY");
            System.out.println("\n请设置环境变量或在项目根目录创建 .env 文件：");
            System.out.println("  DEEPSEEK_API_KEY=sk-your-key-here");
            return;
        }
        System.out.println("✓ API Key 已加载\n");

        // 2. 检查技能目录
        var skillsDir = Path.of(".agents/skills");
        if (!Files.exists(skillsDir) || !Files.isDirectory(skillsDir)) {
            System.out.println("❌ 技能目录不存在: " + skillsDir.toAbsolutePath());
            System.out.println("\n请先创建技能文件，参考 RealSkillTest");
            return;
        }

        // 3. 创建 Skill Registry
        var registry = new SkillRegistry();
        registry.register(new FilesystemSkillProvider(
                skillsDir,
                SkillSource.PROJECT,
                "project",
                100
        ));

        var candidates = registry.listAll();
        System.out.println("📚 发现 " + candidates.size() + " 个技能:");
        for (var c : candidates) {
            System.out.println("  - " + c.name() + ": " + c.description());
        }
        System.out.println();

        if (candidates.isEmpty()) {
            System.out.println("❌ 未发现技能，请先创建 SKILL.md");
            return;
        }

        // 4. 创建运行时环境
        var llmRuntime = new LlmRuntime();
        var adapter = new DeepSeekAdapter(apiKey, "https://api.deepseek.com");
        llmRuntime.registerAdapter("deepseek", adapter);

        var toolRegistry = new ToolRegistryImpl();
        var skillTool = new SkillTool(registry);
        toolRegistry.register(skillTool.getDefinition());

        var systemPrompt = new SystemPromptImpl(
                "你是一个专业的 Java 开发助手。",
                false
        );
        systemPrompt.section(SkillCatalogSection.create(registry));
        
        // 关键：注册工具到 SystemPrompt（提供给 LLM）
        systemPrompt.tools(assembly -> {
            var schemas = new java.util.ArrayList<ToolSchema>();
            for (var tool : toolRegistry.getAll()) {
                schemas.add(new ToolSchema(
                    tool.name(),
                    tool.description(),
                    tool.parameters()
                ));
            }
            return new ToolProviderResult(schemas);
        });
        
        // 打印系统提示（调试）
        var assembly = systemPrompt.assemble();
        System.out.println("=== 系统提示预览 ===\n");
        System.out.println(dev.duo.core.llm.SystemPromptImpl.renderPrompt(assembly));
        System.out.println("\n=== 工具列表 ===");
        for (var tool : assembly.tools()) {
            System.out.println("  - " + tool.name() + ": " + tool.description());
            System.out.println("    参数: " + tool.parameters());
        }
        System.out.println();

        // 5. 创建 Agent
        var sessionId = new SessionId("deepseek-skill-demo");
        var session = new Session(sessionId);
        var options = new AgentOptions(
                "deepseek",
                "deepseek-v4-flash",  // 使用标准模型名
                4000,
                Duration.ofSeconds(30)
        );
        var agent = new ReactLoopAgent(sessionId, options, session, 
                llmRuntime, systemPrompt, toolRegistry);

        // 6. 演示场景：请求代码审查
        System.out.println("=== 场景：请求 Java 代码审查 ===\n");
        System.out.println("用户: 请帮我 review 这段代码\n");

        String demoCode = """
                public class UserService {
                    private Hashtable users = new Hashtable();
                    
                    public void addUser(String name) {
                        users.put(name, new User(name));
                    }
                    
                    public User getUser(String name) {
                        return (User) users.get(name);
                    }
                }
                """;

        var userMessage = new Message.UserMessage(
                MessageId.random(),
                java.util.List.of(new ContentBlock.Text(
                        "请帮我 review 这段代码：\n\n```java\n" + demoCode + "\n```"
                )),
                new dev.duo.model.llm.MessageSource.User()
        );

        agent.followup(userMessage);
        agent.whenIdle();

        // 7. 输出对话结果
        System.out.println("\n=== 对话记录 ===\n");
        printConversation(session);

        // 8. 统计
        System.out.println("\n=== 统计信息 ===\n");
        var turnCount = session.events().stream()
                .filter(e -> e instanceof SessionEventTurnEnd)
                .count();
        System.out.println("总轮次: " + turnCount);
        System.out.println("总事件数: " + session.events().size());
        
        // 检查是否调用了 skill 工具
        var skillToolCalls = session.events().stream()
                .filter(e -> e instanceof dev.duo.model.session.SessionEventToolCall)
                .map(e -> (dev.duo.model.session.SessionEventToolCall) e)
                .filter(e -> "skill".equals(e.name()))
                .count();
        System.out.println("Skill 工具调用次数: " + skillToolCalls);

        System.out.println("\n✅ 演示完成");
    }

    private static String loadApiKey() {
        // 1. 环境变量
        var key = System.getenv("DEEPSEEK_API_KEY");
        if (key != null && !key.isBlank()) {
            return key;
        }

        // 2. .env 文件
        var envFiles = new Path[]{
                Path.of(".env"),
                Path.of("../.env")
        };

        for (var envFile : envFiles) {
            if (Files.exists(envFile)) {
                try (var reader = new BufferedReader(new FileReader(envFile.toFile()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (line.startsWith("DEEPSEEK_API_KEY=")) {
                            return line.substring("DEEPSEEK_API_KEY=".length()).trim();
                        }
                    }
                } catch (Exception e) {
                    // ignore
                }
            }
        }

        return null;
    }

    private static void printConversation(Session session) {
        for (var message : session.deriveMessages()) {
            switch (message) {
                case Message.UserMessage m when m.source() instanceof dev.duo.model.llm.MessageSource.Tool ->
                        System.out.println("  [工具结果] " + firstText(m.content()));
                case Message.UserMessage m -> 
                        System.out.println("  [用户] " + firstText(m.content()));
                case Message.AssistantMessage m ->
                        System.out.println("  [助手] " + firstText(m.content()));
                default -> {}
            }
        }
    }

    private static String firstText(java.util.List<ContentBlock> content) {
        for (var block : content) {
            if (block instanceof ContentBlock.Text text) {
                return text.text();
            }
            if (block instanceof ContentBlock.ToolResult toolResult) {
                return firstText(toolResult.content());
            }
        }
        return "";
    }
}
