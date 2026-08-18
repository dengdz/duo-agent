package dev.dsh.llm.message;

import dev.dsh.util.CallId;

/**
 * 消息（或注入内容）的来源。
 * <p>
 * 对应 TS 源码中的 {@code MessageSourceMap}。
 * </p>
 */
public sealed interface MessageSource {

    /** 由用户产生。 */
    record User() implements MessageSource {}

    /** 由插件产生。 */
    record Plugin(String plugin) implements MessageSource {}

    /** 由模型产生（助手消息）。 */
    record Model(String provider, String model) implements MessageSource {}

    /** 由工具执行产生。 */
    record Tool(CallId callId) implements MessageSource {}
}