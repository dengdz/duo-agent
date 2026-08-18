package dev.duo.core.llm;

import dev.duo.api.llm.LlmAdapter;
import dev.duo.api.llm.StreamCallback;
import dev.duo.model.llm.Message;
import dev.duo.model.llm.MessageFactory;
import dev.duo.model.llm.MessageSource;
import dev.duo.model.llm.ContentBlock;
import dev.duo.model.llm.FinishReason;
import dev.duo.model.llm.GenerateOptions;
import dev.duo.model.llm.StreamChunk;
import dev.duo.model.llm.TokenUsage;
import dev.duo.model.session.SessionEventTypes;
import dev.duo.util.CallId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * 用于测试流式流水线的 mock echo 适配器。
 * <p>
 * 行为：如果最后一条用户文本以 "echo " 开头，则调用 echo 工具
 * （演练工具往返）；否则流式返回预设回复。
 * </p>
 * <p>
 * 对应原版 echo-agent 示例中的 {@code mock-echo} 适配器。
 * </p>
 *
 * @author zhangyl
 * @date 2026-08-18
 */
public class MockEchoAdapter extends LlmAdapter {

    private static final Logger logger = LoggerFactory.getLogger(MockEchoAdapter.class);
    
    /** echo 工具触发的用户输入前缀。 */
    private static final String ECHO_PREFIX = "echo ";
    /** echo 工具调用 id 与名称。 */
    private static final String ECHO_TOOL_NAME = "echo";
    private static final String ECHO_CALL_ID = "call-echo";

    @Override
    public void stream(GenerateOptions options, StreamCallback callback) {
        try {
            // 查找最后一条用户文本
            var lastUserText = "";
            for (int i = options.messages().size() - 1; i >= 0; i--) {
                var msg = options.messages().get(i);
                if (msg instanceof Message.UserMessage userMsg) {
                    for (var block : userMsg.content()) {
                        if (block instanceof ContentBlock.Text text) {
                            lastUserText = text.text();
                            break;
                        }
                    }
                    break;
                }
            }

            // 检查最后一条消息是否有工具结果
            var hasToolResult = false;
            if (!options.messages().isEmpty()) {
                var lastMsg = options.messages().getLast();
                for (var block : lastMsg.content()) {
                    if (block instanceof ContentBlock.ToolResult) {
                        hasToolResult = true;
                        break;
                    }
                }
            }

            if (lastUserText.startsWith(ECHO_PREFIX) && !hasToolResult) {
                // 模拟工具调用
                var payload = lastUserText.substring(ECHO_PREFIX.length());
                var args = "{\"text\": \"" + payload + "\"}";

                callback.onChunk(new StreamChunk.BlockStart(0, SessionEventTypes.BLOCK_TEXT));
                callback.onChunk(new StreamChunk.TextDelta(0, "Let me echo that for you."));
                callback.onChunk(new StreamChunk.BlockEnd(0, new ContentBlock.Text("Let me echo that for you.")));

                callback.onChunk(new StreamChunk.BlockStart(1, SessionEventTypes.BLOCK_TOOL_CALL));
                callback.onChunk(new StreamChunk.ToolCallDelta(
                        1, new CallId(ECHO_CALL_ID), ECHO_TOOL_NAME, args));
                callback.onChunk(new StreamChunk.BlockEnd(1, new ContentBlock.ToolCall(
                        new CallId(ECHO_CALL_ID), ECHO_TOOL_NAME, args
                )));

                callback.onChunk(new StreamChunk.Usage(new TokenUsage(20, 10)));
                callback.onChunk(new StreamChunk.Finish(new FinishReason.ToolCalls()));
            } else {
                var reply = hasToolResult
                        ? "The echo tool has spoken."
                        : "You said: \"" + lastUserText + "\". Try \"echo <something>\" to see a tool call.";

                callback.onChunk(new StreamChunk.BlockStart(0, SessionEventTypes.BLOCK_TEXT));
                callback.onChunk(new StreamChunk.TextDelta(0, reply));
                callback.onChunk(new StreamChunk.BlockEnd(0, new ContentBlock.Text(reply)));

                callback.onChunk(new StreamChunk.Usage(new TokenUsage(20, reply.length())));
                callback.onChunk(new StreamChunk.Finish(new FinishReason.Stop()));
            }

            callback.onComplete();
        } catch (IllegalArgumentException | IllegalStateException e) {
            logger.debug("Mock echo adapter validation error: {}", e.getMessage());
            callback.onError(e);
        } catch (Exception e) {
            logger.error("Unexpected error in mock echo adapter", e);
            callback.onError(new RuntimeException("Mock adapter 执行失败", e));
        }
    }
}