package dev.dsh.api.agent;

/**
 * 模型请求恢复操作。
 * <p>
 * 对应 TS 源码中的 {@code RequestErrorAction}。
 * </p>
 */
public sealed interface RequestErrorAction {

    /** 重试失败的模型请求。 */
    record Retry() implements RequestErrorAction {}
}