package dev.dsh.model.llm;

/**
 * 组装后的 prompt 片段之一。
 * <p>
 * 对应 TS 源码中的 {@code AssembledSection}。
 * </p>
 */
public record AssembledSection(
        String name,
        String text
) {
}