package dev.duo.api.skill;

import java.util.Objects;

/**
 * 技能候选：携带优先级 rank 的技能摘要。
 * <p>
 * 用于多 Provider 合并时的重名裁决。rank 越小优先级越高（project < user < bundled）。
 * </p>
 *
 * @author zhangyl
 * @date 2026-08-19
 */
public record SkillCandidate(
        String name,
        String description,
        SkillSource source,
        String provider,
        int rank,
        String path
) {
    public SkillCandidate {
        Objects.requireNonNull(name, "skill name must not be null");
        Objects.requireNonNull(description, "skill description must not be null");
        Objects.requireNonNull(source, "skill source must not be null");
        Objects.requireNonNull(provider, "skill provider must not be null");

        if (name.isBlank()) {
            throw new IllegalArgumentException("skill name 不能为空");
        }
    }
}
