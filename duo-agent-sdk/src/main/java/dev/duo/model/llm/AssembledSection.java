package dev.duo.model.llm;

/**
 * 组装后的 prompt 片段之一。
 * <p>
 * </p>
 *
 * @author zhangyl
 * @date 2026-08-18
 */
public record AssembledSection(
        String name,
        String text
) {
}