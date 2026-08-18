package dev.dsh.exception;

/**
 * LLM 调用失败时抛出的异常。
 */
public class LlmException extends RuntimeException {

    public LlmException(String message) {
        super(message);
    }

    public LlmException(String message, Throwable cause) {
        super(message, cause);
    }
}