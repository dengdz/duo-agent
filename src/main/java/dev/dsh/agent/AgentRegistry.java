package dev.dsh.agent;

import dev.dsh.agent.inbox.Inbox;
import dev.dsh.exception.AgentCreationException;
import dev.dsh.session.Session;
import dev.dsh.session.SessionStore;
import dev.dsh.session.types.SessionEvent;
import dev.dsh.session.types.SessionId;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Agent 服务：跟踪活跃 agent 并委托创建给工厂。
 * <p>
 * 对应 TS 源码中的 {@code AgentRegistry} 类。
 * </p>
 */
public class AgentRegistry {

    private final Map<SessionId, AgentEntry> store = new ConcurrentHashMap<>();
    private final SessionStore sessionStore;
    private AgentFactory factory;

    public AgentRegistry(SessionStore sessionStore) {
        this.sessionStore = sessionStore;
    }

    /**
     * 注册 Agent 创建工厂（loop 在构造时调用）。
     */
    public void setFactory(AgentFactory factory) {
        if (this.factory != null) {
            throw new IllegalStateException("Agent 工厂已注册");
        }
        this.factory = factory;
    }

    /**
     * 通过注册的工厂创建并发布新 Agent。
     */
    public AgentHandle create(CreateAgentOptions options) throws AgentCreationException {
        if (factory == null) {
            throw new IllegalStateException("没有注册 Agent 工厂（需要加载 agent-loop 插件）");
        }
        try {
            return factory.createAgent(options);
        } catch (AgentCreationException e) {
            throw e;
        } catch (Exception e) {
            throw new AgentCreationException("创建 Agent 失败", e);
        }
    }

    /**
     * 通过注册的工厂加载持久化会话并恢复 Agent。
     */
    public AgentHandle resume(ResumeAgentOptions options) throws AgentCreationException {
        if (factory == null) {
            throw new IllegalStateException("没有注册 Agent 工厂（需要加载 agent-loop 插件）");
        }
        try {
            return factory.resume(options);
        } catch (AgentCreationException e) {
            throw e;
        } catch (Exception e) {
            throw new AgentCreationException("恢复 Agent 失败", e);
        }
    }

    /**
     * 注册一个已构造的 Agent。
     * @param agent 要注册的 Agent
     * @return 处置器
     */
    public AutoCloseable register(Agent agent) {
        var id = agent.id();
        if (store.containsKey(id)) {
            throw new IllegalArgumentException("Agent \"" + id + "\" 已注册");
        }
        store.put(id, new AgentEntry(agent));
        return () -> {
            store.remove(id);
        };
    }

    /**
     * 查找活跃 Agent。
     */
    public Agent get(SessionId id) {
        var entry = store.get(id);
        return entry != null ? entry.agent : null;
    }

    /**
     * 所有活跃 Agent。
     */
    public List<Agent> list() {
        return List.copyOf(store.values().stream().map(e -> e.agent).toList());
    }

    /** 内部 Agent 条目。 */
    private record AgentEntry(Agent agent) {}
}