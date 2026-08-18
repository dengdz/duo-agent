package dev.dsh.agent;

/**
 * {@link Agent#cancel} 的选项。
 * <p>
 * 对应 TS 源码中的 {@code CancelOptions}。
 * </p>
 */
public record CancelOptions(
        /** 保留排队和 steering 的 inbox 项目，而不是丢弃它们。 */
        boolean keepInbox
) {
    public CancelOptions() {
        this(false);
    }
}