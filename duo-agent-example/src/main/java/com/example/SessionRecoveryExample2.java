package com.example;

/**
 * "进程重启恢复对话"示例的 **resume 专用入口**（进程 B）。
 * <p>
 * IDEA 里直接点运行即走恢复流程，无需配置 Program arguments：
 * 先运行 {@link SessionRecoveryExample}（start，落盘），再运行本类（resume，恢复续聊）。
 * 重跑前请删除 sessions-demo 目录。
 * </p>
 *
 * @author zhangyl
 * @date 2026-08-19
 */
public class SessionRecoveryExample2 {

    public static void main(String[] args) throws Exception {
        SessionRecoveryExample.main(new String[]{"resume"});
    }
}
