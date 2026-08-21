package dev.duo.model.llm;

/**
 * 组装后的动态上下文贡献之一。
 * <p>
 * </p>
 *
 * @author zhangyl
 * @date 2026-08-18
 */
public record AssembledContext(
        String name,
        String text
) {
}