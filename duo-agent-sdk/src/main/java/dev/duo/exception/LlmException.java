package dev.duo.exception;

/**
 * LLM 调用失败时抛出的异常。
 *
 * @author zhangyl
 * @date 2026-08-18
 */
public class LlmException extends RuntimeException {

    /** 触发失败的 HTTP 状态码；网络异常等无状态码场景为 null。 */
    private final Integer status;

    public LlmException(String message) {
        this(message, null, null);
    }

    public LlmException(String message, Throwable cause) {
        this(message, null, cause);
    }

    public LlmException(String message, Integer status) {
        this(message, status, null);
    }

    private LlmException(String message, Integer status, Throwable cause) {
        super(message, cause);
        this.status = status;
    }

    public Integer status() {
        return status;
    }
}