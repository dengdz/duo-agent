package dev.duo.core.session;

import dev.duo.model.llm.ContentBlock;
import dev.duo.model.llm.FinishReason;
import dev.duo.model.llm.LlmFailure;
import dev.duo.model.llm.Message;
import dev.duo.model.llm.MessageSource;
import dev.duo.model.llm.StreamChunk;
import dev.duo.model.llm.TokenUsage;
import dev.duo.model.llm.ToolSchema;
import dev.duo.model.session.EpochHeader;
import dev.duo.model.session.SessionEvent;
import dev.duo.model.session.SessionEventAssistantChunk;
import dev.duo.model.session.SessionEventAssistantMessage;
import dev.duo.model.session.SessionEventCompactionEnd;
import dev.duo.model.session.SessionEventCompactionStart;
import dev.duo.model.session.SessionEventRequestContext;
import dev.duo.model.session.SessionEventRequestHeader;
import dev.duo.model.session.SessionEventSessionEndSeed;
import dev.duo.model.session.SessionEventStepEnd;
import dev.duo.model.session.SessionEventStepStart;
import dev.duo.model.session.SessionEventTodoWrite;
import dev.duo.model.session.SessionEventToolCall;
import dev.duo.model.session.SessionEventToolResult;
import dev.duo.model.session.SessionEventTurnEnd;
import dev.duo.model.session.SessionEventTurnStart;
import dev.duo.model.session.SessionEventUserMessage;
import dev.duo.model.session.SessionEventTypes;
import dev.duo.model.session.SessionHeader;
import dev.duo.model.session.SurfaceOp;
import dev.duo.model.session.TodoItem;
import dev.duo.model.session.TodoStatus;
import dev.duo.model.session.TurnEndCancelCause;
import dev.duo.model.session.TurnEndReason;
import dev.duo.util.CallId;
import dev.duo.util.JsonParser;
import dev.duo.util.MessageId;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * {@link SessionEvent} 与 JSONL 行的双向编解码。
 * <p>
 * 行格式为扁平判别 JSON：公共头（type/seq/time/ignorable）+ 各事件字段；
 * 可选字段为 null 时整体省略（不写 null）。sealed 层级（内容块、消息来源、
 * 结束原因等）用 {@code "k"} 判别键区分变体。未知 type 拒绝解码，
 * 未知字段忽略（向前兼容）。
 * </p>
 *
 * @author zhangyl
 * @date 2026-08-19
 */
public final class SessionEventCodec {

    private SessionEventCodec() {}

    // ---- 会话头 ----

    /** 编码会话头行（首行，type = "session"）。 */
    public static String encodeHeader(SessionHeader h) {
        var sb = new StringBuilder(128);
        sb.append("{\"type\":\"session\",\"version\":").append(h.version())
                .append(",\"id\":").append(escape(h.id().value()))
                .append(",\"createdAt\":").append(h.createdAt());
        str(sb, "cwd", h.cwd());
        if (h.parentSession() != null) {
            str(sb, "parentSession", h.parentSession().value());
        }
        num(sb, "seedLength", h.seedLength());
        str(sb, "origin", h.origin());
        num(sb, "delegationDepth", h.delegationDepth());
        str(sb, "agentPreset", h.agentPreset());
        sb.append('}');
        return sb.toString();
    }

    /** 解码会话头行；非 header 行或字段缺失抛 IllegalArgumentException。 */
    public static SessionHeader decodeHeader(String line) {
        var m = asMap(JsonParser.parse(line), "header 行");
        require("session".equals(rStr(m, "type")), "首行必须是 session header");
        return new SessionHeader(
                rInt(m, "version"),
                new dev.duo.model.session.SessionId(rStr(m, "id")),
                rLong(m, "createdAt"),
                rStr(m, "cwd"),
                rStr(m, "parentSession") != null
                        ? new dev.duo.model.session.SessionId(rStr(m, "parentSession")) : null,
                optInt(m, "seedLength"),
                rStr(m, "origin"),
                optInt(m, "delegationDepth"),
                rStr(m, "agentPreset"));
    }

