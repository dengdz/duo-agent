package dev.duo.api.skill;

import java.io.IOException;
import java.util.List;

/**
 * 技能提供者接口（Capability Seam 的 Service Provider 角色）。
 * <p>
 * 对应 DSH 的 skill provider 体系。
 * 实现者负责从特定来源（文件系统、数据库、网络等）发现和加载技能。
 * </p>
 *
 * @author zhangyl
 * @date 2026-08-19
 */
public interface SkillProvider {

    /**
     * 列举所有可用技能候选。
     * @return 技能候选列表（不含完整正文，只有名称、描述和优先级）
     * @throws IOException 如果发现过程失败
     */
    List<SkillCandidate> discover() throws IOException;

    /**
     * 按名称加载技能的完整定义。
     * @param name 技能名称（kebab-case）
     * @return 完整技能定义，如果不存在则返回 null
     * @throws IOException 如果加载失败
     */
    Skill load(String name) throws IOException;
}
