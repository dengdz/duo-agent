package dev.duo.core.session;

import dev.duo.model.session.SessionEvent;
import dev.duo.model.session.SessionEventTypes;
import dev.duo.model.session.SurfaceOp;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static dev.duo.model.session.SessionEventTypes.SURFACE_EVENT_TYPES;

/**
 * 会话事件日志之上的表面层：产生 LLM 消息的事件的有序视图。
 * <p>
 * 追加式日志仍然是事实源。表面节点列表是 seq 的数组，指向日志中的事件。
 * 只有 {@code user/message}、{@code assistant/message}、{@code tool/result}
 * 三个事件类型可以进入表面。
 * </p>
 * <p>
 * 对应 TS 源码中的 {@code SurfaceManager} + {@code SessionSurface}。
 * </p>
 *
 * @author zhangyl
 * @date 2026-08-18
 */
public class SurfaceManager {

    /** 当前表面节点 seq 列表（按模型可见顺序）。 */
    private final List<Integer> nodes = new ArrayList<>();

    /** 已提交的位置替换的单调递增计数。 */
    private int replaceGeneration = 0;

    /** 当前表面节点 seq 的只读视图。 */
    public List<Integer> nodes() {
        return Collections.unmodifiableList(nodes);
    }

    /** 替换代次，用于派生消息缓存失效。 */
    public int replaceGeneration() {
        return replaceGeneration;
    }

    /** 事件类型是否可进入表面。 */
    public static boolean isSurfaceEligibleType(String type) {
        return SURFACE_EVENT_TYPES.contains(type);
    }

    /**
     * 验证一个事件在追加前是否合法。
     * 不修改状态。
     */
    public void validateNext(SessionEvent event) {
        var isSurface = SURFACE_EVENT_TYPES.contains(event.type());
        var op = event.surfaceOp();

        if (isSurface) {
            if (op == null) {
                throw new IllegalArgumentException(
                        "表面事件 \"" + event.type() + "\" 必须携带 surfaceOp"
                );
            }
        } else {
            if (op != null) {
                throw new IllegalArgumentException(
                        "非表面事件 \"" + event.type() + "\" 不能携带 surfaceOp"
                );
            }
            return;
        }

        // 验证 replace 操作的范围
        if (op instanceof SurfaceOp.Replace replace) {
            var startIdx = nodes.indexOf(replace.start());
            if (startIdx == -1) {
                throw new IllegalArgumentException(
                        "surface replace: 起始 seq " + replace.start() + " 不在表面中"
                );
            }
            var endIdx = nodes.indexOf(replace.end());
            if (endIdx == -1) {
                throw new IllegalArgumentException(
                        "surface replace: 结束 seq " + replace.end() + " 不在表面中"
                );
            }
            if (startIdx > endIdx) {
                throw new IllegalArgumentException(
                        "surface replace: 起始 seq " + replace.start() + " 在结束 seq "
                                + replace.end() + " 之后"
                );
            }
        }
    }

    /**
     * 接受一个事件进入表面，更新表面节点列表。
     * 调用前必须先调用 {@link #validateNext}。
     */
    public void accept(SessionEvent event) {
        var op = event.surfaceOp();
        if (op == null) return;

        switch (op) {
            case SurfaceOp.Append ignored -> {
                nodes.add(event.seq());
            }
            case SurfaceOp.Replace replace -> {
                var startIdx = nodes.indexOf(replace.start());
                var endIdx = nodes.indexOf(replace.end());
                // 删除被替换的范围
                for (int i = endIdx; i >= startIdx; i--) {
                    nodes.remove(i);
                }
                // 在 startIdx 位置插入新事件
                nodes.add(startIdx, event.seq());
                replaceGeneration++;
            }
        }
    }
}