    // ---- 事件 ----

    /** 编码事件行。 */
    public static String encode(SessionEvent e) {
        var sb = new StringBuilder(160);
        sb.append("{\"type\":").append(escape(e.type()))
                .append(",\"seq\":").append(e.seq())
                .append(",\"time\":").append(e.time())
                .append(",\"ignorable\":").append(e.ignorable());
        encodeSurface(sb, e.surfaceOp());
        if (e.sourceEventSeqs() != null && e.sourceEventSeqs().length > 0) {
            sb.append(",\"sourceEventSeqs\":[");
            for (int i = 0; i < e.sourceEventSeqs().length; i++) {
                if (i > 0) {
                    sb.append(',');
                }
                sb.append(e.sourceEventSeqs()[i]);
            }
            sb.append(']');
        }
        switch (e) {
            case SessionEventTurnStart t -> num(sb, "turn", t.turn());
            case SessionEventTurnEnd t -> {
                num(sb, "turn", t.turn());
                raw(sb, "reason", encodeTurnEndReason(t.reason()));
            }
            case SessionEventStepStart t -> { num(sb, "turn", t.turn()); num(sb, "step", t.step()); }
            case SessionEventStepEnd t -> { num(sb, "turn", t.turn()); num(sb, "step", t.step()); }
            case SessionEventAssistantChunk t -> {
                num(sb, "turn", t.turn());
                num(sb, "step", t.step());
                raw(sb, "chunk", encodeChunk(t.chunk()));
            }
            case SessionEventToolCall t -> {
                num(sb, "turn", t.turn());
                num(sb, "step", t.step());
                str(sb, "callId", t.callId().value());
                str(sb, "name", t.name());
                str(sb, "arguments", t.arguments());
            }
            case SessionEventTodoWrite t -> raw(sb, "todos", encodeTodos(t.todos()));
            case SessionEventRequestHeader t -> {
                raw(sb, "header", encodeEpochHeader(t.header()));
                str(sb, "reason", t.reason());
            }
            case SessionEventRequestContext t -> {
                str(sb, "provider", t.provider());
                str(sb, "model", t.model());
                num(sb, "contextWindow", t.contextWindow());
            }
            case SessionEventSessionEndSeed t -> { /* 仅公共头 */ }
            case SessionEventCompactionStart t -> {
                str(sb, "compactionId", t.compactionId());
                num(sb, "turn", t.turn());
            }
            case SessionEventCompactionEnd t -> {
                str(sb, "compactionId", t.compactionId());
                num(sb, "turn", t.turn());
                str(sb, "error", t.error());
            }
            case SessionEventUserMessage t -> raw(sb, "message", encodeMessage(t.message()));
            case SessionEventAssistantMessage t -> {
                num(sb, "turn", t.turn());
                num(sb, "step", t.step());
                raw(sb, "message", encodeMessage(t.message()));
                if (t.usage() != null) {
                    raw(sb, "usage", encodeUsage(t.usage()));
                }
            }
            case SessionEventToolResult t -> {
                num(sb, "turn", t.turn());
                num(sb, "step", t.step());
                raw(sb, "message", encodeMessage(t.message()));
                str(sb, "errorName", t.errorName());
                str(sb, "errorCode", t.errorCode());
            }
        }
        sb.append('}');
        return sb.toString();
    }

