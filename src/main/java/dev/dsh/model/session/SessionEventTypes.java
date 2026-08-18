package dev.dsh.model.session;

import java.util.Set;

/**
 * 会话事件类型常量。
 * 所有事件类型字符串集中定义在此，避免魔法值散落在各处。
 *
 * @author zhangyl
 * @date 2026-08-18
 */
public final class SessionEventTypes {

    private SessionEventTypes() {}

    // ---- 非表面事件 ----
    public static final String TURN_START = "turn/start";
    public static final String TURN_END = "turn/end";
    public static final String STEP_START = "step/start";
    public static final String STEP_END = "step/end";
    public static final String ASSISTANT_CHUNK = "assistant/chunk";
    public static final String TOOL_CALL = "tool/call";
    public static final String TODO_WRITE = "todo/write";
    public static final String REQUEST_HEADER = "request/header";
    public static final String REQUEST_CONTEXT = "request/context";
    public static final String SESSION_END_SEED = "session/end-seed";

    // ---- 表面事件 ----
    public static final String USER_MESSAGE = "user/message";
    public static final String ASSISTANT_MESSAGE = "assistant/message";
    public static final String TOOL_RESULT = "tool/result";

    // ---- 内容块类型 ----
    public static final String BLOCK_TEXT = "text";
    public static final String BLOCK_REASONING = "reasoning";
    public static final String BLOCK_TOOL_CALL = "tool-call";
    public static final String BLOCK_TOOL_RESULT = "tool-result";

    // ---- Surface 事件类型集合 ----
    public static final Set<String> SURFACE_EVENT_TYPES = Set.of(
            USER_MESSAGE, ASSISTANT_MESSAGE, TOOL_RESULT
    );
}