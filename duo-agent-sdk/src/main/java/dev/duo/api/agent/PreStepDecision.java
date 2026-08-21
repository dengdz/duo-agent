package dev.duo.api.agent;

import dev.duo.model.llm.Message;

import java.util.List;

/**
 * 循环是否以及以哪些消息进入提议的 step。
 * <p>
 * </p>
 *
 * @author zhangyl
 * @date 2026-08-18
 */
public sealed interface PreStepDecision {

    /** 拒绝此 step，不进入模型调用。 */
    record Reject() implements PreStepDecision {}

    /** 以指定消息进入 step。 */
    record Enter(List<Message.UserMessage> messages) implements PreStepDecision {}
}