    /** 解码事件行；未知 type 或字段缺失抛 IllegalArgumentException。 */
    public static SessionEvent decode(String line) {
        var m = asMap(JsonParser.parse(line), "事件行");
        var type = rStr(m, "type");
        var seq = rInt(m, "seq");
        var time = rLong(m, "time");
        var ignorable = Boolean.TRUE.equals(m.get("ignorable"));
        var surfaceOp = decodeSurface(m.get("surfaceOp"));
        var seqs = decodeSeqs(m.get("sourceEventSeqs"));
        return switch (type) {
            case SessionEventTypes.TURN_START -> new SessionEventTurnStart(seq, time, ignorable, surfaceOp, seqs,
                    rInt(m, "turn"));
            case SessionEventTypes.TURN_END -> new SessionEventTurnEnd(seq, time, ignorable, surfaceOp, seqs,
                    rInt(m, "turn"), decodeTurnEndReason(asMap(m.get("reason"), "reason")));
            case SessionEventTypes.STEP_START -> new SessionEventStepStart(seq, time, ignorable, surfaceOp, seqs,
                    rInt(m, "turn"), rInt(m, "step"));
            case SessionEventTypes.STEP_END -> new SessionEventStepEnd(seq, time, ignorable, surfaceOp, seqs,
                    rInt(m, "turn"), rInt(m, "step"));
            case SessionEventTypes.ASSISTANT_CHUNK -> new SessionEventAssistantChunk(seq, time, ignorable, surfaceOp, seqs,
                    rInt(m, "turn"), rInt(m, "step"),
                    decodeChunk(asMap(m.get("chunk"), "chunk")));
            case SessionEventTypes.TOOL_CALL -> new SessionEventToolCall(seq, time, ignorable, surfaceOp, seqs,
                    rInt(m, "turn"), rInt(m, "step"),
                    new CallId(rStr(m, "callId")), rStr(m, "name"), rStr(m, "arguments"));
            case SessionEventTypes.TODO_WRITE -> new SessionEventTodoWrite(seq, time, ignorable, surfaceOp, seqs,
                    decodeTodos(m.get("todos")));
            case SessionEventTypes.REQUEST_HEADER -> new SessionEventRequestHeader(seq, time, ignorable, surfaceOp, seqs,
                    decodeEpochHeader(asMap(m.get("header"), "header")), rStr(m, "reason"));
            case SessionEventTypes.REQUEST_CONTEXT -> new SessionEventRequestContext(seq, time, ignorable, surfaceOp, seqs,
                    rStr(m, "provider"), rStr(m, "model"), optInt(m, "contextWindow"));
            case SessionEventTypes.SESSION_END_SEED -> new SessionEventSessionEndSeed(seq, time, ignorable, surfaceOp, seqs);
            case SessionEventTypes.COMPACTION_START -> new SessionEventCompactionStart(seq, time, ignorable,
                    surfaceOp, seqs, rStr(m, "compactionId"), optInt(m, "turn"));
            case SessionEventTypes.COMPACTION_END -> new SessionEventCompactionEnd(seq, time, ignorable,
                    surfaceOp, seqs, rStr(m, "compactionId"), optInt(m, "turn"), rStr(m, "error"));
            case SessionEventTypes.USER_MESSAGE -> new SessionEventUserMessage(seq, time, ignorable, surfaceOp, seqs,
                    (Message.UserMessage) decodeMessage(asMap(m.get("message"), "message")));
            case SessionEventTypes.ASSISTANT_MESSAGE -> new SessionEventAssistantMessage(seq, time, ignorable, surfaceOp, seqs,
                    rInt(m, "turn"), rInt(m, "step"),
                    (Message.AssistantMessage) decodeMessage(asMap(m.get("message"), "message")),
                    m.get("usage") != null ? decodeUsage(asMap(m.get("usage"), "usage")) : null);
            case SessionEventTypes.TOOL_RESULT -> new SessionEventToolResult(seq, time, ignorable, surfaceOp, seqs,
                    rInt(m, "turn"), rInt(m, "step"),
                    (Message.ToolResultMessage) decodeMessage(asMap(m.get("message"), "message")),
                    rStr(m, "errorName"), rStr(m, "errorCode"));
            default -> throw new IllegalArgumentException("未知会话事件类型: " + type);
        };
    }

    // ---- 嵌套模型：消息与内容块 ----

    private static String encodeMessage(Message msg) {
        var sb = new StringBuilder(96);
        sb.append("{\"id\":").append(escape(messageIdOf(msg)));
        raw(sb, "content", encodeBlocks(contentOf(msg)));
        raw(sb, "source", encodeSource(sourceOf(msg)));
        sb.append('}');
        return sb.toString();
    }

