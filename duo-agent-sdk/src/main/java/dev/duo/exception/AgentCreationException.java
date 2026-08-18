package dev.duo.exception;

/**
 * Agent 创建或恢复失败时抛出的异常。
 *
 * @author zhangyl
 * @date 2026-08-18
 */
public class AgentCreationException extends Exception {

    public AgentCreationException(String message) {
        super(message);
    }

    public AgentCreationException(String message, Throwable cause) {
        super(message, cause);
    }
}