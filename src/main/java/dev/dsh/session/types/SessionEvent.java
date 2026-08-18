package dev.dsh.session.types;

/**
 * 会话日志中的一条不可变条目。
 * <p>
 * 由 {@code type()} 区分的判别联合。
 * 只有表面事件（UserMessage/AssistantMessage/ToolResult）才携带 surfaceOp 和 sourceEventSeqs。
 * </p>
 * <p>
 * 对应 TS 源码中的 {@code SessionEvent}。
 * </p>
 */
public sealed interface SessionEvent permits
        SessionEventTurnStart,
        SessionEventTurnEnd,
        SessionEventStepStart,
        SessionEventStepEnd,
        SessionEventAssistantChunk,
        SessionEventToolCall,
        SessionEventTodoWrite,
        SessionEventRequestHeader,
        SessionEventRequestContext,
        SessionEventSessionEndSeed,
        SessionEventUserMessage,
        SessionEventAssistantMessage,
        SessionEventToolResult {

    /** 事件类型名称。 */
    String type();
    /** 会话内单调递增序号。 */
    int seq();
    /** Unix 纪元毫秒时间戳。 */
    long time();
    /** 标记一个读者在无法识别 type 时可以安全跳过的事件。 */
    boolean ignorable();

    // ---- 表面事件可选字段 ----

    /** 如何进入模型可见表面；仅表面事件有值。 */
    SurfaceOp surfaceOp();
    /** 引用的源事件 seq 数组；仅表面事件有值。 */
    int[] sourceEventSeqs();
}