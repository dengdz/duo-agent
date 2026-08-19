package dev.duo.core.session;

import dev.duo.model.llm.ContentBlock;
import dev.duo.model.llm.FinishReason;
import dev.duo.model.llm.LlmFailure;
import dev.duo.model.llm.MessageFactory;
import dev.duo.model.llm.MessageSource;
import dev.duo.model.llm.StreamChunk;
import dev.duo.model.llm.TokenUsage;
import dev.duo.model.session.SessionEventAssistantChunk;
import dev.duo.model.session.SessionEventAssistantMessage;
import dev.duo.model.session.SessionEventCompactionEnd;
import dev.duo.model.session.SessionEventCompactionStart;
import dev.duo.model.session.SessionEventRequestContext;
import dev.duo.model.session.SessionEventRequestHeader;
import dev.duo.model.session.SessionEventSessionEndSeed;
import dev.duo.model.session.SessionEventStepEnd;
import dev.duo.model.session.SessionEventStepStart;
import dev.duo.model.session.SessionEventTodoWrite;
import dev.duo.model.session.SessionEventToolCall;
import dev.duo.model.session.SessionEventToolResult;
import dev.duo.model.session.SessionEventTurnEnd;
import dev.duo.model.session.SessionEventTurnStart;
import dev.duo.model.session.SessionEventUserMessage;
import dev.duo.model.session.SessionHeader;
import dev.duo.model.session.SessionId;
import dev.duo.model.session.SurfaceOp;
import dev.duo.model.session.TodoItem;
import dev.duo.model.session.TodoStatus;
import dev.duo.model.session.TurnEndReason;
import dev.duo.util.CallId;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link SessionEventCodec} 的全类型往返测试。
 * <p>覆盖全部 13 种事件、会话头与特殊字符转义。</p>
 *
 * @author zhangyl
 * @date 2026-08-19
 */
class SessionEventCodecTest {

    @Test
    void headerRoundTripsWithOptionalFieldsOmitted() {
        var header = new SessionHeader(0, new SessionId("codec-session"), 1724000000000L,
                "/tmp/work", null, null, null, null, null);
        var decoded = SessionEventCodec.decodeHeader(SessionEventCodec.encodeHeader(header));
        assertEquals(header, decoded);

        var rich = new SessionHeader(1, new SessionId("rich"), 1724000000001L, "/w",
                new SessionId("parent"), 3, "subagent", 2, "preset");
        assertEquals(rich, SessionEventCodec.decodeHeader(SessionEventCodec.encodeHeader(rich)));
    }

    @Test
    void allEventTypesRoundTrip() {
        var cases = List.of(
                new SessionEventTurnStart(0, 1724000000000L, false, null, null, 1),
                new SessionEventTurnEnd(5, 1724000000005L, false, null, null, 1,
                        new TurnEndReason.Completed()),
                new SessionEventTurnEnd(6, 1724000000006L, false, null, null, 2,
                        new TurnEndReason.Error(new LlmFailure("boom", "HTTP_503", 503, 25L, "req-1"))),
                new SessionEventStepStart(1, 1724000000001L, false, null, null, 1, 1),
                new SessionEventStepEnd(4, 1724000000004L, false, null, null, 1, 1),
                new SessionEventAssistantChunk(2, 1724000000002L, false, null, null, 1, 1,
                        new StreamChunk.TextDelta(0, "你\"好\"\n\\world")),
                new SessionEventAssistantChunk(3, 1724000000003L, false, null, null, 1, 1,
                        new StreamChunk.Finish(new FinishReason.Aborted(
                                new LlmFailure("中止", "ABORTED")))),
                new SessionEventToolCall(7, 1724000000007L, false, null, null, 1, 2,
                        new CallId("call-1"), "bash", "{\"cmd\":\"ls\"}"),
                new SessionEventTodoWrite(8, 1724000000008L, false, null, null,
                        List.of(new TodoItem("任务一", TodoStatus.COMPLETED),
                                new TodoItem("任务二", TodoStatus.IN_PROGRESS))),
                new SessionEventSessionEndSeed(9, 1724000000009L, true, null, null),
                new SessionEventUserMessage(1, 1724000000001L, false, new SurfaceOp.Append(),
                        null, MessageFactory.createUserMessage(
                        List.of(new ContentBlock.Text("你好")), new MessageSource.User())),
                new SessionEventAssistantMessage(2, 1724000000002L, false, new SurfaceOp.Replace(1, 2),
                        new int[]{0, 1}, 1, 1,
                        MessageFactory.createAssistantMessage(
                                List.of(new ContentBlock.ToolCall(new CallId("call-2"), "echo", "{}")),
                                "deepseek", "deepseek-chat"),
                        new TokenUsage(11, 22, 33, 44, 55)),
                new SessionEventToolResult(3, 1724000000003L, false, new SurfaceOp.Append(), null,
                        1, 1, MessageFactory.createToolResultMessage(
                        new CallId("call-2"), List.of(new ContentBlock.Text("结果")), true),
                        "ToolError", "E_TOOL"),
                new SessionEventRequestContext(4, 1724000000004L, false, null, null,
                        "deepseek", "deepseek-chat", null),
                new SessionEventCompactionStart(5, 1724000000005L, false, null, null,
                        "compact-1", 1),
                new SessionEventCompactionEnd(6, 1724000000006L, false, null, null,
                        "compact-1", 1, null),
                new SessionEventCompactionEnd(7, 1724000000007L, false, null, null,
                        "compact-2", 2, "摘要调用失败"),
                new SessionEventRequestHeader(5, 1724000000005L, false, null, null,
                        new dev.duo.model.session.EpochHeader("deepseek", "deepseek-chat",
                                "系统提示", null, null, null),
                        "initial"));

        for (var event : cases) {
            var decoded = SessionEventCodec.decode(SessionEventCodec.encode(event));
            // record 对 int[] 字段是引用相等，无法直接 equals；
            // 改用二次编码比较：内容等价 ⇔ 规范化 JSON 相同
            assertEquals(SessionEventCodec.encode(event), SessionEventCodec.encode(decoded),
                    "往返不一致: " + event.type());
            assertEquals(event.seq(), decoded.seq());
            assertEquals(event.time(), decoded.time());
        }
    }

    @Test
    void escapingSurvivesControlCharactersAndQuotes() {
        // 含退格与换页控制符（quote 以 u 转义形式输出）与引号/反斜杠/换行/制表/Unicode
        var event = new SessionEventUserMessage(0, 1L, false, null, null,
                MessageFactory.createUserMessage(
                        List.of(new ContentBlock.Text("quote\" backslash\\ newline\n tab\t \b \f unicode✓")),
                        new MessageSource.User()));
        assertEquals(event, SessionEventCodec.decode(SessionEventCodec.encode(event)));
    }

    @Test
    void assistantChunkWithNestedBlockEndRoundTrips() {
        var block = new ContentBlock.ToolCall(new CallId("call-3"), "file_write", "{\"path\":\"a\"}");
        var event = new SessionEventAssistantChunk(0, 1L, false, null, null, 1, 1,
                new StreamChunk.BlockEnd(1, block));
        assertEquals(event, SessionEventCodec.decode(SessionEventCodec.encode(event)));
    }
}
