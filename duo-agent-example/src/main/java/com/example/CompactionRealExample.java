package com.example;

import dev.duo.adapter.deepseek.DeepSeekAdapter;
import dev.duo.api.agent.AgentHooks;
import dev.duo.api.agent.AgentOptions;
import dev.duo.api.llm.LlmRuntime;
import dev.duo.core.agent.ReactLoopAgent;
import dev.duo.core.compaction.CompactionConfig;
import dev.duo.core.compaction.CompactionHook;
import dev.duo.core.compaction.TokenEstimator;
import dev.duo.core.llm.SystemPromptImpl;
import dev.duo.core.llm.ToolRegistryImpl;
import dev.duo.core.session.Session;
import dev.duo.model.llm.ContentBlock;
import dev.duo.model.llm.Message;
import dev.duo.model.llm.MessageFactory;
import dev.duo.model.llm.MessageSource;
import dev.duo.model.session.SessionEventCompactionStart;
import dev.duo.model.session.SessionId;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

/**
 * 压缩（Compaction）真实 API 演示：真实 DeepSeek 对话 + 真实 LLM 摘要。
 * <p>
 * 密钥读取顺序：DEEPSEEK_API_KEY 环境变量 → 项目根 .env 文件（DEEPSEEK_API_KEY=sk-...）。
 * 观察：真实 token 累积 → 压缩触发（INFO 日志）→ 真实生成的摘要内容 →
 * 最后一轮模型凭摘要回忆此前讨论（摘要质量的真实验证）。
 * </p>
 *
 * @author zhangyl
 * @date 2026-08-19
 */
public class CompactionRealExample {

    private static final String PROVIDER = "deepseek";
    private static final String MODEL = "deepseek-chat";

    public static void main(String[] args) throws Exception {
        var apiKey = resolveApiKey();
        if (apiKey == null) {
            System.err.println("未找到 DEEPSEEK_API_KEY：请设置环境变量，或在项目根创建 .env 文件");
            System.err.println("写入一行：DEEPSEEK_API_KEY=sk-你的密钥");
            System.exit(1);
        }

        System.out.println("=== 压缩真实 API 演示（4 轮真实对话，阈值 100 token）===\n");

        var llm = new LlmRuntime();
        llm.registerAdapter(PROVIDER, new DeepSeekAdapter(apiKey, null));
        var session = new Session(new SessionId("compaction-real"));
        var agent = new ReactLoopAgent(
                new SessionId("compaction-real"),
                new AgentOptions(PROVIDER, MODEL, null, null, AgentHooks.builder()
                        .addPreStepHook(new CompactionHook(
                                llm, new SystemPromptImpl("你是一个简洁的助手。", false),
                                PROVIDER, MODEL,
                                // 阈值 100：中文估算（4字符≈1token）偏低，调低阈值保证演示触发；
                                // 保留约 60 token 的近期尾巴
                                new CompactionConfig(100, 60, 2, Duration.ofSeconds(90))))
                        .build()),
                session, llm, new SystemPromptImpl("你是一个简洁的助手。", false),
                new ToolRegistryImpl());

        var prompts = List.of(
                "用大约 150 字介绍一下 JVM 垃圾回收的分代设计。",
                "用大约 150 字对比 G1 与 ZGC 的取舍。",
                "用大约 100 字说明什么场景该用虚拟线程。",
                "只依据我们之前的讨论（包括任何对话摘要），用一句话概括今天聊的核心主题。");
        for (int i = 0; i < prompts.size(); i++) {
            System.out.println("【用户】" + prompts.get(i));
            agent.followup(MessageFactory.createUserMessage(
                    List.of(new ContentBlock.Text(prompts.get(i))), new MessageSource.User()));
            agent.whenIdle();

            var messages = session.deriveMessages();
            var compacted = session.events().stream()
                    .anyMatch(e -> e instanceof SessionEventCompactionStart);
            System.out.printf("%n第 %d 轮结束: 表面 %d 条消息，估算 %d token，日志 %d 个事件%s%n%n",
                    i + 1, messages.size(), TokenEstimator.estimateAll(messages),
                    session.events().size(), compacted ? "  ← 已发生过压缩" : "");
        }

        System.out.println("--- 压缩后的模型可见表面 ---");
        for (var message : session.deriveMessages()) {
            var text = message.content().stream()
                    .filter(b -> b instanceof ContentBlock.Text)
                    .map(b -> ((ContentBlock.Text) b).text())
                    .findFirst().orElse("");
            var role = switch (message) {
                case Message.UserMessage m when text.startsWith("[对话摘要]") -> "摘要";
                case Message.UserMessage ignored -> "用户";
                case Message.AssistantMessage ignored -> "助手";
                case Message.ToolResultMessage ignored -> "工具";
            };
            System.out.printf("  [%s] %.80s%s%n", role, text, text.length() > 80 ? "…" : "");
        }
        System.out.printf("%n日志事件总数: %d（完整保留） / 模型可见表面: %d 条%n",
                session.events().size(), session.deriveMessages().size());
        System.out.println("\n=== 演示完成：最后一轮的回答若能提到 GC/虚拟线程，说明摘要成功承载了历史 ===");
    }

    /** 环境变量优先，其次当前目录或父目录（项目根）的 .env（DEEPSEEK_API_KEY=sk-...）。 */
    private static String resolveApiKey() {
        var env = System.getenv("DEEPSEEK_API_KEY");
        if (env != null && !env.isBlank()) {
            return env;
        }
        // IDEA 的默认工作目录是模块目录，命令行是项目根——两处都找
        for (var dir = Path.of(".").toAbsolutePath(); dir != null; dir = dir.getParent()) {
            var envFile = dir.resolve(".env");
            if (!Files.exists(envFile)) {
                continue;
            }
            try (var lines = Files.lines(envFile)) {
                var key = lines.map(String::trim)
                        .filter(line -> line.startsWith("DEEPSEEK_API_KEY="))
                        .map(line -> line.substring("DEEPSEEK_API_KEY=".length()).trim())
                        .findFirst().orElse(null);
                if (key != null) {
                    return key;
                }
            } catch (java.io.IOException ignored) {
                // 读不到就继续向上找
            }
        }
        return null;
    }
}
