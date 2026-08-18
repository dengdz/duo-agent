package dev.dsh.session;

import dev.dsh.session.types.*;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内存会话存储（{@code ctx.sessions}）。
 * <p>
 * 持久化不在此实现——持久化插件订阅 {@code session/event} 并在 flush/dispose 时写入。
 * </p>
 * <p>
 * 对应 TS 源码中的 {@code SessionStore}。
 * </p>
 */
public class SessionStore {

    private final Map<SessionId, Session> store = new ConcurrentHashMap<>();
    private int counter = 0;

    /**
     * 创建并注册一个会话。
     * <p>
     * 简化的 create 方法：prepare + enter 一步完成。
     * </p>
     */
    public Session create(SessionId id, CreateSessionOptions options) {
        if (id == null) {
            do {
                id = new SessionId("session-" + (++counter));
            } while (store.containsKey(id));
        }
        if (store.containsKey(id)) {
            throw new IllegalArgumentException("会话 \"" + id + "\" 已存在");
        }

        var meta = options != null ? options.meta() : null;
        var header = new SessionHeader(
                0, id,
                meta != null && meta.createdAt() != null ? meta.createdAt() : System.currentTimeMillis(),
                meta != null ? meta.cwd() : null,
                meta != null ? meta.parentSession() : null,
                meta != null ? meta.seedLength() : null,
                meta != null ? meta.origin() : null,
                meta != null ? meta.delegationDepth() : null,
                meta != null ? meta.agentPreset() : null
        );

        var seed = options != null && options.seed() != null ? options.seed() : new SessionEvent[0];
        var session = new Session(id, seed, header);
        store.put(id, session);
        return session;
    }

    /** 创建会话，使用默认选项。 */
    public Session create() {
        return create(null, null);
    }

    /** 创建会话，指定 id。 */
    public Session create(SessionId id) {
        return create(id, null);
    }

    /**
     * 移除并销毁一个会话。
     */
    public void dispose(SessionId id) {
        store.remove(id);
    }

    /**
     * 查找一个活跃会话。
     */
    public Session get(SessionId id) {
        return store.get(id);
    }

    /**
     * 所有活跃会话，按创建顺序。
     */
    public List<Session> list() {
        return List.copyOf(store.values());
    }

    /**
     * 从父会话 fork 一个子会话。
     * <p>
     * 对应 TS 源码中的 {@code SessionStore.fork}。
     * </p>
     */
    public Session fork(Session source, Integer boundary, SessionId childId) {
        if (childId != null && store.containsKey(childId)) {
            throw new IllegalArgumentException("子会话 \"" + childId + "\" 已存在");
        }

        var events = source.events();
        var endSeq = boundary != null ? boundary : events.size() - 1;
        if (endSeq < 0) endSeq = 0;

        // 验证边界不在打开的 turn 内
        // （简化版：仅简单检查事件序列）
        if (endSeq > 0) {
            var hasOpenTurn = false;
            for (int i = 0; i <= endSeq; i++) {
                var event = events.get(i);
                if (event instanceof SessionEventTurnStart) hasOpenTurn = true;
                if (event instanceof SessionEventTurnEnd) hasOpenTurn = false;
            }
            if (hasOpenTurn) {
                throw new IllegalArgumentException("fork 边界不能在打开的 turn 内");
            }
        }

        var seed = events.subList(0, endSeq + 1).toArray(new SessionEvent[0]);

        var options = new CreateSessionOptions(
                seed,
                new CreateSessionOptions.SessionMeta(
                        source.header().cwd(),
                        source.id(),
                        null,
                        seed.length,
                        null, null, null
                )
        );

        return create(childId, options);
    }

    /** 从父会话 fork 一个子会话（不指定边界 = 当前最后事件）。 */
    public Session fork(Session source) {
        return fork(source, null, null);
    }
}