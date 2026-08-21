package dev.duo.core.compaction;

import dev.duo.api.agent.PreStepDecision;
import dev.duo.api.hook.PreStepHook;
import dev.duo.api.llm.LlmRuntime;
import dev.duo.api.llm.StreamCallback;
import dev.duo.api.llm.SystemPrompt;
import dev.duo.core.llm.BlockAssembler;
import dev.duo.core.llm.SystemPromptImpl;
import dev.duo.core.session.Session;
import dev.duo.model.llm.ContentBlock;
import dev.duo.model.llm.FinishReason;
import dev.duo.model.llm.GenerateOptions;
import dev.duo.model.llm.Message;
import dev.duo.model.llm.MessageFactory;
import dev.duo.model.llm.MessageSource;
import dev.duo.model.session.SessionEventCompactionEnd;
import dev.duo.model.session.SessionEventCompactionStart;
import dev.duo.model.session.SessionEventUserMessage;
import dev.duo.model.session.SurfaceOp;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 压缩 hook：step 间压力触发的表面压缩。
 * <p>
 * 全链路外挂在 {@link PreStepHook} 上：估算表面 token，超过阈值时把
 * [开头, 中段切点] 的表面范围替换为一条摘要 checkpoint 消息（SurfaceOp.Replace）。
 * 选区保留最近的对话尾巴（retainTokens），且不拆散工具调用配对；
 * 摘要请求复用对话自身的 system prompt 与消息前缀（不使提供方前缀缓存失效）。
 * 事务以 compaction/start 为持久锁，成败都落一条 compaction/end；
 * 压缩失败只记录并放行 turn（压缩是优化，绝不阻塞对话）。
 * </p>
 *
 * @author zhangyl
 * @date 2026-08-19
 */
public final class CompactionHook implements PreStepHook {

    private static final Logger logger = LoggerFactory.getLogger(CompactionHook.class);

    /** 摘要指令：追加为最后一条 user 消息。 */
    static final String SUMMARIZE_INSTRUCTION =
            "请把此前的对话压缩为一份摘要，供后续对话作为唯一的历史上下文使用。"
                    + "必须保留：用户的原始意图与显式纠正、已做过的关键决定、"
                    + "工具调用的重要结果、尚未完成的任务。直接输出摘要正文。";

    private final LlmRuntime llmRuntime;
    private final SystemPrompt systemPrompt;
    private final String summarizationProvider;
    private final String summarizationModel;
    private final CompactionConfig config;

    public CompactionHook(LlmRuntime llmRuntime, SystemPrompt systemPrompt,
                          String summarizationProvider, String summarizationModel,
                          CompactionConfig config) {
        this.llmRuntime = Objects.requireNonNull(llmRuntime, "llmRuntime must not be null");
        this.systemPrompt = Objects.requireNonNull(systemPrompt, "systemPrompt must not be null");
        this.summarizationProvider = Objects.requireNonNull(summarizationProvider,
                "summarizationProvider must not be null");
        this.summarizationModel = Objects.requireNonNull(summarizationModel,
                "summarizationModel must not be null");
        this.config = Objects.requireNonNull(config, "config must not be null");
    }

    @Override
    public PreStepDecision decide(PreStepContext context, Chain next) throws Exception {
        try {
            compactUntilBelowThreshold(context.session(), context.turn());
        } catch (Exception e) {
            logger.warn("会话 {} 压缩失败，继续当前 turn: {}", context.agentId(), e.getMessage(), e);
        }
        return next.proceed();
    }

    /** 一次触发内的压缩循环：仍超阈值则再压，最多 maxAttempts 次。 */
    private void compactUntilBelowThreshold(Session session, int turn) throws Exception {
        for (int attempt = 0; attempt < config.maxAttempts(); attempt++) {
            var messages = session.deriveMessages();
            if (TokenEstimator.estimateAll(messages) < config.thresholdTokens()) {
                return;
            }
            if (!compactOnce(session, messages, turn)) {
                return;
            }
        }
        logger.warn("会话 {} 压缩 {} 次后仍超阈值（{} >= {}），放行",
                session.id(), config.maxAttempts(),
                TokenEstimator.estimateAll(session.deriveMessages()), config.thresholdTokens());
    }

