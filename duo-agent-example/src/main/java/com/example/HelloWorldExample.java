package com.example;

import dev.duo.api.DuoAgent;
import dev.duo.api.DuoModel;
import dev.duo.model.deepseek.DeepSeekModel;

/**
 * HelloWorld 示例 - 展示 duo-agent 最简化用法。
 * <p>
 * 两步式用法：先用 DeepSeekModel 封装模型配置（可复用），
 * 再交给 DuoAgent.builder() 组装会话与工具。
 * </p>
 *
 * @author zhangyl
 * @date 2026-08-19
 */
public class HelloWorldExample {

    public static void main(String[] args) {
        System.out.println("=== Duo Agent - HelloWorld 示例 ===\n");

        // 获取 API Key（优先环境变量，其次 .env 文件）
        var apiKey = EnvLoader.get("DEEPSEEK_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            System.err.println("❌ 错误：未设置 DEEPSEEK_API_KEY");
            System.err.println("   方式 1：在项目根目录创建 .env 文件");
            System.err.println("           DEEPSEEK_API_KEY=your_api_key");
            System.err.println("   方式 2：设置环境变量");
            System.err.println("           export DEEPSEEK_API_KEY=your_api_key\n");
            System.exit(1);
        }

        // ==================== 核心代码（新 API）====================
        // 1. 模型配置：同一 Model 可传给多个 Agent 复用
        DuoModel model = DeepSeekModel.builder()
                .apiKey(apiKey)                  // baseUrl 默认官方端点
                .model("deepseek-chat")
                .contextWindow(128000)
                // deepseek-chat 非推理模型可限制输出；推理模型（deepseek-reasoner）建议不设置
                .maxOutputTokens(4096)
                .build();

        // 2. Agent：只配置会话与工具，模型配置不再重复填写
        var agent = DuoAgent.builder()
                .model(model)
                .withFileTools()
                .build();

        String response = agent.call("你好，请介绍一下自己。");
        System.out.println(response);
        // ========================================================

        System.out.println("\n✅ 示例完成");
    }
}
