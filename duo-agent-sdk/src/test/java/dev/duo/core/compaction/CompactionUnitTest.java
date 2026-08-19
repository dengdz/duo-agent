package dev.duo.core.compaction;

import dev.duo.model.llm.ContentBlock;
import dev.duo.model.llm.Message;
import dev.duo.model.llm.MessageFactory;
import dev.duo.model.llm.MessageSource;
import dev.duo.util.CallId;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 压缩支撑算法的单元测试：token 估算、工具配对平衡、选区。
 *
 * @author zhangyl
 * @date 2026-08-19
 */
class CompactionUnitTest {

    private static Message.UserMessage user(String text) {
        return MessageFactory.createUserMessage(
                List.of(new ContentBlock.Text(text)), new MessageSource.User());
    }

    private static Message.AssistantMessage assistantText(String text) {
        return MessageFactory.createAssistantMessage(
                List.of(new ContentBlock.Text(text)), "p", "m");
    }

    private static Message.AssistantMessage assistantToolCall(String callId) {
        return MessageFactory.createAssistantMessage(
                List.of(new ContentBlock.ToolCall(new CallId(callId), "echo", "{}")),
                "p", "m");
    }

    private static Message.ToolResultMessage toolResult(String callId) {
        return MessageFactory.createToolResultMessage(
                new CallId(callId), List.of(new ContentBlock.Text("结果")), false);
    }

    @Test
    void estimatorCountsContentAndOverhead() {
        assertEquals(4, TokenEstimator.estimate(user("")), "空消息只剩固定开销");
        assertTrue(TokenEstimator.estimate(user("12345678")) > TokenEstimator.estimate(user("")),
                "内容长度应计入估算");
        assertEquals(TokenEstimator.estimate(user("abc")) * 2,
                TokenEstimator.estimateAll(List.of(user("abc"), user("abc"))),
                "总量等于逐条之和");
    }

    @Test
    void balancedBeforeDetectsDanglingCall() {
        var messages = List.<Message>of(
                user("hi"),
                assistantToolCall("c1"),
                toolResult("c1"));
        assertTrue(ToolPairing.balancedBefore(messages, 3), "完整配对平衡");
        assertFalse(ToolPairing.balancedBefore(messages, 2), "切在结果前 = 悬挂调用");
        assertTrue(ToolPairing.balancedBefore(messages, 2 - 1), "切点前移到调用之前恢复平衡");
    }

    @Test
    void balancedAfterDetectsOrphanResult() {
        var messages = List.<Message>of(
                user("hi"),
                toolResult("orphan"));
        assertFalse(ToolPairing.balancedAfter(messages, 1), "保留区开头的孤儿结果不平衡");
        assertTrue(ToolPairing.balancedAfter(messages, 2), "孤儿并入压缩区后平衡");
    }

    @Test
    void balancedAfterAcceptsSelfContainedPairsInTail() {
        // 后侧自带完整配对（调用与结果都在保留区）：不得误判为孤儿
        var messages = List.<Message>of(
                user("第一问"),
                assistantText("第一答"),
                user("第二问"),
                assistantToolCall("c2"),
                toolResult("c2"));
        assertTrue(ToolPairing.balancedAfter(messages, 2),
                "后侧自包含的调用配对是平衡的");
        assertTrue(ToolPairing.balancedBefore(messages, 2), "切点前无调用同样平衡");
    }

    @Test
    void rangeSelectionRetainsTailByTokens() {
        // 3 条长消息：retainTokens 只保住最后一条 → 压缩 [0, 1]
        var messages = List.<Message>of(
                user("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"),
                user("bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"),
                user("cccccccccccccccccccccccccccccccc"));
        int keep = CompactionHook.selectCompactableRange(messages, 8);
        assertEquals(2, keep, "尾部按 token 保留，前两条进入压缩区");
    }

    @Test
    void rangeSelectionExtendsToPairingBoundary() {
        // 尾部保住 2 条后切点本可在调用与结果之间，配对平衡要求把调用一起保留
        var messages = new ArrayList<Message>();
        messages.add(user("问题"));
        messages.add(assistantToolCall("c1"));
        messages.add(toolResult("c1"));
        messages.add(user("追问"));
        messages.add(assistantText("回答"));
        int keep = CompactionHook.selectCompactableRange(messages, 6);
        var compressed = messages.subList(0, keep);
        assertTrue(ToolPairing.balancedBefore(messages, keep), "选区切点必须前侧平衡");
        assertTrue(keep >= 2, "配对边界使切点前移（不拆散调用）");
    }

    @Test
    void rangeSelectionZeroWhenTailCoversAll() {
        var messages = List.<Message>of(user("短"), user("短"));
        assertEquals(0, CompactionHook.selectCompactableRange(messages, 100),
                "全部消息都在保留线内时无可压缩区");
    }
}
