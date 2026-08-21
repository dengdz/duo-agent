package dev.duo.model.llm;

/**
 * 动态模型上下文，物化为持久化的用户角色快照。
 * <p>
 * </p>
 *
 * @author zhangyl
 * @date 2026-08-18
 */
public record PromptContext(
        /** 唯一名称。 */
        String name,
        /** 升序连接。 */
        int order,
        /** 静态文本，空文本不贡献任何内容。 */
        String text
) {
}