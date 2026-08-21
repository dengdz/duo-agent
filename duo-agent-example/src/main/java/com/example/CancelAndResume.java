package com.example;

import dev.duo.api.DuoAgent;
import dev.duo.api.agent.Agent;
import dev.duo.api.agent.AgentCancelCause;
import dev.duo.api.agent.CancelOptions;
import dev.duo.model.deepseek.DeepSeekModel;

/**
 * 验证取消后 Agent 是否可以继续使用。
 * <p>
 * 测试场景：
 * 1. 发起请求（调用 bash sleep 30）
 * 2. 3 秒后取消
 * 3. 再发起新请求（简单问答）
 * 4. 验证新请求是否正常执行
 * </p>
 * 
 * @author zhangyl
 * @date 2026-08-21
 */
public class CancelAndResume {

    public static void main(String[] args) throws Exception {
        var apiKey = System.getenv("DEEPSEEK_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            System.err.println("错误：请设置环境变量 DEEPSEEK_API_KEY");
            System.exit(1);
        }

        System.out.println("=".repeat(60));
        System.out.println("取消后恢复测试");
        System.out.println("=".repeat(60));
        System.out.println();

        var model = DeepSeekModel.builder()
                .apiKey(apiKey)
                .model("deepseek-chat")
                .build();

        var duoAgent = DuoAgent.builder()
                .model(model)
                .withCodeTools()
                .build();
        
        Agent agent = duoAgent.getAgent();

        // 第一次请求：长时任务，准备取消
        System.out.println("【第 1 次请求】发起长时 bash 任务...");
        new Thread(() -> {
            try {
                String result = duoAgent.call("用 bash 执行 sleep 30，然后告诉我完成了");
                System.out.println("第 1 次请求结果: " + result);
            } catch (Exception e) {
                System.out.println("第 1 次请求异常: " + e.getMessage());
            }
        }, "request-1").start();

        // 等待 3 秒后取消
        Thread.sleep(3000);
        System.out.println("\n[3s] 取消第 1 次请求...");
        agent.cancel(new AgentCancelCause.User(), new CancelOptions(false));
        agent.whenIdle();
        System.out.println("✓ 第 1 次请求已取消");

        // 等待 1 秒后发起第二次请求
        Thread.sleep(1000);
        System.out.println("\n【第 2 次请求】发起简单问答...");
        String result2 = duoAgent.call("1+1等于几？请直接回答数字。");
        System.out.println("✓ 第 2 次请求成功");
        System.out.println("第 2 次请求结果: " + result2);

        // 第三次请求：验证工具调用也正常
        System.out.println("\n【第 3 次请求】发起短时 bash 任务...");
        String result3 = duoAgent.call("用 bash 执行 echo 'Hello from bash'");
        System.out.println("✓ 第 3 次请求成功");
        System.out.println("第 3 次请求结果: " + result3);

        System.out.println();
        System.out.println("=".repeat(60));
        System.out.println("测试通过：取消后 Agent 可以正常继续使用");
        System.out.println("=".repeat(60));
    }
}