    private static Message decodeMessage(Map<?, ?> m) {
        var id = new MessageId(rStr(m, "id"));
        var content = decodeBlocks(m.get("content"));
        // 消息具体类型由 source 判别：User/Plugin→用户消息、Model→助手消息、Tool→工具结果
        return switch (decodeSource(asMap(m.get("source"), "source"))) {
            case MessageSource.User source -> new Message.UserMessage(id, content, source);
            case MessageSource.Plugin source -> new Message.UserMessage(id, content, source);
            case MessageSource.Model source -> new Message.AssistantMessage(id, content, source);
            case MessageSource.Tool source -> new Message.ToolResultMessage(id, content, source);
        };
    }

    private static String messageIdOf(Message msg) {
        return switch (msg) {
            case Message.UserMessage u -> u.id().value();
            case Message.AssistantMessage a -> a.id().value();
            case Message.ToolResultMessage t -> t.id().value();
        };
    }

    private static List<ContentBlock> contentOf(Message msg) {
        return switch (msg) {
            case Message.UserMessage u -> u.content();
            case Message.AssistantMessage a -> a.content();
            case Message.ToolResultMessage t -> t.content();
        };
    }

    private static MessageSource sourceOf(Message msg) {
        return switch (msg) {
            case Message.UserMessage u -> u.source();
            case Message.AssistantMessage a -> a.source();
            case Message.ToolResultMessage t -> t.source();
        };
    }

    private static String encodeBlocks(List<ContentBlock> blocks) {
        var sb = new StringBuilder(96);
        sb.append('[');
        for (var block : blocks) {
            if (sb.length() > 1) {
                sb.append(',');
            }
            sb.append(encodeBlock(block));
        }
        sb.append(']');
        return sb.toString();
    }

    private static String encodeBlock(ContentBlock block) {
        var sb = new StringBuilder(48);
        switch (block) {
            case ContentBlock.Text t -> {
                sb.append("{\"k\":\"text\"");
                str(sb, "text", t.text());
            }
            case ContentBlock.Reasoning r -> {
                sb.append("{\"k\":\"reasoning\"");
                str(sb, "text", r.text());
            }
            case ContentBlock.ToolCall c -> {
                sb.append("{\"k\":\"tool-call\"");
                str(sb, "id", c.id().value());
                str(sb, "name", c.name());
                str(sb, "arguments", c.arguments());
            }
            case ContentBlock.ToolResult r -> {
                sb.append("{\"k\":\"tool-result\"");
                str(sb, "toolCallId", r.toolCallId().value());
                raw(sb, "content", encodeBlocks(r.content()));
                bool(sb, "isError", r.isError());
            }
        }
        sb.append('}');
        return sb.toString();
    }

    private static List<ContentBlock> decodeBlocks(Object raw) {
        var list = asList(raw, "content");
        var result = new ArrayList<ContentBlock>(list.size());
        for (var item : list) {
            result.add(decodeBlock(asMap(item, "contentBlock")));
        }
        return result;
    }

    private static ContentBlock decodeBlock(Map<?, ?> m) {
        return switch (rStr(m, "k")) {
            case "text" -> new ContentBlock.Text(rStr(m, "text"));
            case "reasoning" -> new ContentBlock.Reasoning(rStr(m, "text"));
            case "tool-call" -> new ContentBlock.ToolCall(
                    new CallId(rStr(m, "id")), rStr(m, "name"), rStr(m, "arguments"));
            case "tool-result" -> new ContentBlock.ToolResult(
                    new CallId(rStr(m, "toolCallId")), decodeBlocks(m.get("content")),
                    Boolean.TRUE.equals(m.get("isError")));
            default -> throw new IllegalArgumentException("未知内容块: " + rStr(m, "k"));
        };
    }

