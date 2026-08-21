package dev.duo.api.agent;

/**
 * turn 被取消的传播载体：由 {@link CancellationSignal#checkPoint()} 与
 * 工具的 interrupt 处理抛出，驱动循环显式捕获并转为
 * {@code TurnEndReason.Aborted} 收尾。
 * <p>
 * 必须为 checked：驱动循环存在 {@code catch (RuntimeException)} 兜底分支，
 * unchecked 的取消异常会被误记为 {@code Error} 收尾；checked 强制每个
 * 传播层显式决策。取消是终态语义——任何 request-error 恢复链不得将其
 * 转为重试。
 * </p>
 *
 * @author zhangyl
 * @date 2026-08-21
 */
public final class TurnCancelledException extends Exception {

    private final transient AgentCancelCause cancelCause;

    public TurnCancelledException(AgentCancelCause cancelCause) {
        super("turn 已取消: " + cancelCause);
        this.cancelCause = cancelCause;
    }

    /** 取消原因（首写固化的那份），收尾映射为 TurnEndCancelCause 持久化。 */
    public AgentCancelCause cancelCause() {
        return cancelCause;
    }
}
