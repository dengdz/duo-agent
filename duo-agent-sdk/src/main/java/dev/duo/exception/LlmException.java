package dev.duo.exception;

/**
 * LLM 调用失败时抛出的异常。
 *
 * @author zhangyl
 * @date 2026-08-18
 */
public class LlmException extends RuntimeException {

    public LlmException(String message) {
        super(message);
    }

    public LlmException(String message, Throwable cause) {
        super(message, cause);
    }
}