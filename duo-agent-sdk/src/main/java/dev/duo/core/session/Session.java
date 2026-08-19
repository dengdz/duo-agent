package dev.duo.core.session;

import dev.duo.model.llm.Message;
import dev.duo.model.session.EpochHeader;
import dev.duo.model.session.SessionEvent;
import dev.duo.model.session.SessionEventAssistantMessage;
import dev.duo.model.session.SessionEventRequestHeader;
import dev.duo.model.session.SessionEventSessionEndSeed;
import dev.duo.model.session.SessionEventToolResult;
import dev.duo.model.session.SessionEventTypes;
import dev.duo.model.session.SessionEventUserMessage;
import dev.duo.model.session.SessionHeader;
import dev.duo.model.session.SessionId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * 事件溯源的会话：一个 {@link SessionEvent} 的追加式日志。
 * <p>
 * 消息历史由日志派生，日志是唯一的事实源。
 * 模型可见 = 已记日志。
 * </p>
 * <p>
 * 对应 TS 源码中的 {@code Session} 类。
 * </p>
 *
 * @author zhangyl
 * @date 2026-08-18
 */
public class Session {

    private static final Logger logger = LoggerFactory.getLogger(Session.class);

    /** 追加式事件日志。 */
    private final List<SessionEvent> log = new ArrayList<>();

    /** 表面管理器。 */
    private final SurfaceManager surfaceManager = new SurfaceManager();

    /** 不可变的存储元数据。 */
    private final SessionHeader header;

    /** 本进程追加的第一个事件序号（用于区分种子事件和本进程事件）。 */
    private final int firstLiveSeq;

    /** 事件快照缓存。 */
    private List<SessionEvent> eventsSnapshot;

    /** 追加监听器；持久化等外部投影据此订阅事件流（对应 TS 的 session/event 广播）。 */
    private final List<Consumer<SessionEvent>> appendListeners = new CopyOnWriteArrayList<>();

    // ---- requestHeader 折叠 ----
    private EpochHeader headerFold;
    private int headerFoldSeq;

    // ---- deriveMessages 缓存 ----
    private List<Message> derivedMessages = new ArrayList<>();
    private int derivedNodes;
    private int derivedGeneration;

    /**
     * 创建一个新的会话。
     * @param id 会话标识
     * @param seed 可选的重放或 fork 种子事件
     * @param header 可选的存储元数据
     */
    public Session(SessionId id, SessionEvent[] seed, SessionHeader header) {
        this(id, seed, header, null);
    }

    /**
     * 带初始追加监听器的种子构造：监听先于构造期的 session/end-seed 边界事件注册，
     * 保证从恢复的第一条事件起就不遗漏（持久化接线的正确入口）。
     *
     * @param id 会话标识
     * @param seed 可选的重放或 fork 种子事件
     * @param header 可选的存储元数据
     * @param initialListener 构造期即生效的追加监听器；null 表示无
     */
    public Session(SessionId id, SessionEvent[] seed, SessionHeader header,
                   Consumer<SessionEvent> initialListener) {
        if (initialListener != null) {
            appendListeners.add(initialListener);
        }

        // 处理种子事件
        if (seed != null && seed.length > 0) {
            for (int i = 0; i < seed.length; i++) {
                var event = seed[i];
                if (event.seq() != i) {
                    throw new IllegalArgumentException(
                            "种子事件索引 " + i + " 的 seq 为 " + event.seq()
                                    + "（期望 " + i + "）；种子必须从 0 开始连续"
                    );
                }
                surfaceManager.validateNext(event);
                log.add(event);
                surfaceManager.accept(event);
            }
        }

        this.firstLiveSeq = log.size();

        // 构造或使用传入的 header
        if (header != null) {
            this.header = header;
        } else {
            this.header = new SessionHeader(0, id);
        }

        // 如果有种子事件且最后一条不是 session/end-seed，追加一条
        if (seed != null && seed.length > 0) {
            var last = log.isEmpty() ? null : log.getLast();
            if (last == null || !SessionEventTypes.SESSION_END_SEED.equals(last.type())) {
                append(new SessionEventSessionEndSeed(log.size()));
            }
        }
    }

    /** 简化构造：仅指定 id。 */
    public Session(SessionId id) {
        this(id, null, null);
    }

    // ---- 属性 ----