    /**
     * 执行一次压缩事务。返回是否真的发生了压缩（无可压区域返回 false）。
     */
    private boolean compactOnce(Session session, List<Message> messages, int turn) throws Exception {
        int keepFromIndex = selectCompactableRange(messages, config.retainTokens());
        if (keepFromIndex <= 0) {
            return false;
        }
        var surfaceNodes = session.surface().nodes();
        // 压缩区间 [表面首节点, 保留区前一个节点]
        int startSeq = surfaceNodes.getFirst();
        int endSeq = surfaceNodes.get(keepFromIndex - 1);
        var compactionId = UUID.randomUUID().toString();

        session.append(new SessionEventCompactionStart(session.seq(), compactionId, turn));
        try {
            var summary = summarize(messages);
            if (summary.isBlank()) {
                throw new IllegalStateException("摘要结果为空");
            }
            var checkpoint = MessageFactory.createUserMessage(
                    List.of(new ContentBlock.Text("[对话摘要] " + summary)),
                    new MessageSource.Plugin("compaction"));
            // 表面替换：日志追加一条携带 Replace 的用户消息，原事件保留在日志中
            session.append(new SessionEventUserMessage(
                    session.seq(), checkpoint, new SurfaceOp.Replace(startSeq, endSeq)));
            session.append(new SessionEventCompactionEnd(session.seq(), compactionId, turn, null));
            logger.info("会话 {} 压缩完成：替换表面 seq {}-{}（{} 条消息）为摘要",
                    session.id(), startSeq, endSeq, keepFromIndex);
            return true;
        } catch (Exception e) {
            try {
                session.append(new SessionEventCompactionEnd(
                        session.seq(), compactionId, turn, e.getMessage()));
            } catch (RuntimeException closeError) {
                // 闭合标记写入失败不得吞掉导致压缩失败的原始异常
                e.addSuppressed(closeError);
            }
            throw e;
        }
    }

    /**
     * 选区：从尾部累计 retainTokens 定位保留起点，向前扩展到工具配对平衡。
     *
     * @param messages 表面消息（模型可见顺序）
     * @param retainTokens 必须保留的尾部估算 token 下限
     * @return 保留区起始的消息索引；0 表示无可压缩范围
     */
    static int selectCompactableRange(List<Message> messages, int retainTokens) {
        var tokens = TokenEstimator.estimateEach(messages);
        int accumulated = 0;
        int keepFromIndex = messages.size();
        for (int i = messages.size() - 1; i >= 0; i--) {
            accumulated += tokens[i];
            keepFromIndex = i;
            if (accumulated >= retainTokens) {
                break;
            }
        }
        if (keepFromIndex <= 0) {
            return 0;
        }
        // 前侧配对平衡：悬挂的 tool-call 需把切点前移（保留配对完整）
        while (keepFromIndex > 0 && !ToolPairing.balancedBefore(messages, keepFromIndex)) {
            keepFromIndex--;
        }
        // 后侧孤儿结果兜底：保留区开头不得有无调用的 result
        while (keepFromIndex > 0 && !ToolPairing.balancedAfter(messages, keepFromIndex)) {
            keepFromIndex--;
        }
        return keepFromIndex;
    }

    /** 摘要调用：复用对话自身 system prompt 与完整消息前缀，指令追加为末条 user 消息。 */
    private String summarize(List<Message> messages) throws Exception {
        var requestMessages = new ArrayList<Message>(messages.size() + 1);
        requestMessages.addAll(messages);
        requestMessages.add(MessageFactory.createUserMessage(
                List.of(new ContentBlock.Text(SUMMARIZE_INSTRUCTION)), new MessageSource.User()));

        var assembly = systemPrompt.assemble();
        var system = SystemPromptImpl.renderPrompt(assembly);
        var request = new GenerateOptions(
                summarizationProvider, summarizationModel, List.copyOf(requestMessages), system, null);

        var assembler = new BlockAssembler();
        var barrier = new CompletableFuture<Void>();
        var errorRef = new AtomicReference<Throwable>();
        var closed = new AtomicBoolean();

        llmRuntime.stream(request, new StreamCallback() {
            @Override
            public void onChunk(dev.duo.model.llm.StreamChunk chunk) {
                if (closed.get()) {
                    return;
                }
                assembler.push(chunk);
            }
            @Override
            public void onComplete() {
                barrier.complete(null);
            }
            @Override
            public void onError(Throwable err) {
                errorRef.set(err);
                barrier.completeExceptionally(err);
            }
        });

        try {
            barrier.get(config.summarizationTimeout().toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            closed.set(true);
            throw new IllegalStateException("摘要调用被中断", e);
        } catch (ExecutionException | TimeoutException e) {
            closed.set(true);
            throw new IllegalStateException("摘要调用失败", e);
        }
        if (errorRef.get() != null) {
            closed.set(true);
            throw new IllegalStateException("摘要调用失败", errorRef.get());
        }
        var finish = assembler.finish();
        if (finish instanceof FinishReason.Aborted || finish instanceof FinishReason.Error) {
            throw new IllegalStateException("摘要流异常结束");
        }
        return assembler.blocks().stream()
                .filter(b -> b instanceof ContentBlock.Text)
                .map(b -> ((ContentBlock.Text) b).text())
                .reduce("", String::concat);
    }
}
