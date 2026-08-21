package dev.duo.core.skill;

import dev.duo.api.skill.SkillCandidate;
import dev.duo.model.llm.PromptSection;

import java.util.List;

/**
 * 技能目录 Section：将可用技能列表注入系统提示。
 * <p>
 * 模型看到摘要后调用 skill 工具按需加载完整正文。
 * </p>
 *
 * @author zhangyl
 * @date 2026-08-19
 */
public class SkillCatalogSection {

    private static final int DEFAULT_ORDER = 50;
    private static final String SECTION_NAME = "skill:catalog";

    private SkillCatalogSection() {
        // 工具类，禁止实例化
    }

    /**
     * 创建技能目录 PromptSection。
     * @param registry 技能注册表
     * @return 系统提示 section
     */
    public static PromptSection create(SkillRegistry registry) {
        var candidates = registry.listAll();

        if (candidates.isEmpty()) {
            // 无技能时不注入目录
            return new PromptSection(SECTION_NAME, DEFAULT_ORDER, "", false);
        }

        var catalog = buildCatalog(candidates);
        return new PromptSection(SECTION_NAME, DEFAULT_ORDER, catalog, false);
    }

    /**
     * 构建目录文本。
     */
    private static String buildCatalog(List<SkillCandidate> candidates) {
        var sb = new StringBuilder();
        sb.append("A skill is a reusable set of task-specific instructions.\n");
        sb.append("The following skills are available in this session:\n\n");
        sb.append("<available_skills>\n");

        for (var candidate : candidates) {
            sb.append("- ").append(candidate.name()).append(": ")
              .append(candidate.description()).append("\n");
        }

        sb.append("</available_skills>\n\n");
        sb.append("If the user names a skill, or the task clearly matches a skill's description, ");
        sb.append("call the `skill` tool with the exact skill name before taking task actions. ");
        sb.append("Load all applicable skills, then follow their full instructions. ");
        sb.append("This catalog contains summaries only; do not infer or follow a skill's ");
        sb.append("instructions until it has been loaded.");

        return sb.toString();
    }
}