    private static String encodeSource(MessageSource source) {
        var sb = new StringBuilder(32);
        switch (source) {
            case MessageSource.User ignored -> sb.append("{\"k\":\"user\"}");
            case MessageSource.Plugin p -> {
                sb.append("{\"k\":\"plugin\"");
                str(sb, "plugin", p.plugin());
                sb.append('}');
            }
            case MessageSource.Model m -> {
                sb.append("{\"k\":\"model\"");
                str(sb, "provider", m.provider());
                str(sb, "model", m.model());
                sb.append('}');
            }
            case MessageSource.Tool t -> {
                sb.append("{\"k\":\"tool\"");
                str(sb, "callId", t.callId().value());
                sb.append('}');
            }
        }
        return sb.toString();
    }

    private static MessageSource decodeSource(Map<?, ?> m) {
        return switch (rStr(m, "k")) {
            case "user" -> new MessageSource.User();
            case "plugin" -> new MessageSource.Plugin(rStr(m, "plugin"));
            case "model" -> new MessageSource.Model(rStr(m, "provider"), rStr(m, "model"));
            case "tool" -> new MessageSource.Tool(new CallId(rStr(m, "callId")));
            default -> throw new IllegalArgumentException("未知消息来源: " + rStr(m, "k"));
        };
    }

    // ---- 嵌套模型：流块与结束原因 ----

    private static String encodeChunk(StreamChunk chunk) {
        var sb = new StringBuilder(48);
        switch (chunk) {
            case StreamChunk.BlockStart c -> {
                sb.append("{\"k\":\"block-start\"");
                num(sb, "index", c.index());
                str(sb, "blockType", c.blockType());
            }
            case StreamChunk.TextDelta c -> {
                sb.append("{\"k\":\"text-delta\"");
                num(sb, "index", c.index());
                str(sb, "text", c.text());
            }
            case StreamChunk.ReasoningDelta c -> {
                sb.append("{\"k\":\"reasoning-delta\"");
                num(sb, "index", c.index());
                str(sb, "text", c.text());
            }
            case StreamChunk.ToolCallDelta c -> {
                sb.append("{\"k\":\"tool-call-delta\"");
                num(sb, "index", c.index());
                str(sb, "id", c.id().value());
                str(sb, "name", c.name());
                str(sb, "argumentsDelta", c.argumentsDelta());
            }
            case StreamChunk.BlockEnd c -> {
                sb.append("{\"k\":\"block-end\"");
                num(sb, "index", c.index());
                raw(sb, "block", encodeBlock(c.block()));
            }
            case StreamChunk.Usage c -> {
                sb.append("{\"k\":\"usage\"");
                raw(sb, "usage", encodeUsage(c.usage()));
            }
            case StreamChunk.Finish c -> {
                sb.append("{\"k\":\"finish\"");
                raw(sb, "reason", encodeFinishReason(c.reason()));
                // replayState 是运行时私有状态，不参与持久化
            }
        }
        sb.append('}');
        return sb.toString();
    }

    private static StreamChunk decodeChunk(Map<?, ?> m) {
        return switch (rStr(m, "k")) {
            case "block-start" -> new StreamChunk.BlockStart(rInt(m, "index"), rStr(m, "blockType"));
            case "text-delta" -> new StreamChunk.TextDelta(rInt(m, "index"), rStr(m, "text"));
            case "reasoning-delta" -> new StreamChunk.ReasoningDelta(rInt(m, "index"), rStr(m, "text"));
            case "tool-call-delta" -> new StreamChunk.ToolCallDelta(rInt(m, "index"),
                    new CallId(rStr(m, "id")), rStr(m, "name"), rStr(m, "argumentsDelta"));
            case "block-end" -> new StreamChunk.BlockEnd(rInt(m, "index"),
                    decodeBlock(asMap(m.get("block"), "block")));
            case "usage" -> new StreamChunk.Usage(decodeUsage(asMap(m.get("usage"), "usage")));
            case "finish" -> new StreamChunk.Finish(decodeFinishReason(asMap(m.get("reason"), "reason")));
            default -> throw new IllegalArgumentException("未知流块: " + rStr(m, "k"));
        };
    }