    /** 会话标识。 */
    public SessionId id() {
        return header.id();
    }

    /** 存储元数据。 */
    public SessionHeader header() {
        return header;
    }

    /** 本进程追加的第一个事件序号。 */
    public int firstLiveSeq() {
        return firstLiveSeq;
    }

    /** 当前表面管理器。 */
    public SurfaceManager surface() {
        return surfaceManager;
    }

    /** 事件日志的不可变快照。 */
    public List<SessionEvent> events() {
        if (eventsSnapshot == null) {
            eventsSnapshot = List.copyOf(log);
        }
        return eventsSnapshot;
    }

    /** 下一个事件序号 = 日志长度。 */
    public int seq() {
        return log.size();
    }

    // ---- append ----

    /**
     * 注册追加监听器：此后每次 {@link #append} 成功后回调。
     * <p>监听器异常会被捕获并记录（不阻断日志与后续监听器），适合观察型投影。</p>
     *
     * @param listener 事件回调
     * @return 注销器
     */
    public AutoCloseable onAppend(java.util.function.Consumer<SessionEvent> listener) {
        appendListeners.add(listener);
        return () -> appendListeners.remove(listener);
    }

    /**
     * 追加一个已构造好的事件到日志。
     * @param event 要追加的事件（必须已设置好 seq、time 等字段）
     * @return 追加的事件
     */
    public SessionEvent append(SessionEvent event) {
        // 验证和接受表面操作
        surfaceManager.validateNext(event);
        log.add(event);
        eventsSnapshot = null;
        surfaceManager.accept(event);
        notifyAppend(event);
        return event;
    }

    /** 通知监听器；单个监听器失败记录后继续，不得破坏事实源。 */
    private void notifyAppend(SessionEvent event) {
        for (var listener : appendListeners) {
            try {
                listener.accept(event);
            } catch (RuntimeException e) {
                logger.warn("Append listener failed on event {}: {}", event.type(), e.getMessage(), e);
            }
        }
    }

    /**
     * 构造并追加一个事件到日志。
     * @param event 事件构造器
     * @return 追加的事件
     */
    public SessionEvent append(EventBuilder event) {
        return append(event.build(seq()));
    }

    // ---- deriveMessages ----

    /**
     * 从日志派生出 LLM 消息历史。
     * <p>
     * 遍历表面节点列表，对每个节点投影为 Message。
     * 结果被缓存，仅新增节点做投影。
     * </p>
     */
    public List<Message> deriveMessages() {
        var nodes = surfaceManager.nodes();
        var generation = surfaceManager.replaceGeneration();

        if (generation != derivedGeneration) {
            derivedMessages = new ArrayList<>();
            derivedNodes = 0;
            derivedGeneration = generation;
        }

        for (int i = derivedNodes; i < nodes.size(); i++) {
            var seq = nodes.get(i);
            var event = log.get(seq);
            var msg = deriveEventMessage(event);
            if (msg != null) {
                derivedMessages.add(msg);
            }
        }
        derivedNodes = nodes.size();

        return List.copyOf(derivedMessages);
    }

    /**
     * 将单个事件投影为 LLM 消息。
     * <p>
     * 对应 TS 源码中的 {@code deriveEventMessage}。
     * </p>
     */
    static Message deriveEventMessage(SessionEvent event) {
        return switch (event) {
            case SessionEventUserMessage ev -> ev.message();
            case SessionEventAssistantMessage ev -> {
                if (ev.message().content().isEmpty()) yield null;
                yield ev.message();
            }
            case SessionEventToolResult ev -> ev.message();
            default -> null;
        };
    }

    // ---- requestHeader ----

    /**
     * 返回日志中最后一个 {@code request/header} 事件折叠的 EpochHeader。
     */
    public EpochHeader requestHeader() {
        if (headerFoldSeq < log.size()) {
            for (int i = headerFoldSeq; i < log.size(); i++) {
                var event = log.get(i);
                if (event instanceof SessionEventRequestHeader rh) {
                    headerFold = rh.header();
                }
            }
            headerFoldSeq = log.size();
        }
        return headerFold;
    }

    // ---- 事件构造器辅助接口 ----

    /** 事件构造器，用于 {@link #append(EventBuilder)}。 */
    @FunctionalInterface
    public interface EventBuilder {
        SessionEvent build(int seq);
    }
}