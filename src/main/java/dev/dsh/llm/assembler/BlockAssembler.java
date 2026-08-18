package dev.dsh.llm.assembler;

import dev.dsh.llm.types.ContentBlock;
import dev.dsh.llm.types.FinishReason;
import dev.dsh.llm.types.StreamChunk;
import dev.dsh.llm.types.TokenUsage;
import dev.dsh.util.CallId;

import java.util.*;

/**
 * 将原始 {@link StreamChunk} 增量组装为完整的 {@link ContentBlock} 列表
 * 和最终的助手 {@link dev.dsh.llm.message.Message}。
 * <p>
 * Agent loop 在将原始 chunk 记录到日志（用于重放保真度）的同时喂给此组装器，
 * 然后在流结束后读取 {@code blocks()}、{@code usage()}、{@code finish()}。
 * </p>
 * <p>
 * 容忍仅 delta 的协议（无 block-start/end）；
 * 已由 {@code block-end} 关闭的索引再次收到 delta 时将被忽略（畸形流），
 * 以防止行为异常的适配器增长内存或损坏已完成的块。
 * </p>
 * <p>
 * 对应 TS 源码中的 {@code BlockAssembler}。
 * </p>
 */
public class BlockAssembler {

    private static final class PartialBlock {
        String blockType;
        String text = "";
        String toolCallId;
        String toolCallName;
        String toolCallArguments = "";
        /** 由 {@code block-end} 设置——权威数据，冻结此 partial。 */
        ContentBlock block;
    }

    private final Map<Integer, PartialBlock> partials = new LinkedHashMap<>();
    private final List<Integer> order = new ArrayList<>();
    private TokenUsage usage;
    private FinishReason finish;
    private Object replayState;

    /**
     * 向组装状态喂入一个 chunk。
     * @param chunk 按流顺序排列的下一个原始 chunk
     */
    public void push(StreamChunk chunk) {
        switch (chunk) {
            case StreamChunk.BlockStart bs -> {
                if (!partials.containsKey(bs.index())) {
                    order.add(bs.index());
                    var partial = new PartialBlock();
                    partial.blockType = bs.blockType();
                    partials.put(bs.index(), partial);
                }
            }
            case StreamChunk.TextDelta td -> {
                var partial = ensure(td.index(), "text");
                if (partial.block != null) return; // 被 block-end 关闭；忽略掉队者
                partial.text += td.text();
            }
            case StreamChunk.ReasoningDelta rd -> {
                var partial = ensure(rd.index(), "reasoning");
                if (partial.block != null) return;
                partial.text += rd.text();
            }
            case StreamChunk.ToolCallDelta tcd -> {
                var partial = ensure(tcd.index(), "tool-call");
                if (partial.block != null) return;
                if (partial.toolCallId == null) partial.toolCallId = tcd.id().value();
                if (tcd.name() != null && !tcd.name().isBlank()) partial.toolCallName = tcd.name();
                partial.toolCallArguments += tcd.argumentsDelta();
            }
            case StreamChunk.BlockEnd be -> {
                var partial = ensure(be.index(), blockTypeName(be.block()));
                if (partial.block != null) return; // 首次关闭优先
                partial.block = be.block();
            }
            case StreamChunk.Usage u -> this.usage = u.usage();
            case StreamChunk.Finish f -> {
                this.finish = f.reason();
                this.replayState = f.replayState();
            }
        }
    }

    private PartialBlock ensure(int index, String blockType) {
        var partial = partials.get(index);
        if (partial == null) {
            partial = new PartialBlock();
            partial.blockType = blockType;
            partials.put(index, partial);
            order.add(index);
        }
        return partial;
    }

    /** 将 ContentBlock 映射为其类型名称字符串。 */
    private static String blockTypeName(ContentBlock block) {
        return switch (block) {
            case ContentBlock.Text ignored -> "text";
            case ContentBlock.Reasoning ignored -> "reasoning";
            case ContentBlock.ToolCall ignored -> "tool-call";
            case ContentBlock.ToolResult ignored -> "tool-result";
        };
    }

    private ContentBlock assemble(PartialBlock partial, int index) {
        if (partial.block != null) return partial.block;
        return switch (partial.blockType) {
            case "text" -> new ContentBlock.Text(partial.text);
            case "reasoning" -> new ContentBlock.Reasoning(partial.text);
            case "tool-call" -> new ContentBlock.ToolCall(
                    partial.toolCallId != null
                            ? new CallId(partial.toolCallId)
                            : new CallId("call-" + index),
                    partial.toolCallName != null ? partial.toolCallName : "",
                    partial.toolCallArguments
            );
            default -> throw new IllegalStateException(
                    "无法组装类型为 \"" + partial.blockType + "\" 的不完整块"
            );
        };
    }

    /**
     * 按流顺序组装所有已见块。
     * @return 每个已见索引对应一个块，但 max-token 截断将丢弃无法安全执行的工具调用
     */
    public List<ContentBlock> blocks() {
        var blocks = order.stream()
                .map(index -> assemble(
                        Objects.requireNonNull(partials.get(index),
                                "BlockAssembler 不变量违规：索引 " + index + " 无对应 partial"),
                        index))
                .toList();

        return finish instanceof FinishReason.MaxTokens
                ? blocks.stream().filter(b -> !(b instanceof ContentBlock.ToolCall)).toList()
                : blocks;
    }

    /** 来自 usage chunk 的用量；到达前为 {@code null}。 */
    public Optional<TokenUsage> usage() {
        return Optional.ofNullable(usage);
    }

    /** 来自 finish chunk 的结束原因；流结束时未收到则默认为 {@code stop}。 */
    public FinishReason finish() {
        return finish != null ? finish : new FinishReason.Stop();
    }

    /** 来自终结 finish chunk 的适配器私有重放状态，如果有的话。 */
    public Optional<Object> replayState() {
        return Optional.ofNullable(replayState);
    }
}