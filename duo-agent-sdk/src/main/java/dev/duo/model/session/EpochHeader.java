package dev.duo.model.session;

/**
 * 日志中的请求状态快照：调用配置、系统提示词和工具。
 * <p>
 * </p>
 *
 * @author zhangyl
 * @date 2026-08-18
 */
public record EpochHeader(
        String provider,
        String model,
        String system,
        java.util.List<dev.duo.model.llm.ToolSchema> tools,
        Double temperature,
        Integer maxTokens
) {
}