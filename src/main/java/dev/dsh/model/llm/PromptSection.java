package dev.dsh.model.llm;

/**
 * 系统提示词的一个贡献片段。
 * <p>
 * 对应 TS 源码中的 {@code PromptSection}。
 * </p>
 *
 * @author zhangyl
 * @date 2026-08-18
 */
public record PromptSection(
        /** 唯一名称。 */
        String name,
        /** 升序连接。约定：-100 为 harness 身份，0 为部署人设，工具指引使用 100-199。 */
        int order,
        /** 静态文本，文本中可引用 {{variable}}。 */
        String text,
        /** 将此贡献视为完整的系统提示词。 */
        boolean complete
) {
    public PromptSection(String name, int order, String text) {
        this(name, order, text, false);
    }
}