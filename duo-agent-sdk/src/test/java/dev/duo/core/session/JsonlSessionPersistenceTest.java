package dev.duo.core.session;

import dev.duo.model.llm.ContentBlock;
import dev.duo.model.llm.MessageFactory;
import dev.duo.model.llm.MessageSource;
import dev.duo.model.session.SessionEvent;
import dev.duo.model.session.SessionEventAssistantMessage;
import dev.duo.model.session.SessionEventStepStart;
import dev.duo.model.session.SessionEventToolCall;
import dev.duo.model.session.SessionEventTurnStart;
import dev.duo.model.session.SessionEventUserMessage;
import dev.duo.model.session.SessionHeader;
import dev.duo.model.session.SessionId;
import dev.duo.model.session.SurfaceOp;
import dev.duo.model.session.TurnEndReason;
import dev.duo.model.session.SessionEventTurnEnd;
import dev.duo.util.CallId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link JsonlSessionPersistence} 的行为测试。
 * <p>
 * 覆盖：写入-加载往返、撕裂末行丢弃、崩溃修复合成闭合、惰性物化、
 * Session append 监听集成。
 * </p>
 *
 * @author zhangyl
 * @date 2026-08-19
 */
class JsonlSessionPersistenceTest {

    @TempDir
    Path baseDir;

    private static SessionHeader headerOf(SessionId id) {
        return new SessionHeader(0, id, 1724000000000L, null, null, null, null, null, null);
    }

    /** 与 JsonlSessionPersistence 相同的文件名规则（Base64url 编码）。 */
    private Path fileOf(SessionId id) {
        var encoded = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(id.value().getBytes(StandardCharsets.UTF_8));
        return baseDir.resolve(encoded + ".jsonl");
    }

    @Test
    void appendThenLoadRoundTrips() throws IOException {
        var id = new SessionId("round-trip");
        try (var persistence = new JsonlSessionPersistence(baseDir)) {
            persistence.create(headerOf(id));
            persistence.append(id, List.of(
                    new SessionEventTurnStart(0, 1L, false, null, null, 1),
                    new SessionEventStepStart(1, 2L, false, null, null, 1, 1),
                    new SessionEventUserMessage(2, 3L, false, new SurfaceOp.Append(), null,
                            MessageFactory.createUserMessage(List.of(new ContentBlock.Text("你好")),
                                    new MessageSource.User())),
                    new SessionEventTurnEnd(3, 4L, false, null, null, 1,
                            new TurnEndReason.Completed())));
        }

        // 新实例（模拟进程重启）加载
        try (var persistence = new JsonlSessionPersistence(baseDir)) {
            var inspection = persistence.load(id);
            assertEquals(4, inspection.events().size(), "平衡日志加载后不应追加任何事件");
            assertEquals("round-trip", inspection.header().id().value());
            assertEquals(1, persistence.list().size(), "已物化会话应可列举");
        }
    }

    @Test
    void createdButNeverAppendedSessionLeavesNoTrace() throws IOException {
        try (var persistence = new JsonlSessionPersistence(baseDir)) {
            persistence.create(headerOf(new SessionId("abandoned")));
            assertEquals(0, persistence.list().size(), "惰性物化：未 append 的会话不留文件");
        }
    }

    @Test
    void recreateExistingPersistedSessionIsRejected() throws IOException {
        var id = new SessionId("dup");
        try (var persistence = new JsonlSessionPersistence(baseDir)) {
            persistence.create(headerOf(id));
            persistence.append(id, List.of(
                    new SessionEventTurnStart(0, 1L, false, null, null, 1),
                    new SessionEventTurnEnd(1, 2L, false, null, null, 1,
                            new TurnEndReason.Completed())));
        }
        // 新进程重复 create 同一会话：必须拒绝，防止向既有日志追加 seq 重置的第二段
        try (var persistence = new JsonlSessionPersistence(baseDir)) {
            assertThrows(IllegalArgumentException.class,
                    () -> persistence.create(headerOf(id)),
                    "磁盘已有日志的会话不得重复 create，恢复必须走 load");
        }
    }

