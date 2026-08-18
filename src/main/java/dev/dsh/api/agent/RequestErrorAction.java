package dev.dsh.api.agent;

/**
 * 模型请求恢复操作。
 * <p>
 * 对应 TS 源码中的 {@code RequestErrorAction}。
 * </p>
 *
 * @author zhangyl
 * @date 2026-08-18
 */
public sealed interface RequestErrorAction {

    /** 重试失败的模型请求。 */
    record Retry() implements RequestErrorAction {}
}