package dev.dsh.model.llm;

import java.util.List;

/**
 * 一个组装中可见的工具 schema 及其预限制名称集。
 * <p>
 * 对应 TS 源码中的 {@code ToolProviderResult}。
 * </p>
 *
 * @author zhangyl
 * @date 2026-08-18
 */
public record ToolProviderResult(
        /** 此提供者为本次组装贡献的 schema。 */
        List<ToolSchema> schemas,
        /** 用于配置验证的预限制名称全集（默认使用 schemas 的名称）。 */
        List<String> knownNames
) {
    public ToolProviderResult(List<ToolSchema> schemas) {
        this(schemas, schemas.stream().map(ToolSchema::name).toList());
    }
}