    @Test
    void nonContiguousSeqAppendIsRejected() throws IOException {
        var id = new SessionId("gapped");
        var persistence = new JsonlSessionPersistence(baseDir);
        try {
            persistence.create(headerOf(id));
            persistence.append(id, List.of(
                    new SessionEventTurnStart(0, 1L, false, null, null, 1),
                    new SessionEventTurnEnd(1, 2L, false, null, null, 1,
                            new TurnEndReason.Completed())));
            // seq 从 3 开始（跳过 2）：连续性契约的写入侧校验必须拒绝
            assertThrows(IOException.class, () -> persistence.append(id, List.of(
                    new SessionEventTurnStart(3, 3L, false, null, null, 2))),
                    "seq 不连续的批次必须拒绝写入");
        } finally {
            try {
                // writer 保留了上述失败；close 会再次暴露（最终失败语义），属预期
                persistence.close();
            } catch (IOException expected) {
                // 预期中的失败重放
            }
        }
    }

    @Test
    void tornFinalLineIsDiscardedOnLoad() throws IOException {
        var id = new SessionId("torn");
        try (var persistence = new JsonlSessionPersistence(baseDir)) {
            persistence.create(headerOf(id));
            persistence.append(id, List.of(
                    new SessionEventTurnStart(0, 1L, false, null, null, 1),
                    new SessionEventTurnEnd(1, 2L, false, null, null, 1,
                            new TurnEndReason.Completed())));
        }
        // 模拟崩溃写了一半：末行追加撕裂 JSON
        var file = fileOf(new SessionId("torn"));
        Files.writeString(file, "{\"type\":\"assistant/chunk\",\"seq\":2,\"ti",
                StandardOpenOption.APPEND);

        try (var persistence = new JsonlSessionPersistence(baseDir)) {
            var inspection = persistence.load(id);
            assertEquals(2, inspection.events().size(), "撕裂的末行应被丢弃");
        }
    }

    @Test
    void corruptedMiddleLineRejects() throws IOException {
        var id = new SessionId("corrupt");
        try (var persistence = new JsonlSessionPersistence(baseDir)) {
            persistence.create(headerOf(id));
            persistence.append(id, List.of(
                    new SessionEventTurnStart(0, 1L, false, null, null, 1),
                    new SessionEventTurnEnd(1, 2L, false, null, null, 1,
                            new TurnEndReason.Completed())));
        }
        var file = fileOf(id);
        var lines = Files.readAllLines(file);
        lines.add(1, "{\"type\":\"user/message\",\"seq\":1,\"broken\":\"truncated");
        Files.writeString(file, String.join("\n", lines) + "\n");

        try (var persistence = new JsonlSessionPersistence(baseDir)) {
            assertThrows(IOException.class, () -> persistence.load(id),
                    "已提交前缀中的损坏行应拒绝而非静默丢弃");
        }
    }

    @Test
    void loadClosesCrashedOpenTurnDurably() throws IOException {
        var id = new SessionId("crashed");
        var callId = new CallId("call-crash");
        // 模拟崩溃：turn/start、step/start、用户消息、带工具调用的助手消息、tool/call，无闭合
        try (var persistence = new JsonlSessionPersistence(baseDir)) {
            persistence.create(headerOf(id));
            persistence.append(id, List.of(
                    new SessionEventTurnStart(0, 100L, false, null, null, 1),
                    new SessionEventStepStart(1, 101L, false, null, null, 1, 1),
                    new SessionEventUserMessage(2, 102L, false, new SurfaceOp.Append(), null,
                            MessageFactory.createUserMessage(List.of(new ContentBlock.Text("echo hi")),
                                    new MessageSource.User())),
                    new SessionEventAssistantMessage(3, 103L, false, new SurfaceOp.Append(), null,
                            1, 1,
                            MessageFactory.createAssistantMessage(
                                    List.of(new ContentBlock.ToolCall(callId, "echo", "{}")),
                                    "deepseek", "deepseek-chat"),
                            null),
                    new SessionEventToolCall(4, 104L, false, null, null, 1, 1,
                            callId, "echo", "{}")));
        }

        try (var persistence = new JsonlSessionPersistence(baseDir)) {
            var inspection = persistence.load(id);
            var events = inspection.events();
            assertEquals(8, events.size(), "5 条原始 + 3 条合成（tool/result、step/end、turn/end）");

            var closers = events.subList(5, 8);
            assertInstanceOf(dev.duo.model.session.SessionEventToolResult.class, closers.getFirst());
            var toolResult = (dev.duo.model.session.SessionEventToolResult) closers.getFirst();
            assertTrue(toolResult.message().source() instanceof MessageSource.Tool source
                    && source.callId().equals(callId), "合成结果应归属于悬挂的调用");
            var resultBlock = (ContentBlock.ToolResult) toolResult.message().content().getFirst();
            assertTrue(resultBlock.isError(), "合成结果应为错误结果");
            var resultText = (ContentBlock.Text) resultBlock.content().getFirst();
            assertTrue(resultText.text().contains("outcome is unknown"),
                    "已启动的调用应提示结果未知、谨慎重试");

            var turnEnd = (SessionEventTurnEnd) closers.getLast();
            assertInstanceOf(TurnEndReason.Interrupted.class, turnEnd.reason(),
                    "崩溃 turn 应以 interrupted 结束");
            assertEquals(7, turnEnd.seq(), "合成事件 seq 从末尾续");

            // 再加载一次：日志已平衡，不再追加（修复是持久的、幂等的）
            assertEquals(8, persistence.load(id).events().size());
        }
    }

