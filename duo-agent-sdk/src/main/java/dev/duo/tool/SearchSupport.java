package dev.duo.tool;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.Paths;
import java.util.Set;

/**
 * grep/glob 搜索工具的共享支撑：遍历排除目录、二进制文件探测、显示路径转换、include glob 校验。
 * <p>
 * 对应 DSH {@code tool-fs-search} 的 search-core 遍历约定（v1 纯 Java 实现，
 * 不 spawn ripgrep）：VCS 元数据目录不进入；疑似二进制文件跳过；结果路径相对
 * 工作目录显示。
 * </p>
 *
 * @author zhangyl
 * @date 2026-08-19
 */
final class SearchSupport {

    /** 二进制探测的采样字节数。 */
    private static final int BINARY_SNIFF_BYTES = 1024;

    /** NUL 字节，出现即判定为二进制。 */
    private static final byte NUL = 0;

    /** 遍历时永不进入的 VCS 元数据目录名。 */
    private static final Set<String> EXCLUDED_DIRS = Set.of(".git", ".svn", ".hg", ".bzr", ".jj", ".sl");

    private SearchSupport() {
        // 工具类，禁止实例化
    }

    /**
     * 判断路径的任一层目录名是否被排除（VCS 元数据目录）。
     * @param path 待检查路径
     * @return 是否应跳过
     */
    static boolean isExcludedPath(Path path) {
        for (var segment : path) {
            if (EXCLUDED_DIRS.contains(segment.toString())) {
                return true;
            }
        }
        return false;
    }

    /**
     * 粗略探测文件是否为二进制：采样前 1KB，包含 NUL 字节即判定为二进制。
     * @param file 待探测文件
     * @return 是否疑似二进制
     */
    static boolean isProbablyBinary(Path file) {
        byte[] sample;
        try (var input = Files.newInputStream(file)) {
            sample = input.readNBytes(BINARY_SNIFF_BYTES);
        } catch (IOException e) {
            return true;
        }
        for (var b : sample) {
            if (b == NUL) {
                return true;
            }
        }
        return false;
    }

    /**
     * 将路径转为相对工作目录的显示路径；工作目录之外的保持绝对路径。
     * @param path 待转换路径
     * @return 显示路径
     */
    static String toRelativeDisplay(Path path) {
        var cwd = Paths.get("").toAbsolutePath().normalize();
        var normalized = path.toAbsolutePath().normalize();
        if (normalized.startsWith(cwd)) {
            var relative = cwd.relativize(normalized);
            return relative.toString().isEmpty() ? "." : relative.toString();
        }
        return normalized.toString();
    }

    /**
     * 编译单个正向 include glob（DSH 语义）：拒绝空串、{@code !} 前缀与顶层逗号列表，
     * 允许 {@code {a,b}} 交替。只匹配文件名，不匹配整路径。
     * @param include 待校验的 glob
     * @return 编译好的文件名匹配器
     * @throws IllegalArgumentException 当 glob 为空、取反或逗号列表时
     */
    static PathMatcher compileInclude(String include) {
        if (include.isBlank()) {
            throw new IllegalArgumentException("include 必须是非空 glob");
        }
        if (include.startsWith("!")) {
            throw new IllegalArgumentException("include 必须是正向 glob，不支持取反（!…）");
        }
        int braceDepth = 0;
        for (var i = 0; i < include.length(); i++) {
            var c = include.charAt(i);
            if (c == '{') {
                braceDepth++;
            } else if (c == '}') {
                braceDepth = Math.max(0, braceDepth - 1);
            } else if (c == ',' && braceDepth == 0) {
                throw new IllegalArgumentException("include 必须是单个 glob，不支持逗号列表（请用 {a,b} 交替）");
            }
        }
        return FileSystems.getDefault().getPathMatcher("glob:" + include);
    }

    /**
     * 判断文件名是否匹配 include 匹配器。
     * @param matcher include 匹配器
     * @param file 待检查文件
     * @return 是否匹配
     */
    static boolean matchesInclude(PathMatcher matcher, Path file) {
        return matcher.matches(file.getFileName());
    }
}
