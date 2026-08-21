package dev.duo.api.agent;

/**
 * Agent 的生命周期状态。
 * <p>
 * {@code idle} 表示没有驱动正在运行；
 * {@code running} 从唤醒输入开始可取消的 pre-step 处理开始，
 * 持续到驱动排空、关闭或检查点 turn。
 * </p>
 *
 * @author zhangyl
 * @date 2026-08-18
 */
public enum AgentStatus {
    /** 没有驱动正在运行。 */
    IDLE,
    /** 驱动正在运行（从唤醒输入开始，持续到驱动排空或检查点 turn）。 */
    RUNNING
}