package dev.dsh.exception;

/**
 * Agent 驱动循环（turn/step）执行失败时抛出的异常。
 */
public class AgentLoopException extends Exception {

    public AgentLoopException(String message) {
        super(message);
    }

    public AgentLoopException(String message, Throwable cause) {
        super(message, cause);
    }
}