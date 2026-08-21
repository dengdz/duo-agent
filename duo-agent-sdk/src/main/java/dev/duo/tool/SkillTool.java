package dev.duo.tool;

import dev.duo.core.skill.SkillRegistry;
import dev.duo.model.llm.ToolDefinition;
import dev.duo.model.llm.ToolExecutor;
import dev.duo.model.llm.ToolExecutionResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * skill 工具：按需加载技能的完整正文。
 * <p>
 * 对应 DSH 的 {@code tool-skill} 包。
 * 模型先从 available_skills 目录看到技能摘要，再调用此工具获取完整指令。
 * </p>
 *
 * @author zhangyl
 * @date 2026-08-19
 */
public class SkillTool {

    private static final Logger logger = LoggerFactory.getLogger(SkillTool.class);

    private static final String TOOL_NAME = "skill";
    private static final String ARG_NAME = "name";

    private final SkillRegistry registry;

    public SkillTool(SkillRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry must not be null");
    }

    public ToolDefinition getDefinition() {
        return new ToolDefinition(
                TOOL_NAME,
                "Load the full instructions for an available skill. Call this with the exact skill name from the session skill catalog before acting on a task that names or clearly matches that skill.",
                Map.of(
                        "type", "object",
                        "properties", Map.of(
                                ARG_NAME, Map.of(
                                        "type", "string",
                                        "description", "技能名称（必须是可用技能目录中的精确名称）"
                                )
                        ),
                        "required", List.of(ARG_NAME)
                ),
                ToolExecutor.of(this::execute)
        );
    }

    private ToolExecutionResult execute(Map<String, Object> args) {
        var name = args.get(ARG_NAME);
        if (name == null || name.toString().isBlank()) {
            return new ToolExecutionResult("错误：缺少技能名称");
        }

        var skillName = name.toString().trim();

        try {
            var skill = registry.load(skillName);
            if (skill == null) {
                return new ToolExecutionResult("技能 \"" + skillName + "\" 不存在");
            }
            return new ToolExecutionResult(skill.content());
        } catch (Exception e) {
            logger.warn("加载技能 \"{}\" 失败", skillName, e);
            return new ToolExecutionResult("加载技能 \"" + skillName + "\" 失败: " + e.getMessage());
        }
    }
}
