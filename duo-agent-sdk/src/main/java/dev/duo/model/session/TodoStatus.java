package dev.duo.model.session;

import java.util.Arrays;
import java.util.List;

/**
 * 任务条目的生命周期状态。
 * <p>
 * 协议字符串集中在此定义，避免魔法值散落。
 * </p>
 *
 * @author zhangyl
 * @date 2026-08-18
 */
public enum TodoStatus {
    /** 尚未开始。 */
    PENDING("pending"),
    /** 进行中。 */
    IN_PROGRESS("in_progress"),
    /** 已完成。 */
    COMPLETED("completed");

    private final String protocol;

    TodoStatus(String protocol) {
        this.protocol = protocol;
    }

    /** LLM 协议中的字符串值。 */
    public String protocol() {
        return protocol;
    }

    /** 全部协议字符串，用于工具 schema 的 enum 列表。 */
    public static List<String> protocolValues() {
        return Arrays.stream(values()).map(TodoStatus::protocol).toList();
    }

    /** 从协议字符串解析；非法值抛出 IllegalArgumentException。 */
    public static TodoStatus fromProtocol(String value) {
        for (var status : values()) {
            if (status.protocol.equals(value)) {
                return status;
            }
        }
        throw new IllegalArgumentException("未知任务状态: " + value);
    }
}