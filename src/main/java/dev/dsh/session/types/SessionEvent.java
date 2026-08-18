package dev.dsh.session.types;

import dev.dsh.llm.message.Message;
import dev.dsh.llm.message.MessageSource;
import dev.dsh.llm.types.ContentBlock;
import dev.dsh.llm.types.StreamChunk;
import dev.dsh.llm.types.TokenUsage;
import dev.dsh.util.CallId;

import java.util.List;

/**
 * 会话日志中的一条不可变条目。
 * <p>
 * 由 {@code type} 区分的判别联合：switch (event.type()) 收窄 event 类型。
 * 只有表面事件（UserMessage/AssistantMessage/ToolResult）才携带 surfaceOp 和 sourceEventSeqs。
 * </p>
 * <p>
 * 对应 TS 源码中的 {@code SessionEvent}。
 * </p>
 */
public sealed interface SessionEvent {

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

    // ========== 非表面事件 ==========

    /** 打开一轮对话。 */
    record TurnStart(
            int seq, long time, boolean ignorable,
            SurfaceOp surfaceOp, int[] sourceEventSeqs,
            int turn
    ) implements SessionEvent {
        public TurnStart(int seq, int turn) {
            this(seq, System.currentTimeMillis(), false, null, null, turn);
        }
        @Override public String type() { return "turn/start"; }
    }

    /** 关闭一轮对话。 */
    record TurnEnd(
            int seq, long time, boolean ignorable,
            SurfaceOp surfaceOp, int[] sourceEventSeqs,
            int turn, TurnEndReason reason
    ) implements SessionEvent {
        public TurnEnd(int seq, int turn, TurnEndReason reason) {
            this(seq, System.currentTimeMillis(), false, null, null, turn, reason);
        }
        @Override public String type() { return "turn/end"; }
    }

    /** 打开一次模型调用步骤。 */
    record StepStart(
            int seq, long time, boolean ignorable,
            SurfaceOp surfaceOp, int[] sourceEventSeqs,
            int turn, int step
    ) implements SessionEvent {
        public StepStart(int seq, int turn, int step) {
            this(seq, System.currentTimeMillis(), false, null, null, turn, step);
        }
        @Override public String type() { return "step/start"; }
    }

    /** 关闭一次模型调用步骤。 */
    record StepEnd(
            int seq, long time, boolean ignorable,
            SurfaceOp surfaceOp, int[] sourceEventSeqs,
            int turn, int step
    ) implements SessionEvent {
        public StepEnd(int seq, int turn, int step) {
            this(seq, System.currentTimeMillis(), false, null, null, turn, step);
        }
        @Override public String type() { return "step/end"; }
    }

    /** 模型输出的原始 token 片段。 */
    record AssistantChunk(
            int seq, long time, boolean ignorable,
            SurfaceOp surfaceOp, int[] sourceEventSeqs,
            int turn, int step, StreamChunk chunk
    ) implements SessionEvent {
        public AssistantChunk(int seq, int turn, int step, StreamChunk chunk) {
            this(seq, System.currentTimeMillis(), false, null, null, turn, step, chunk);
        }
        @Override public String type() { return "assistant/chunk"; }
    }

    /** 模型请求调用工具。 */
    record ToolCall(
            int seq, long time, boolean ignorable,
            SurfaceOp surfaceOp, int[] sourceEventSeqs,
            int turn, int step, CallId callId, String name, String arguments
    ) implements SessionEvent {
        public ToolCall(int seq, int turn, int step, CallId callId, String name, String arguments) {
            this(seq, System.currentTimeMillis(), false, null, null, turn, step, callId, name, arguments);
        }
        @Override public String type() { return "tool/call"; }
    }

    /** 整表快照的 todo 列表。 */
    record TodoWrite(
            int seq, long time, boolean ignorable,
            SurfaceOp surfaceOp, int[] sourceEventSeqs,
            List<TodoItem> todos
    ) implements SessionEvent {
        public TodoWrite(int seq, List<TodoItem> todos) {
            this(seq, System.currentTimeMillis(), false, null, null, todos);
        }
        @Override public String type() { return "todo/write"; }
    }

    /** 请求配置快照。 */
    record RequestHeader(
            int seq, long time, boolean ignorable,
            SurfaceOp surfaceOp, int[] sourceEventSeqs,
            EpochHeader header, String reason
    ) implements SessionEvent {
        public RequestHeader(int seq, EpochHeader header, String reason) {
            this(seq, System.currentTimeMillis(), false, null, null, header, reason);
        }
        @Override public String type() { return "request/header"; }
    }

    /** 路由元数据。 */
    record RequestContext(
            int seq, long time, boolean ignorable,
            SurfaceOp surfaceOp, int[] sourceEventSeqs,
            String provider, String model, Integer contextWindow
    ) implements SessionEvent {
        public RequestContext(int seq, String provider, String model) {
            this(seq, System.currentTimeMillis(), false, null, null, provider, model, null);
        }
        @Override public String type() { return "request/context"; }
    }

    /** 标记种子事件结束位置。 */
    record SessionEndSeed(
            int seq, long time, boolean ignorable,
            SurfaceOp surfaceOp, int[] sourceEventSeqs
    ) implements SessionEvent {
        public SessionEndSeed(int seq) {
            this(seq, System.currentTimeMillis(), false, null, null);
        }
        @Override public String type() { return "session/end-seed"; }
        @Override public boolean ignorable() { return true; }
    }

    // ========== 表面事件（携带 surfaceOp 和 sourceEventSeqs） ==========

    /** 用户输入的消息（表面事件）。 */
    record UserMessage(
            int seq, long time, boolean ignorable,
            SurfaceOp surfaceOp, int[] sourceEventSeqs,
            Message.UserMessage message
    ) implements SessionEvent {
        public UserMessage(int seq, Message.UserMessage message, SurfaceOp surfaceOp) {
            this(seq, System.currentTimeMillis(), false, surfaceOp, null, message);
        }
        @Override public String type() { return "user/message"; }
    }

    /** 组装好的模型回复（表面事件）。 */
    record AssistantMessage(
            int seq, long time, boolean ignorable,
            SurfaceOp surfaceOp, int[] sourceEventSeqs,
            int turn, int step,
            Message.AssistantMessage message, TokenUsage usage
    ) implements SessionEvent {
        public AssistantMessage(int seq, int turn, int step, Message.AssistantMessage message, SurfaceOp surfaceOp, TokenUsage usage) {
            this(seq, System.currentTimeMillis(), false, surfaceOp, null, turn, step, message, usage);
        }
        @Override public String type() { return "assistant/message"; }
    }

    /** 工具执行结果（表面事件）。 */
    record ToolResult(
            int seq, long time, boolean ignorable,
            SurfaceOp surfaceOp, int[] sourceEventSeqs,
            int turn, int step,
            Message.ToolResultMessage message,
            String errorName, String errorCode
    ) implements SessionEvent {
        public ToolResult(int seq, int turn, int step, Message.ToolResultMessage message, SurfaceOp surfaceOp) {
            this(seq, System.currentTimeMillis(), false, surfaceOp, null, turn, step, message, null, null);
        }
        @Override public String type() { return "tool/result"; }
    }
}