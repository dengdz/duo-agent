package dev.duo.api.llm;

import dev.duo.model.llm.PromptAssembly;
import dev.duo.model.llm.PromptContext;
import dev.duo.model.llm.PromptSection;
import dev.duo.model.llm.ToolProviderResult;

import java.util.function.Function;

/**
 * 系统提示词服务：管理有序 sections、动态上下文、工具 schema 和 prompt 变量。
 *
 * @author zhangyl
 * @date 2026-08-18
 */
public interface SystemPrompt {

    /** 部署人设的 section 名称和 order。 */
    String PERSONA_SECTION = "deployment:persona";
    int PERSONA_ORDER = 0;
    String TOOL_ORDER_REST = "<unlisted-tools>";

    /**
     * 注册一个有序的 prompt section。
     * @param section 要注册的 section
     * @return 处置器
     */
    AutoCloseable section(PromptSection section);

    /**
     * 注册有序的动态上下文。
     * @param context 要注册的上下文贡献
     * @return 处置器
     */
    AutoCloseable context(PromptContext context);

    /**
     * 注册工具 schema 提供者。
     * @param provider 为每次组装求值的提供者
     * @return 处置器
     */
    AutoCloseable tools(Function<PromptAssembly, ToolProviderResult> provider);

    /**
     * 注册 prompt 变量。
     * @param name 变量名（[a-z][a-z0-9_]*）
     * @param provider 为每次组装求值的提供者，可返回 null
     * @return 处置器
     */
    AutoCloseable variable(String name, Function<PromptAssembly, String> provider);

    /**
     * 组装全局和当前作用域的提供者，返回完整的 PromptAssembly。
     * @return 组装后的结果
     */
    PromptAssembly assemble();
}