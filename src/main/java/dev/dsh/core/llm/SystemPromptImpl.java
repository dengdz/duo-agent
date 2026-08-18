package dev.dsh.core.llm;

import dev.dsh.api.llm.SystemPrompt;
import dev.dsh.model.llm.*;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * SystemPrompt 的默认实现。
 * <p>
 * 管理全局的 section、context、tool provider 和 variable 注册。
 * 简化版跳过 ScopedLayers 和 waterfall，保留核心组装逻辑。
 * </p>
 */
public class SystemPromptImpl implements SystemPrompt {

    private final Map<String, PromptSection> sections = new LinkedHashMap<>();
    private final Map<String, PromptContext> contexts = new LinkedHashMap<>();
    private final List<Function<PromptAssembly, ToolProviderResult>> toolProviders = new ArrayList<>();
    private final Map<String, Function<PromptAssembly, String>> variables = new LinkedHashMap<>();

    private final String persona;
    private final boolean includeHarnessIdentity;

    public SystemPromptImpl() {
        this("", true);
    }

    public SystemPromptImpl(String persona, boolean includeHarnessIdentity) {
        this.persona = persona;
        this.includeHarnessIdentity = includeHarnessIdentity;

        // 默认注册
        if (includeHarnessIdentity) {
            sections.put("harness:identity", new PromptSection(
                    "harness:identity", -100,
                    "You are an AI agent powered by mp-agent."
            ));
        }
        sections.put(PERSONA_SECTION, new PromptSection(
                PERSONA_SECTION, PERSONA_ORDER, persona, false
        ));
    }

    @Override
    public AutoCloseable section(PromptSection section) {
        var name = section.name();
        if (sections.containsKey(name)) {
            throw new IllegalArgumentException("prompt section \"" + name + "\" 已注册");
        }
        sections.put(name, section);
        return () -> sections.remove(name);
    }

    @Override
    public AutoCloseable context(PromptContext context) {
        var name = context.name();
        if (contexts.containsKey(name)) {
            throw new IllegalArgumentException("prompt context \"" + name + "\" 已注册");
        }
        contexts.put(name, context);
        return () -> contexts.remove(name);
    }

    @Override
    public AutoCloseable tools(Function<PromptAssembly, ToolProviderResult> provider) {
        toolProviders.add(provider);
        return () -> toolProviders.remove(provider);
    }

    @Override
    public AutoCloseable variable(String name, Function<PromptAssembly, String> provider) {
        if (!VARIABLE_NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("无效的 prompt 变量名 \"" + name + "\"");
        }
        if (variables.containsKey(name)) {
            throw new IllegalArgumentException("prompt 变量 \"" + name + "\" 已注册");
        }
        variables.put(name, provider);
        return () -> variables.remove(name);
    }

    @Override
    public PromptAssembly assemble() {
        // 1. 解析变量
        var vars = new HashMap<String, String>();
        var dummyAssembly = new PromptAssembly(List.of(), List.of(), List.of(), Map.of());
        for (var entry : variables.entrySet()) {
            var value = entry.getValue().apply(dummyAssembly);
            if (value != null) {
                vars.put(entry.getKey(), value);
            }
        }

        // 2. 排序 sections
        var sortedSections = sections.values().stream()
                .sorted(Comparator.comparingInt(PromptSection::order))
                .map(s -> new AssembledSection(s.name(), s.text()))
                .toList();

        // 3. 排序 contexts
        var sortedContexts = contexts.values().stream()
                .sorted(Comparator.comparingInt(PromptContext::order))
                .map(c -> new AssembledContext(c.name(), c.text()))
                .toList();

        // 4. 收集工具 schema
        var collectedTools = new ArrayList<ToolSchema>();
        var knownNames = new HashSet<String>();
        for (var provider : toolProviders) {
            var result = provider.apply(dummyAssembly);
            if (result != null) {
                for (var schema : result.schemas()) {
                    collectedTools.add(schema);
                    knownNames.add(schema.name());
                }
                knownNames.addAll(result.knownNames());
            }
        }

        // 5. 检查 complete section
        var completeSections = sortedSections.stream()
                .filter(s -> {
                    var sec = sections.get(s.name());
                    return sec != null && sec.complete();
                })
                .toList();
        if (completeSections.size() > 1) {
            throw new IllegalStateException("多个 complete prompt section 同时活跃");
        }

        var hasComplete = !completeSections.isEmpty();

        var assembly = new PromptAssembly(
                sortedSections,
                sortedContexts,
                collectedTools,
                Map.copyOf(vars)
        );

        // 如果有 complete section，强制设为唯一 section
        if (hasComplete) {
            assembly = new PromptAssembly(
                    completeSections,
                    assembly.contexts(),
                    assembly.tools(),
                    assembly.variables()
            );
        }

        return assembly;
    }

    /**
     * 渲染 prompt：插值变量、丢弃空 section、用空行连接。
     */
    public static String renderPrompt(PromptAssembly assembly) {
        return assembly.sections().stream()
                .map(s -> interpolate(s.text(), assembly.variables()))
                .filter(text -> !text.isEmpty())
                .collect(Collectors.joining("\n\n"));
    }

    /**
     * 插值 {{variable}} 引用。
     */
    static String interpolate(String text, Map<String, String> variables) {
        var result = new StringBuilder();
        var last = 0;
        while (true) {
            var open = text.indexOf("{{", last);
            if (open < 0) break;

            var close = text.indexOf("}}", open + 2);
            if (close < 0) {
                // 没有闭合的 }} → 视为原文
                result.append(text, last, open + 2);
                last = open + 2;
                continue;
            }

            var name = text.substring(open + 2, close).trim();
            if (name.isEmpty() || !VARIABLE_NAME.matcher(name).matches()) {
                throw new IllegalArgumentException("无效的变量引用 \"{{" + name + "}}\"");
            }

            var value = variables.get(name);
            if (value == null) {
                throw new IllegalArgumentException("未知的 prompt 变量 \"{{" + name + "}}\"");
            }

            result.append(text, last, open);
            result.append(value);
            last = close + 2;
        }
        result.append(text.substring(last));
        return result.toString();
    }

    private static final java.util.regex.Pattern VARIABLE_NAME =
            java.util.regex.Pattern.compile("^[a-z][a-z0-9_]*$");
}