    private static String encodeFinishReason(FinishReason reason) {
        var sb = new StringBuilder(48);
        switch (reason) {
            case FinishReason.Stop ignored -> sb.append("{\"k\":\"stop\"}");
            case FinishReason.ToolCalls ignored -> sb.append("{\"k\":\"tool-calls\"}");
            case FinishReason.MaxTokens ignored -> sb.append("{\"k\":\"max-tokens\"}");
            case FinishReason.Aborted a -> {
                sb.append("{\"k\":\"aborted\"");
                raw(sb, "failure", encodeFailure(a.failure()));
                sb.append('}');
            }
            case FinishReason.Error err -> {
                sb.append("{\"k\":\"error\"");
                raw(sb, "failure", encodeFailure(err.failure()));
                sb.append('}');
            }
        }
        return sb.toString();
    }

    private static FinishReason decodeFinishReason(Map<?, ?> m) {
        return switch (rStr(m, "k")) {
            case "stop" -> new FinishReason.Stop();
            case "tool-calls" -> new FinishReason.ToolCalls();
            case "max-tokens" -> new FinishReason.MaxTokens();
            case "aborted" -> new FinishReason.Aborted(decodeFailure(asMap(m.get("failure"), "failure")));
            case "error" -> new FinishReason.Error(decodeFailure(asMap(m.get("failure"), "failure")));
            default -> throw new IllegalArgumentException("未知结束原因: " + rStr(m, "k"));
        };
    }

    private static String encodeFailure(LlmFailure failure) {
        var sb = new StringBuilder(64);
        sb.append("{\"message\":").append(escape(failure.message()));
        str(sb, "code", failure.code());
        num(sb, "status", failure.status());
        num(sb, "providerRetryAfterMs", failure.providerRetryAfterMs());
        str(sb, "requestId", failure.requestId());
        sb.append('}');
        return sb.toString();
    }

    private static LlmFailure decodeFailure(Map<?, ?> m) {
        return new LlmFailure(rStr(m, "message"), rStr(m, "code"), optInt(m, "status"),
                m.get("providerRetryAfterMs") == null ? null
                        : ((Number) m.get("providerRetryAfterMs")).longValue(),
                rStr(m, "requestId"));
    }

    private static String encodeUsage(TokenUsage usage) {
        var sb = new StringBuilder(48);
        sb.append("{\"inputTokens\":").append(usage.inputTokens())
                .append(",\"outputTokens\":").append(usage.outputTokens());
        num(sb, "cacheReadTokens", usage.cacheReadTokens());
        num(sb, "cacheWriteTokens", usage.cacheWriteTokens());
        num(sb, "reasoningTokens", usage.reasoningTokens());
        sb.append('}');
        return sb.toString();
    }

    private static TokenUsage decodeUsage(Map<?, ?> m) {
        return new TokenUsage(rInt(m, "inputTokens"), rInt(m, "outputTokens"),
                optInt(m, "cacheReadTokens"), optInt(m, "cacheWriteTokens"),
                optInt(m, "reasoningTokens"));
    }

    private static String encodeTurnEndReason(TurnEndReason reason) {
        var sb = new StringBuilder(48);
        switch (reason) {
            case TurnEndReason.Completed ignored -> sb.append("{\"k\":\"completed\"}");
            case TurnEndReason.Blocked ignored -> sb.append("{\"k\":\"blocked\"}");
            case TurnEndReason.MaxTokens ignored -> sb.append("{\"k\":\"max-tokens\"}");
            case TurnEndReason.Interrupted ignored -> sb.append("{\"k\":\"interrupted\"}");
            case TurnEndReason.Aborted a -> {
                sb.append("{\"k\":\"aborted\"");
                raw(sb, "cause", encodeCancelCause(a.reason()));
                sb.append('}');
            }
            case TurnEndReason.Error err -> {
                sb.append("{\"k\":\"error\"");
                raw(sb, "failure", encodeFailure(err.failure()));
                sb.append('}');
            }
        }
        return sb.toString();
    }

