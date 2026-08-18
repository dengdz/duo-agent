package dev.dsh.api.agent;

import dev.dsh.model.llm.Message;

import java.util.List;

/**
 * 循环是否以及以哪些消息进入提议的 step。
 * <p>
 * 对应 TS 源码中的 {@code PreStepDecision}。
 * </p>
 */
public sealed interface PreStepDecision {

    /** 拒绝此 step，不进入模型调用。 */
    record Reject() implements PreStepDecision {}

    /** 以指定消息进入 step。 */
    record Enter(List<Message.UserMessage> messages) implements PreStepDecision {}
}