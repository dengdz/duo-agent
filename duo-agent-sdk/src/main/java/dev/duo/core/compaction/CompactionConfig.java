package dev.duo.core.compaction;

import java.time.Duration;

/**
 * 压缩配置：阈值、保留尾巴与重试次数。
 * <p>
 * v1 简化：全局一份（不按模型策略区分），token 以估算计。
 * </p>
 *
 * @author zhangyl
 * @date 2026-08-19
 */
public record CompactionConfig(
        /** 表面估算 token 超过该值时触发压缩。 */
        int thresholdTokens,
        /** 压缩必须保留的最近对话尾巴的估算 token 下限。 */
        int retainTokens,
        /** 一次触发内最多执行的压缩次数（超过仍达阈值则记录并放行）。 */
        int maxAttempts,
        /** 摘要调用等待时长。 */
        Duration summarizationTimeout
) {
    public CompactionConfig {
        if (thresholdTokens < 1) {
            throw new IllegalArgumentException("thresholdTokens 必须 >= 1");
        }
        if (retainTokens < 0) {
            throw new IllegalArgumentException("retainTokens 必须 >= 0");
        }
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts 必须 >= 1");
        }
    }

    public CompactionConfig(int thresholdTokens, int retainTokens, int maxAttempts) {
        this(thresholdTokens, retainTokens, maxAttempts, Duration.ofSeconds(60));
    }
}
