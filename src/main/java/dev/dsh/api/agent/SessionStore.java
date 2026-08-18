package dev.dsh.api.agent;

import dev.dsh.core.session.Session;
import dev.dsh.model.session.CreateSessionOptions;
import dev.dsh.model.session.SessionId;

import java.util.List;

/**
 * 会话存储契约（{@code ctx.sessions}）。
 * <p>
 * 对应 TS 源码中的 {@code SessionStore}。
 * 内存实现见 {@code dev.dsh.core.session.InMemorySessionStore}；
 * 持久化实现将订阅 {@code session/event} 并在 flush/dispose 时写入。
 * </p>
 * <p>
 * 注意：方法签名使用 {@code dev.dsh.core.session.Session}——会话是库的领域类型，
 * 契约层对其引用属于明示豁免，待持久化改造时一并接口化。
 * </p>
 *
 * @author zhangyl
 * @date 2026-08-18
 */
public interface SessionStore {

    /**
     * 创建并注册一个会话。
     * <p>
     * 简化的 create 方法：prepare + enter 一步完成。
     * </p>
     */
    Session create(SessionId id, CreateSessionOptions options);

    /** 创建会话，使用默认选项。 */
    Session create();

    /** 创建会话，指定 id。 */
    Session create(SessionId id);

    /** 移除并销毁一个会话。 */
    void dispose(SessionId id);

    /** 查找一个活跃会话。 */
    Session get(SessionId id);

    /** 所有活跃会话，按创建顺序。 */
    List<Session> list();

    /** 从父会话 fork 一个子会话。 */
    Session fork(Session source, Integer boundary, SessionId childId);

    /** 从父会话 fork 一个子会话（不指定边界 = 当前最后事件）。 */
    Session fork(Session source);
}