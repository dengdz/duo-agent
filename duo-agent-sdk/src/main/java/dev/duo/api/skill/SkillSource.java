package dev.duo.api.skill;

/**
 * 技能来源标识。
 * <p>
 * 对应 DSH 的 {@code SkillSource} 类型。
 * 用于区分技能的来源层级，决定重名时的优先级。
 * </p>
 *
 * @author zhangyl
 * @date 2026-08-19
 */
public enum SkillSource {
    /** 项目级技能（最高优先级）。 */
    PROJECT("project"),
    /** 用户级技能。 */
    USER("user"),
    /** 内置技能（最低优先级）。 */
    BUNDLED("bundled"),
    /** 自定义来源。 */
    CUSTOM("custom");

    private final String protocol;

    SkillSource(String protocol) {
        this.protocol = protocol;
    }

    /**
     * 获取来源的协议标识字符串。
     * @return 协议标识（如 "project"）
     */
    public String protocol() {
        return protocol;
    }
}
