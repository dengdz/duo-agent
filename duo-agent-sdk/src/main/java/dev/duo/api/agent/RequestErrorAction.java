package dev.duo.api.agent;

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

    /** 重试失败的模型请求：重新派生消息并重新构造请求。 */
    record Retry() implements RequestErrorAction {}

    /** 保持失败：step 以失败结束（无人接管恢复权时的默认动作）。 */
    record Fail() implements RequestErrorAction {}
}