    @Test
    void sessionAppendListenerFeedsPersistence() throws IOException {        var id = new SessionId("listener");
        try (var persistence = new JsonlSessionPersistence(baseDir)) {
            persistence.create(headerOf(id));
            var session = new Session(id);
            session.onAppend(event -> persistence.acceptFromListener(id, event));

            session.append(new SessionEventTurnStart(session.seq(), 1724000000000L,
                    false, null, null, 1));
            session.append(new SessionEventTurnEnd(session.seq(), 1724000000001L,
                    false, null, null, 1, new TurnEndReason.Completed()));
            // 显式 flush（不等 200ms 定时器）
            persistence.flush(id);
        }

        try (var persistence = new JsonlSessionPersistence(baseDir)) {
            var inspection = persistence.load(id);
            assertEquals(2, inspection.events().size(), "监听写入的事件应完整持久化");
            // 恢复为可继续对话的 Session（种子构造）
            var restored = new Session(id,
                    inspection.events().toArray(new dev.duo.model.session.SessionEvent[0]),
                    inspection.header());
            // 种子构造会追加 session/end-seed 边界事件（既有设计）
            assertEquals(3, restored.events().size());
            assertEquals(3, restored.seq(), "恢复后的 Session 应从正确 seq 续写");
        }
    }

    @Test
    void resumeWithInitialListenerKeepsSeqContiguous() throws IOException {
        var id = new SessionId("resume-seq");
        // 进程 A：落盘两条平衡事件
        try (var persistence = new JsonlSessionPersistence(baseDir)) {
            persistence.create(headerOf(id));
            persistence.append(id, List.of(
                    new SessionEventTurnStart(0, 1L, false, null, null, 1),
                    new SessionEventTurnEnd(1, 2L, false, null, null, 1,
                            new TurnEndReason.Completed())));
        }

        // 进程 B：load → 带初始监听器的种子构造（end-seed 必须入盘而非丢失）→ 续写
        try (var persistence = new JsonlSessionPersistence(baseDir)) {
            var inspection = persistence.load(id);
            var session = new Session(id,
                    inspection.events().toArray(new dev.duo.model.session.SessionEvent[0]),
                    inspection.header(),
                    event -> persistence.acceptFromListener(id, event));
            persistence.flush(id);

            var reloaded = persistence.load(id);
            assertEquals(3, reloaded.events().size(), "end-seed(seq=2) 应随构造入盘");
            assertEquals(dev.duo.model.session.SessionEventTypes.SESSION_END_SEED,
                    reloaded.events().getLast().type());
            assertEquals(3, session.seq(), "Session 续写 seq 与磁盘尾衔接");
        }
    }

    @Test
    void headerOnlyLogLoadsAsEmptyWithoutCrash() throws IOException {
        // 批量写部分失败的遗留形态：文件只有 header 行——load 不得抛 NoSuchElementException
        var id = new SessionId("header-only");
        var file = fileOf(id);
        Files.createDirectories(baseDir);
        Files.writeString(file, SessionEventCodec.encodeHeader(headerOf(id)) + "\n");

        try (var persistence = new JsonlSessionPersistence(baseDir)) {
            var inspection = persistence.load(id);
            assertEquals(0, inspection.events().size(), "仅 header 的日志应加载为空事件");
        }
    }
}
