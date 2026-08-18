package dev.duo.api.agent;

import dev.duo.api.agent.Inbox;
import dev.duo.model.llm.Message;
import dev.duo.model.llm.MessageFactory;
import dev.duo.model.llm.MessageSource;
import dev.duo.model.llm.ContentBlock;
import dev.duo.model.session.SessionId;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Agent 模块测试。
 * <p>
 * 覆盖：Inbox 操作、AgentRegistry 注册和查询、Agent 接口实现。
 * </p>
 *
 * @author zhangyl
 * @date 2026-08-18
 */
class AgentTest {

    @Test
    void inboxShouldBeEmptyInitially() {
        var inbox = new Inbox();
        assertFalse(inbox.hasPending());
        assertTrue(inbox.nextTurn().isEmpty());
        assertTrue(inbox.nextStep().isEmpty());
    }

    @Test
    void inboxShouldAppendToNextStep() {
        var inbox = new Inbox();
        var msg = MessageFactory.createUserMessage(
                List.of(new ContentBlock.Text("hello")),
                new MessageSource.User()
        );

        inbox.append(InboxTarget.NEXT_STEP, msg);
        assertTrue(inbox.hasPending());
        assertEquals(1, inbox.nextStep().size());
    }

    @Test
    void inboxShouldAppendToNextTurn() {
        var inbox = new Inbox();
        var msg = MessageFactory.createUserMessage(
                List.of(new ContentBlock.Text("hello")),
                new MessageSource.User()
        );

        inbox.append(InboxTarget.NEXT_TURN, msg);
        assertTrue(inbox.hasPending());
        assertEquals(1, inbox.nextTurn().size());
    }

    @Test
    void inboxClaimShouldDrainAllNextStepAndOneNextTurn() {
        var inbox = new Inbox();

        var stepMsg1 = MessageFactory.createUserMessage(
                List.of(new ContentBlock.Text("step1")), new MessageSource.User()
        );
        var stepMsg2 = MessageFactory.createUserMessage(
                List.of(new ContentBlock.Text("step2")), new MessageSource.User()
        );
        var turnMsg = MessageFactory.createUserMessage(
                List.of(new ContentBlock.Text("turn")), new MessageSource.User()
        );

        inbox.append(InboxTarget.NEXT_STEP, stepMsg1);
        inbox.append(InboxTarget.NEXT_STEP, stepMsg2);
        inbox.append(InboxTarget.NEXT_TURN, turnMsg);

        var claimed = inbox.claim(InboxTarget.NEXT_TURN);
        assertEquals(3, claimed.size());
        assertFalse(inbox.hasPending());
    }

    @Test
    void inboxClaimWithNextStepShouldNotTouchNextTurn() {
        var inbox = new Inbox();
        var turnMsg = MessageFactory.createUserMessage(
                List.of(new ContentBlock.Text("turn")), new MessageSource.User()
        );
        inbox.append(InboxTarget.NEXT_TURN, turnMsg);

        var claimed = inbox.claim(InboxTarget.NEXT_STEP);
        assertTrue(claimed.isEmpty());
        assertTrue(inbox.hasPending());
    }

    @Test
    void inboxClearShouldRemoveAllMessages() {
        var inbox = new Inbox();
        inbox.append(InboxTarget.NEXT_STEP, MessageFactory.createUserMessage(
                List.of(new ContentBlock.Text("s")), new MessageSource.User()
        ));
        inbox.append(InboxTarget.NEXT_TURN, MessageFactory.createUserMessage(
                List.of(new ContentBlock.Text("t")), new MessageSource.User()
        ));

        inbox.clear();
        assertFalse(inbox.hasPending());
    }

    @Test
    void inboxPrependShouldInsertAtHead() {
        var inbox = new Inbox();
        var first = MessageFactory.createUserMessage(
                List.of(new ContentBlock.Text("first")), new MessageSource.User()
        );
        var second = MessageFactory.createUserMessage(
                List.of(new ContentBlock.Text("second")), new MessageSource.User()
        );

        inbox.append(InboxTarget.NEXT_STEP, second);
        inbox.prepend(InboxTarget.NEXT_STEP, first);

        var claimed = inbox.claim(InboxTarget.NEXT_STEP);
        assertEquals(2, claimed.size());
        assertEquals("first", ((ContentBlock.Text) claimed.get(0).content().getFirst()).text());
    }

    @Test
    void registryShouldRegisterAndLookupAgent() {
        var registry = new AgentRegistry(null);
        var agent = new MockAgent(new SessionId("test-agent"));

        registry.register(agent);
        assertNotNull(registry.get(new SessionId("test-agent")));
        assertEquals(1, registry.list().size());
    }

    @Test
    void registryShouldThrowOnDuplicateRegister() {
        var registry = new AgentRegistry(null);
        var agent = new MockAgent(new SessionId("dup"));

        registry.register(agent);
        assertThrows(IllegalArgumentException.class, () -> registry.register(agent));
    }

    @Test
    void registryShouldThrowWithoutFactory() {
        var registry = new AgentRegistry(null);
        assertThrows(IllegalStateException.class, () -> {
            registry.create(new CreateAgentOptions(
                    new SessionId("test"), null, null, null
            ));
        });
    }

    @Test
    void registryShouldCreateWithFactory() throws Exception {
        var registry = new AgentRegistry(null);
        var created = new AtomicBoolean(false);

        registry.setFactory(new AgentFactory() {
            @Override
            public AgentHandle createAgent(CreateAgentOptions options) {
                created.set(true);
                var agent = new MockAgent(options.sessionId());
                registry.register(agent);
                return new AgentHandle(agent, () -> {});
            }

            @Override
            public AgentHandle resume(ResumeAgentOptions options) {
                throw new UnsupportedOperationException();
            }
        });

        var handle = registry.create(new CreateAgentOptions(
                new SessionId("test"), null, null, null
        ));
        assertTrue(created.get());
        assertNotNull(handle.agent());
        assertNotNull(registry.get(new SessionId("test")));
    }

    // ---- MockAgent 实现 ----

    static class MockAgent implements Agent {
        private final SessionId id;
        private final Inbox inbox = new Inbox();

        MockAgent(SessionId id) {
            this.id = id;
        }

        @Override
        public SessionId id() { return id; }

        @Override
        public AgentOptions options() { return new AgentOptions(); }

        @Override
        public dev.duo.core.session.Session session() { return null; }

        @Override
        public Inbox inbox() { return inbox; }

        @Override
        public AgentStatus status() { return AgentStatus.IDLE; }

        @Override
        public void cancel(AgentCancelCause cause, CancelOptions options) {}

        @Override
        public void whenIdle() {}

        @Override
        public void send(Message message, InboxTarget target, boolean wakeup) {
            inbox.append(target, message);
        }

        @Override
        public void followup(Message message) {
            inbox.append(InboxTarget.NEXT_TURN, message);
        }

        @Override
        public void steer(Message message) {
            inbox.append(InboxTarget.NEXT_STEP, message);
        }

        @Override
        public void inject(Message message) {
            inbox.append(InboxTarget.NEXT_STEP, message);
        }
    }
}