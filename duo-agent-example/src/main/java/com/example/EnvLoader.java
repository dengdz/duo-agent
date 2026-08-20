package com.example;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;

/**
 * 零依赖的 .env 文件加载器。
 * <p>
 * 从项目根目录读取 .env 文件（如果存在），解析 KEY=VALUE 格式的配置项。
 * 优先级：真实环境变量 > .env 文件。
 * </p>
 *
 * @author zhangyl
 * @date 2026-08-20
 */
public class EnvLoader {

    private static final Map<String, String> ENV_CACHE = new HashMap<>();
    private static boolean loaded = false;

    /**
     * 获取配置项，优先从真实环境变量读取，其次从 .env 文件。
     * <p>
     * 环境变量只要存在（即使为空白）即优先返回——允许用
     * {@code export KEY=} 显式置空以屏蔽 .env 中的值，由调用方校验。
     * </p>
     *
     * @param key 配置项名称
     * @return 配置值，不存在时返回 null
     */
    public static String get(String key) {
        var envValue = System.getenv(key);
        if (envValue != null) {
            return envValue;
        }
        ensureLoaded();
        return ENV_CACHE.get(key);
    }

    private static synchronized void ensureLoaded() {
        if (loaded) {
            return;
        }
        // 从当前目录向上查找 .env 文件（支持在子目录运行）
        Path current = Paths.get("").toAbsolutePath();
        for (int i = 0; i < 3; i++) {
            var envFile = current.resolve(".env");
            if (Files.isRegularFile(envFile)) {
                loadFrom(envFile);
                loaded = true;  // 加载完成后置位；解析中途异常时下次调用可重试
                return;
            }
            var parent = current.getParent();
            if (parent == null) {
                break;
            }
            current = parent;
        }
        loaded = true;  // 未找到 .env 也置位，避免每次重复查找
    }

    private static void loadFrom(Path envFile) {
        try {
            var lines = Files.readAllLines(envFile);
            for (var line : lines) {
                var trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    continue;  // 跳过空行和注释
                }
                var idx = trimmed.indexOf('=');
                if (idx <= 0) {
                    continue;  // 无效行
                }
                var key = trimmed.substring(0, idx).trim();
                var value = trimmed.substring(idx + 1).trim();
                // 去除前后的引号（支持 KEY="value" 或 KEY='value'）；
                // length >= 2 防止单个引号（如 KEY="）时 startsWith/endsWith 同字符匹配导致 substring 越界
                if (value.length() >= 2
                        && ((value.startsWith("\"") && value.endsWith("\""))
                        || (value.startsWith("'") && value.endsWith("'")))) {
                    value = value.substring(1, value.length() - 1);
                }
                ENV_CACHE.put(key, value);
            }
        } catch (IOException e) {
            // 静默失败：.env 是可选的
        }
    }
}
