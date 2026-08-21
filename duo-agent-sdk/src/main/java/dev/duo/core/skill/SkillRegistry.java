package dev.duo.core.skill;

import dev.duo.api.skill.Skill;
import dev.duo.api.skill.SkillCandidate;
import dev.duo.api.skill.SkillProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 技能注册表：合并多个 Provider，按 rank 裁决重名。
 * <p>
 * 线程安全：候选缓存使用 ConcurrentHashMap，重建过程在 synchronized 方法内完成。
 * </p>
 *
 * @author zhangyl
 * @date 2026-08-19
 */
public class SkillRegistry {

    private static final Logger logger = LoggerFactory.getLogger(SkillRegistry.class);

    private final List<SkillProvider> providers = new ArrayList<>();

    /** 缓存：name → 胜出的候选。 */
    private final Map<String, SkillCandidate> candidateCache = new ConcurrentHashMap<>();

    /** 缓存：name → 胜出候选所属的 Provider（load 时直接定位，避免锁外遍历）。 */
    private final Map<String, SkillProvider> providerBySkill = new ConcurrentHashMap<>();

    /** 缓存失效标志。 */
    private volatile boolean cacheValid = false;

    /**
     * 注册一个技能提供者。
     * @param provider 提供者实例
     */
    public synchronized void register(SkillProvider provider) {
        Objects.requireNonNull(provider, "provider must not be null");
        providers.add(provider);
        cacheValid = false;
        logger.debug("SkillProvider 已注册: {}", provider.getClass().getSimpleName());
    }

    /**
     * 列举所有技能候选（合并后，重名已裁决）。
     * @return 技能候选列表
     */
    public List<SkillCandidate> listAll() {
        ensureCacheValid();
        return List.copyOf(candidateCache.values());
    }

    /**
     * 按名称加载完整技能定义（从胜出的 Provider 加载）。
     * @param name 技能名称
     * @return 完整技能，如果不存在则返回 null
     */
    public Skill load(String name) {
        Objects.requireNonNull(name, "name must not be null");

        ensureCacheValid();
        var candidate = candidateCache.get(name);
        if (candidate == null) {
            logger.debug("技能 \"{}\" 不存在", name);
            return null;
        }

        var provider = providerBySkill.get(name);
        if (provider == null) {
            logger.warn("技能 \"{}\" 在候选中但缺少 Provider 映射", name);
            return null;
        }

        try {
            var skill = provider.load(name);
            if (skill != null) {
                logger.debug("技能 \"{}\" 已加载（来源：{}）", name, skill.source());
                return skill;
            }
        } catch (IOException e) {
            logger.warn("加载技能 \"{}\" 失败（provider: {}）", name, provider.getClass().getSimpleName(), e);
        }

        logger.warn("技能 \"{}\" 在候选中但无法加载", name);
        return null;
    }

    /**
     * 确保候选缓存有效：合并所有 Provider 并按 rank 裁决重名。
     */
    private synchronized void ensureCacheValid() {
        if (cacheValid) {
            return;
        }

        candidateCache.clear();
        providerBySkill.clear();
        var merged = new HashMap<String, SkillCandidate>();
        var winnerProviders = new HashMap<String, SkillProvider>();

        for (var provider : providers) {
            try {
                var candidates = provider.discover();
                for (var candidate : candidates) {
                    var winner = merged.merge(candidate.name(), candidate, (existing, newOne) ->
                            existing.rank() <= newOne.rank() ? existing : newOne
                    );
                    // merge 返回胜出者；仅当胜出者来自当前 Provider 时更新映射
                    if (winner == candidate) {
                        winnerProviders.put(candidate.name(), provider);
                    }
                }
            } catch (IOException e) {
                logger.warn("Provider discover 失败: {}", provider.getClass().getSimpleName(), e);
            }
        }

        candidateCache.putAll(merged);
        providerBySkill.putAll(winnerProviders);
        cacheValid = true;
        logger.debug("技能候选缓存已刷新，共 {} 个", candidateCache.size());
    }
}