    private static TurnEndReason decodeTurnEndReason(Map<?, ?> m) {
        return switch (rStr(m, "k")) {
            case "completed" -> new TurnEndReason.Completed();
            case "blocked" -> new TurnEndReason.Blocked();
            case "max-tokens" -> new TurnEndReason.MaxTokens();
            case "interrupted" -> new TurnEndReason.Interrupted();
            case "aborted" -> new TurnEndReason.Aborted(
                    decodeCancelCause(asMap(m.get("cause"), "cause")));
            case "error" -> new TurnEndReason.Error(decodeFailure(asMap(m.get("failure"), "failure")));
            default -> throw new IllegalArgumentException("未知 turn 结束原因: " + rStr(m, "k"));
        };
    }

    private static String encodeCancelCause(TurnEndCancelCause cause) {
        var sb = new StringBuilder(32);
        switch (cause) {
            case TurnEndCancelCause.User ignored -> sb.append("{\"k\":\"user\"}");
            case TurnEndCancelCause.Parent ignored -> sb.append("{\"k\":\"parent\"}");
            case TurnEndCancelCause.Hook h -> {
                sb.append("{\"k\":\"hook\"");
                str(sb, "reason", h.reason());
                sb.append('}');
            }
            case TurnEndCancelCause.Disposed ignored -> sb.append("{\"k\":\"disposed\"}");
            case TurnEndCancelCause.Legacy ignored -> sb.append("{\"k\":\"legacy\"}");
        }
        return sb.toString();
    }

    private static TurnEndCancelCause decodeCancelCause(Map<?, ?> m) {
        return switch (rStr(m, "k")) {
            case "user" -> new TurnEndCancelCause.User();
            case "parent" -> new TurnEndCancelCause.Parent();
            case "hook" -> new TurnEndCancelCause.Hook(rStr(m, "reason"));
            case "disposed" -> new TurnEndCancelCause.Disposed();
            case "legacy" -> new TurnEndCancelCause.Legacy();
            default -> throw new IllegalArgumentException("未知取消原因: " + rStr(m, "k"));
        };
    }

    // ---- 嵌套模型：杂项 ----

    private static String encodeTodos(List<TodoItem> todos) {
        var sb = new StringBuilder(48);
        sb.append('[');
        for (var todo : todos) {
            if (sb.length() > 1) {
                sb.append(',');
            }
            sb.append("{\"content\":").append(escape(todo.content()))
                    .append(",\"status\":\"").append(todo.status().name()).append("\"}");
        }
        sb.append(']');
        return sb.toString();
    }

    private static List<TodoItem> decodeTodos(Object raw) {
        var list = asList(raw, "todos");
        var result = new ArrayList<TodoItem>(list.size());
        for (var item : list) {
            var m = asMap(item, "todoItem");
            result.add(new TodoItem(rStr(m, "content"), TodoStatus.valueOf(rStr(m, "status"))));
        }
        return result;
    }

    private static String encodeEpochHeader(EpochHeader header) {
        var sb = new StringBuilder(96);
        sb.append("{\"provider\":").append(escape(header.provider()))
                .append(",\"model\":").append(escape(header.model()));
        str(sb, "system", header.system());
        if (header.tools() != null) {
            var tools = new StringBuilder(64);
            tools.append('[');
            for (var tool : header.tools()) {
                if (tools.length() > 1) {
                    tools.append(',');
                }
                tools.append("{\"name\":").append(escape(tool.name()));
                str(tools, "description", tool.description());
                // parameters 是任意 JSON Schema 对象，按原样转交 JsonParser
                tools.append(",\"parameters\":").append(dev.duo.util.JsonWriter.toJson(tool.parameters()));
                tools.append('}');
            }
            tools.append(']');
            raw(sb, "tools", tools.toString());
        }
        num(sb, "temperature", header.temperature());
        num(sb, "maxTokens", header.maxTokens());
        sb.append('}');
        return sb.toString();
    }

