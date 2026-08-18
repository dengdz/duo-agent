package dev.dsh.api.agent;

/**
 * Agent 的生命周期状态。
 * <p>
 * {@code idle} 表示没有驱动正在运行；
 * {@code running} 从唤醒输入开始可取消的 pre-step 处理开始，
 * 持续到驱动排空、关闭或检查点 turn。
 * </p>
 * 对应 TS 源码中的 {@code AgentStatus}。
 */
public enum AgentStatus {
    IDLE,
    RUNNING
}