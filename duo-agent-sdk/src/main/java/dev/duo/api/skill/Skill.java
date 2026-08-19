package dev.duo.api.skill;

import java.util.Objects;

/**
 * 技能定义：名称、描述和完整正文。
 * <p>
 * 对应 DSH 的 {@code SkillDefinition}（简化版，v1 裁剪 invocation policy 和 metadata）。
 * </p>
 *
 * @author zhangyl
 * @date 2026-08-19
 */
public record Skill(
        String name,
        String description,
        String content,
        SkillSource source,
        String provider,
        String path
) {
    public Skill {
        Objects.requireNonNull(name, "skill name must not be null");
        Objects.requireNonNull(description, "skill description must not be null");
        Objects.requireNonNull(content, "skill content must not be null");
        Objects.requireNonNull(source, "skill source must not be null");
        Objects.requireNonNull(provider, "skill provider must not be null");

        if (name.isBlank()) {
            throw new IllegalArgumentException("skill name 不能为空");
        }
    }
}
