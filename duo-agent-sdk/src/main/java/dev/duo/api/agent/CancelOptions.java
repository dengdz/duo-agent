package dev.duo.api.agent;

/**
 * {@link Agent#cancel} 的选项。
 * <p>
 * </p>
 *
 * @author zhangyl
 * @date 2026-08-18
 */
public record CancelOptions(
        /** 保留排队和 steering 的 inbox 项目，而不是丢弃它们。 */
        boolean keepInbox
) {
    public CancelOptions() {
        this(false);
    }
}