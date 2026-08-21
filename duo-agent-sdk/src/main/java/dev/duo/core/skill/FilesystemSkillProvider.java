package dev.duo.core.skill;

import dev.duo.api.skill.Skill;
import dev.duo.api.skill.SkillCandidate;
import dev.duo.api.skill.SkillProvider;
import dev.duo.api.skill.SkillSource;
import dev.duo.util.FrontmatterParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * 文件系统技能提供者：从目录扫描 SKILL.md / *.md 文件。
 * <p>
 * 解析 YAML frontmatter，校验 name 格式，缓存候选列表。
 * 线程安全：discover/load 同步保护缓存（discover 是一次性扫描，无热更新）。
 * </p>
 *
 * @author zhangyl
 * @date 2026-08-19
 */
public class FilesystemSkillProvider implements SkillProvider {

    private static final Logger logger = LoggerFactory.getLogger(FilesystemSkillProvider.class);

    private final Path root;
    private final SkillSource source;
    private final String provider;
    private final int rank;

    /** 缓存：name → 文件路径。 */
    private final Map<String, Path> pathCache = new HashMap<>();

    /** 缓存：候选列表。 */
    private List<SkillCandidate> candidateCache;

    /**
     * 构造文件系统技能提供者。
     * @param root 技能根目录
     * @param source 来源标识
     * @param provider 提供者名称
     * @param rank 优先级（越小优先级越高）
     */
    public FilesystemSkillProvider(Path root, SkillSource source, String provider, int rank) {
        this.root = Objects.requireNonNull(root, "root must not be null");
        this.source = Objects.requireNonNull(source, "source must not be null");
        this.provider = Objects.requireNonNull(provider, "provider must not be null");
        this.rank = rank;
    }

    @Override
    public synchronized List<SkillCandidate> discover() throws IOException {
        if (candidateCache != null) {
            return candidateCache;
        }

        if (!Files.exists(root)) {
            logger.debug("技能目录不存在: {}", root);
            candidateCache = List.of();
            return candidateCache;
        }

        var candidates = new ArrayList<SkillCandidate>();

        try (Stream<Path> stream = Files.walk(root)) {
            var files = stream
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".md"))
                    .toList();

            for (var file : files) {
                try {
                    var candidate = parseCandidate(file);
                    if (candidate != null) {
                        candidates.add(candidate);
                        pathCache.put(candidate.name(), file);
                    }
                } catch (Exception e) {
                    logger.warn("解析技能文件失败: {}", file, e);
                }
            }
        }

        candidateCache = List.copyOf(candidates);
        logger.debug("从 {} 发现 {} 个技能", root, candidates.size());
        return candidateCache;
    }

    @Override
    public synchronized Skill load(String name) throws IOException {
        Objects.requireNonNull(name, "name must not be null");

        // 确保缓存已建立
        if (candidateCache == null) {
            discover();
        }

        var path = pathCache.get(name);
        if (path == null) {
            return null;
        }

        var content = Files.readString(path);
        var result = FrontmatterParser.parse(content);

        var skillName = result.frontmatter().get("name");
        var description = result.frontmatter().getOrDefault("description", "");

        if (skillName == null || !skillName.equals(name)) {
            logger.warn("技能 \"{}\" 的 frontmatter name 不匹配: {}", name, skillName);
            return null;
        }

        return new Skill(
                name,
                description,
                result.body(),
                source,
                provider,
                path.toString()
        );
    }

    /**
     * 解析单个文件为候选（只提取 frontmatter，不读 body）。
     * @param file 文件路径
     * @return 候选，如果格式非法或 name 不合法则返回 null
     */
    private SkillCandidate parseCandidate(Path file) throws IOException {
        var content = Files.readString(file);

        FrontmatterParser.ParseResult result;
        try {
            result = FrontmatterParser.parse(content);
        } catch (IllegalArgumentException e) {
            logger.warn("frontmatter 解析失败: {}", file, e);
            return null;
        }

        var name = result.frontmatter().get("name");
        if (name == null || name.isBlank()) {
            logger.warn("技能文件缺少 name 字段: {}", file);
            return null;
        }

        if (!FrontmatterParser.isValidSkillName(name)) {
            logger.warn("技能名称格式非法: {} (文件: {})", name, file);
            return null;
        }

        var description = result.frontmatter().getOrDefault("description", "");

        return new SkillCandidate(
                name,
                description,
                source,
                provider,
                rank,
                file.toString()
        );
    }
}
