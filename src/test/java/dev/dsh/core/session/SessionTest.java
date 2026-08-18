package dev.dsh.core.session;

import dev.dsh.model.llm.Message;
import dev.dsh.model.llm.MessageFactory;
import dev.dsh.model.llm.MessageSource;
import dev.dsh.model.llm.ContentBlock;
import dev.dsh.model.llm.StreamChunk;
import dev.dsh.model.llm.TokenUsage;
import dev.dsh.model.session.*;
import dev.dsh.util.CallId;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link Session} 的测试。
 * <p>
 * 覆盖：事件追加、不可变快照、seq 连续、表面事件、deriveMessages、
 * requestHeader 折叠、表面替换。
 * </p>
 */
class SessionTest {

    @Test
    void shouldAppendEventIncreaseSeq() {
        var session = new Session(new SessionId("test"));

        assertEquals(0, session.seq());

        session.append(new SessionEventTurnStart(0, 1));
        assertEquals(1, session.seq());

        session.append(new SessionEventTurnEnd(1, 1, new TurnEndReason.Completed()));
        assertEquals(2, session.seq());
    }

    @Test
    void shouldProvideImmutableEventSnapshot() {
        var session = new Session(new SessionId("test"));
        session.append(new SessionEventTurnStart(0, 1));

        var events = session.events();
        assertEquals(1, events.size());

        // 追加新事件后，旧快照不变
        session.append(new SessionEventTurnEnd(1, 1, new TurnEndReason.Completed()));
        assertEquals(1, events.size());  // 旧快照
        assertEquals(2, session.events().size());  // 新快照
    }

    @Test
    void shouldMaintainContiguousSeq() {
        var session = new Session(new SessionId("test"));

        for (int i = 0; i < 5; i++) {
            var event = session.append(new SessionEventStepStart(i, 1, 1));
            assertEquals(i, event.seq());
        }
        assertEquals(5, session.seq());
    }

    @Test
    void shouldDistinguishSurfaceAndNonSurfaceEvents() {
        var session = new Session(new SessionId("test"));

        // 非表面事件
        session.append(new SessionEventTurnStart(0, 1));
        session.append(new SessionEventStepStart(1, 1, 1));

        // 表面事件（带 surfaceOp）
        session.append(new SessionEventUserMessage(
                2,
                MessageFactory.createUserMessage(
                        List.of(new ContentBlock.Text("hello")),
                        new MessageSource.User()
                ),
                new SurfaceOp.Append()
        ));

        var surface = session.surface();
        assertEquals(1, surface.nodes().size());
        assertEquals(2, surface.nodes().getFirst());
    }

    @Test
    void shouldDeriveMessageHistory() {
        var session = new Session(new SessionId("test"));

        // turn 1
        session.append(new SessionEventTurnStart(0, 1));

        // 用户消息
        var userMsg = MessageFactory.createUserMessage(
                List.of(new ContentBlock.Text("hello")),
                new MessageSource.User()
        );
        session.append(new SessionEventUserMessage(1, userMsg, new SurfaceOp.Append()));

        // 助手消息
        var assistantMsg = MessageFactory.createAssistantMessage(
                List.of(new ContentBlock.Text("Hi there!")),
                "deepseek", "v4"
        );
        session.append(new SessionEventAssistantMessage(
                2, 1, 1, assistantMsg, new SurfaceOp.Append(), new TokenUsage(10, 5)
        ));

        session.append(new SessionEventTurnEnd(3, 1, new TurnEndReason.Completed()));

        // 派生消息
        var messages = session.deriveMessages();
        assertEquals(2, messages.size());
        assertEquals("user", messages.get(0).role());
        assertEquals("assistant", messages.get(1).role());
    }

    @Test
    void shouldSkipEmptyAssistantMessage() {
        var session = new Session(new SessionId("test"));

        var userMsg = MessageFactory.createUserMessage(
                List.of(new ContentBlock.Text("hello")),
                new MessageSource.User()
        );
        session.append(new SessionEventUserMessage(0, userMsg, new SurfaceOp.Append()));

        // 空内容的助手消息（仅用于承载 usage）
        var emptyAssistant = MessageFactory.createAssistantMessage(
                List.of(),
                "deepseek", "v4"
        );
        session.append(new SessionEventAssistantMessage(
                1, 1, 1, emptyAssistant, new SurfaceOp.Append(), new TokenUsage(10, 5)
        ));

        var messages = session.deriveMessages();
        assertEquals(1, messages.size());  // 空内容被跳过
    }

    @Test
    void shouldFoldRequestHeader() {
        var session = new Session(new SessionId("test"));

        assertNull(session.requestHeader());

        var header = new EpochHeader("deepseek", "v4", null, null, null, null);
        session.append(new SessionEventRequestHeader(0, header, "initial"));

        var folded = session.requestHeader();
        assertNotNull(folded);
        assertEquals("deepseek", folded.provider());
        assertEquals("v4", folded.model());
    }

    @Test
    void shouldReplaceSurfaceNodes() {
        var session = new Session(new SessionId("test"));

        // 追加两条用户消息
        var msg1 = MessageFactory.createUserMessage(
                List.of(new ContentBlock.Text("first")),
                new MessageSource.User()
        );
        var e1 = session.append(new SessionEventUserMessage(0, msg1, new SurfaceOp.Append()));

        var msg2 = MessageFactory.createUserMessage(
                List.of(new ContentBlock.Text("second")),
                new MessageSource.User()
        );
        var e2 = session.append(new SessionEventUserMessage(1, msg2, new SurfaceOp.Append()));

        assertEquals(2, session.surface().nodes().size());

        // 替换第一条消息
        var msg3 = MessageFactory.createUserMessage(
                List.of(new ContentBlock.Text("replaced")),
                new MessageSource.User()
        );
        session.append(new SessionEventUserMessage(
                2, msg3, new SurfaceOp.Replace(0, 0)
        ));

        // 替换后应该只有 2 个节点（原来 2 个，替换掉 1 个，插入 1 个）
        assertEquals(2, session.surface().nodes().size());
        // 替换代次增加
        assertEquals(1, session.surface().replaceGeneration());
    }

    @Test
    void shouldForkChildSession() {
        var store = new SessionStore();
        var parent = store.create(new SessionId("parent"));

        // 追加一些事件
        parent.append(new SessionEventTurnStart(0, 1));
        var userMsg = MessageFactory.createUserMessage(
                List.of(new ContentBlock.Text("hello")),
                new MessageSource.User()
        );
        parent.append(new SessionEventUserMessage(1, userMsg, new SurfaceOp.Append()));
        parent.append(new SessionEventTurnEnd(2, 1, new TurnEndReason.Completed()));

        // fork
        var child = store.fork(parent, null, new SessionId("child"));

        assertNotNull(child);
        assertEquals("child", child.id().value());
        assertEquals(parent.id(), child.header().parentSession());
        assertEquals(4, child.seq());  // 3 个事件 + session/end-seed
    }

    @Test
    void shouldRejectSurfaceOpOnNonSurfaceEvent() {
        var session = new Session(new SessionId("test"));
        // 构造一个带 surfaceOp 的 turn/start 事件
        var badEvent = new SessionEventTurnStart(
                0, System.currentTimeMillis(), false,
                new SurfaceOp.Append(), null, 1
        );
        assertThrows(IllegalArgumentException.class, () -> {
            session.append(badEvent);
        });
    }
}