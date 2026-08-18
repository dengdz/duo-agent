package dev.dsh.session.types;

/**
 * 日志中的请求状态快照：调用配置、系统提示词和工具。
 * <p>
 * 对应 TS 源码中的 {@code EpochHeader}。
 * </p>
 */
public record EpochHeader(
        String provider,
        String model,
        String system,
        java.util.List<dev.dsh.llm.types.ToolSchema> tools,
        Double temperature,
        Integer maxTokens
) {
}