    private static EpochHeader decodeEpochHeader(Map<?, ?> m) {
        List<ToolSchema> tools = null;
        if (m.get("tools") != null) {
            var list = asList(m.get("tools"), "tools");
            tools = new ArrayList<>(list.size());
            for (var item : list) {
                var t = asMap(item, "toolSchema");
                @SuppressWarnings("unchecked")
                var parameters = (Map<String, Object>) t.get("parameters");
                tools.add(new ToolSchema(rStr(t, "name"), rStr(t, "description"), parameters));
            }
        }
        return new EpochHeader(rStr(m, "provider"), rStr(m, "model"), rStr(m, "system"), tools,
                m.get("temperature") == null ? null : ((Number) m.get("temperature")).doubleValue(),
                optInt(m, "maxTokens"));
    }

    // ---- 表面操作 ----

    private static void encodeSurface(StringBuilder sb, SurfaceOp surfaceOp) {
        if (surfaceOp instanceof SurfaceOp.Replace replace) {
            sb.append(",\"surfaceOp\":{\"k\":\"replace\",\"start\":").append(replace.start())
                    .append(",\"end\":").append(replace.end()).append('}');
        } else if (surfaceOp instanceof SurfaceOp.Append) {
            sb.append(",\"surfaceOp\":{\"k\":\"append\"}");
        }
    }

    private static SurfaceOp decodeSurface(Object raw) {
        if (raw == null) {
            return null;
        }
        var m = asMap(raw, "surfaceOp");
        return switch (rStr(m, "k")) {
            case "append" -> new SurfaceOp.Append();
            case "replace" -> new SurfaceOp.Replace(rInt(m, "start"), rInt(m, "end"));
            default -> throw new IllegalArgumentException("未知表面操作: " + rStr(m, "k"));
        };
    }

    private static int[] decodeSeqs(Object raw) {
        if (raw == null) {
            return null;
        }
        var list = asList(raw, "sourceEventSeqs");
        var result = new int[list.size()];
        for (int i = 0; i < list.size(); i++) {
            result[i] = ((Number) list.get(i)).intValue();
        }
        return result;
    }

    // ---- 读写小工具 ----

    private static void str(StringBuilder sb, String key, String value) {
        if (value != null) {
            sb.append(',').append(escape(key)).append(':').append(escape(value));
        }
    }

    private static void num(StringBuilder sb, String key, Number value) {
        if (value != null) {
            sb.append(',').append(escape(key)).append(':').append(value);
        }
    }

    private static void bool(StringBuilder sb, String key, boolean value) {
        sb.append(',').append(escape(key)).append(':').append(value);
    }

    private static void raw(StringBuilder sb, String key, String encodedJson) {
        sb.append(',').append(escape(key)).append(':').append(encodedJson);
    }

    /** JSON 字符串字面量转义；实现复用 {@link dev.duo.util.JsonWriter#quote}，保证全库一致。 */
    static String escape(String value) {
        return dev.duo.util.JsonWriter.quote(value);
    }

    private static Map<?, ?> asMap(Object raw, String what) {
        if (raw instanceof Map<?, ?> m) {
            return m;
        }
        throw new IllegalArgumentException(what + " 不是 JSON 对象");
    }

    private static List<?> asList(Object raw, String what) {
        if (raw instanceof List<?> list) {
            return list;
        }
        throw new IllegalArgumentException(what + " 不是 JSON 数组");
    }

    private static String rStr(Map<?, ?> m, String key) {
        var v = m.get(key);
        return v == null ? null : String.valueOf(v);
    }

    private static int rInt(Map<?, ?> m, String key) {
        var v = m.get(key);
        require(v instanceof Number, "字段 " + key + " 缺失或不是数字");
        return ((Number) v).intValue();
    }

    private static long rLong(Map<?, ?> m, String key) {
        var v = m.get(key);
        require(v instanceof Number, "字段 " + key + " 缺失或不是数字");
        return ((Number) v).longValue();
    }

    private static Integer optInt(Map<?, ?> m, String key) {
        var v = m.get(key);
        return v instanceof Number number ? number.intValue() : null;
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }
}
