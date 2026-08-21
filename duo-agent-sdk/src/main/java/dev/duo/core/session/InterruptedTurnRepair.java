package dev.duo.core.session;

import dev.duo.model.llm.ContentBlock;
import dev.duo.model.llm.MessageFactory;
import dev.duo.model.session.SessionEvent;
import dev.duo.model.session.SessionEventAssistantMessage;
import dev.duo.model.session.SessionEventStepEnd;
import dev.duo.model.session.SessionEventStepStart;
import dev.duo.model.session.SessionEventToolCall;
import dev.duo.model.session.SessionEventToolResult;
import dev.duo.model.session.SessionEventTurnEnd;
import dev.duo.model.session.SessionEventTurnStart;
import dev.duo.model.session.SurfaceOp;
import dev.duo.model.session.TurnEndReason;
import dev.duo.util.CallId;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 崩溃遗留的 open turn 闭合器。
 * <p>
 * 扫描事件日志的 turn/step 状态与未闭合工具调用，为崩溃时未关闭的末尾 turn
 * 合成闭合事件：每个悬挂工具调用一个 {@code tool/result}（isError，文案区分
 * "已启动但结果未知"与"未启动"以指导模型安全重试）、一个 {@code step/end}
 * 与一个 {@code turn/end{interrupted}}。合成事件的 seq 从末尾续、time 复用
 * 最后真实事件的时间（确定性，不发明未来时间）。平衡日志返回空列表。
 * </p>
 *
 * @author zhangyl
 * @date 2026-08-19
 */
public final class InterruptedTurnRepair {

    /** 已记录 tool/call、但无对应结果：结果未知，仅在幂等时重试。 */
    static final String OUTCOME_UNKNOWN_TEXT =
            "The tool call was interrupted after it was recorded, but no result was durably "
                    + "recorded. Its outcome is unknown. Decide whether to retry from the tool "
                    + "semantics: retry only if the operation is read-only or idempotent; if it "
                    + "may have side effects, first verify external state or ask the user. "
                    + "Do not retry blindly.";

    /** 未记录 tool/call：未启动，可直接重试。 */
    static final String NOT_STARTED_TEXT =
            "The tool call was interrupted before the harness recorded it as started. "
                    + "Retry it if it is still needed.";

    private InterruptedTurnRepair() {}

    /**
     * 计算闭合事件。
     *
     * @param events 已存储的事件日志（seq 连续）
     * @return 需要追加的合成闭合事件；日志平衡时为空列表
     */
    public static List<SessionEvent> closersFor(List<SessionEvent> events) {
        Integer openTurn = null;
        Integer openStep = null;
        // 悬挂调用：assistant 消息注册、tool/call 标记已启动、tool/result 消除；插入序保留转录顺序
        Map<CallId, PendingCall> pendingCalls = new LinkedHashMap<>();

        for (var event : events) {
            switch (event) {
                case SessionEventTurnStart t -> {
                    openTurn = t.turn();
                    openStep = null;
                    pendingCalls.clear();
                }
                case SessionEventTurnEnd t -> {
                    openTurn = null;
                    openStep = null;
                    pendingCalls.clear();
                }
                case SessionEventStepStart t -> openStep = t.step();
                case SessionEventStepEnd ignored -> {
                    pendingCalls.clear();
                    openStep = null;
                }
                case SessionEventAssistantMessage a -> {
                    for (var block : a.message().content()) {
                        if (block instanceof ContentBlock.ToolCall call) {
                            pendingCalls.put(call.id(), new PendingCall(a.step(), false));
                        }
                    }
                }
                case SessionEventToolCall c -> {
                    var entry = pendingCalls.get(c.callId());
                    if (entry != null) {
                        pendingCalls.put(c.callId(), new PendingCall(entry.step(), true));
                    }
                }
                case SessionEventToolResult t ->
                        pendingCalls.remove(t.message().source().callId());
                default -> { /* 其余事件不改变 turn/step 边界游标 */ }
            }
        }

        if (openTurn == null || events.isEmpty()) {
            return List.of();
        }

        var last = events.getLast();
        int seq = last.seq() + 1;
        long time = last.time();
        var closers = new ArrayList<SessionEvent>();

        // 先闭合调用再闭合 step：悬挂的助手调用会被提供方拒绝
        for (var entry : pendingCalls.entrySet()) {
            var callId = entry.getKey();
            var pending = entry.getValue();
            var text = pending.started() ? OUTCOME_UNKNOWN_TEXT : NOT_STARTED_TEXT;
            var resultMessage = MessageFactory.createToolResultMessage(
                    callId, List.of(new ContentBlock.Text(text)), true);
            closers.add(new SessionEventToolResult(
                    seq++, time, false, new SurfaceOp.Append(), null,
                    openTurn, pending.step(), resultMessage, null, null));
        }
        if (openStep != null) {
            closers.add(new SessionEventStepEnd(seq++, time, false, null, null,
                    openTurn, openStep));
        }
        closers.add(new SessionEventTurnEnd(seq++, time, false, null, null,
                openTurn, new TurnEndReason.Interrupted()));
        return closers;
    }

    /** 悬挂工具调用的定位信息。 */
    private record PendingCall(int step, boolean started) {}
}
