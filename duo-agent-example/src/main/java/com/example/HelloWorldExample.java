package com.example;

import dev.duo.api.DuoAgent;

/**
 * HelloWorld 示例 - 展示 duo-agent 最简化用法。
 * <p>
 * 这是使用 Builder API 的最简单示例，使用新的 API 格式配置。
 * </p>
 *
 * @author zhangyl
 * @date 2026-08-19
 */
public class HelloWorldExample {

    public static void main(String[] args) {
        System.out.println("=== Duo Agent - HelloWorld 示例 ===\n");

        // 获取 API Key（必须从环境变量读取）
        var apiKey = System.getenv("DEEPSEEK_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            System.err.println("❌ 错误：未设置 DEEPSEEK_API_KEY 环境变量");
            System.err.println("   请设置环境变量后重试：");
            System.err.println("   export DEEPSEEK_API_KEY=your_api_key\n");
            System.exit(1);
        }

        // ==================== 核心代码（新 API）====================
        var agent = DuoAgent.builder()
                .apiFormat("openai")  // DeepSeek 使用 OpenAI 兼容格式
                .baseUrl("https://api.deepseek.com")
                .apiKey(apiKey)
                .model("deepseek-chat")
                .contextWindow(128000)
                // deepseek-chat 非推理模型可限制输出；推理模型（deepseek-reasoner）建议不设置
                .maxOutputTokens(4096)
                .withFileTools()
                .build();

        String response = agent.chat("你好，请介绍一下自己。");
        System.out.println(response);
        // ========================================================

        System.out.println("\n✅ 示例完成");
    }
}
