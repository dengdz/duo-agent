package dev.dsh.exception;

/**
 * 会话操作（创建、fork、追加）失败时抛出的异常。
 */
public class SessionException extends RuntimeException {

    public SessionException(String message) {
        super(message);
    }

    public SessionException(String message, Throwable cause) {
        super(message, cause);
    }
}