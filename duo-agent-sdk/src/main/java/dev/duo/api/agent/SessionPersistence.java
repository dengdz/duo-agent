package dev.duo.api.agent;

import dev.duo.model.session.SessionEvent;
import dev.duo.model.session.SessionHeader;
import dev.duo.model.session.SessionId;

import java.io.IOException;
import java.util.List;

/**
 * 持久化会话存储契约。
 * <p>
 * 后端以事件溯源方式存储 {@link SessionEvent} 日志，不可回放的
 * {@link SessionHeader} 元数据单独携带。{@link #append} 返回即已落盘；
 * {@link #load} 会对崩溃遗留的 open turn 做持久化闭合修复。
 * </p>
 *
 * @author zhangyl
 * @date 2026-08-19
 */
public interface SessionPersistence {

    /**
     * 注册新会话的元数据。允许惰性物化：created 后从未 append 的会话
     * 不在 {@link #list} 中出现——废弃的会话不留痕迹。
     *
     * @param header 不可变会话头
     */
    void create(SessionHeader header) throws IOException;

    /**
     * 持久化一批事件。遵守仅追加与 seq 连续契约：
     * 首事件的 seq 必须等于已存的下一 seq（load 完成崩溃闭合之后）。
     *
     * @param id 目标会话
     * @param events 按 seq 排序的连续批次
     */
    void append(SessionId id, List<SessionEvent> events) throws IOException;

    /**
     * 加载会话并提交冷恢复：完整的中断末尾 turn 以合成 tool 结果
     * 与 step/turn 闭合事件持久关闭，仅撕裂的末行被丢弃；
     * 已提交前缀中的损坏拒绝。返回以平衡的 turn/end 结尾。
     *
     * @param id 已持久化的会话
     * @return 会话头与闭合后的事件日志
     */
    SessionInspection load(SessionId id) throws IOException;

    /** 列出所有已物化会话的元数据（不解析完整日志）。 */
    List<SessionHeader> list() throws IOException;

    /** 强制把指定会话的待写缓冲落盘。 */
    void flush(SessionId id) throws IOException;

    /** 加载结果：会话头与闭合后的事件日志。 */
    record SessionInspection(SessionHeader header, List<SessionEvent> events) {}
}
