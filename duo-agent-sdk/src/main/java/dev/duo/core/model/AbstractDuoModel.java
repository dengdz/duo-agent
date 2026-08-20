package dev.duo.core.model;

import dev.duo.api.DuoModel;
import dev.duo.api.llm.LlmAdapter;
import dev.duo.api.llm.StreamCallback;
import dev.duo.core.flow.BufferedPublisher;
import dev.duo.model.llm.ContentBlock;
import dev.duo.model.llm.GenerateOptions;
import dev.duo.model.llm.MessageFactory;
import dev.duo.model.llm.MessageSource;
import dev.duo.model.llm.StreamChunk;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * DuoModel 的通用实现骨架。
 * <p>
 * call/stream 的驱动逻辑对厂家中立（组装 GenerateOptions → 驱动适配器），
 * 厂家实现只需提供 {@link #newAdapter(Duration)} 工厂钩子与 API 格式标识。
 * 本类存在的原因是流式驱动包含不可重复的并发细节（冷发布、有界缓冲、
 * 恰好一次终态），不应在每个厂家 Model 中各写一份。
 * </p>
 * <p>
 * <b>适配器复用</b>：无参 {@link #createAdapter()} 返回的实例在 Model
 * 生命周期内复用——适配器持有 HttpClient 连接池，按调用重建会造成资源抖动；
 * Agent 组装路径的 {@link #createAdapter(Duration)} 按调用创建新实例，
 * 与「每个 Agent 独占适配器」的既有组装语义一致。
 * </p>
 *
 * @author zhangyl
 * @date 2026-08-20
 */
public abstract class AbstractDuoModel implements DuoModel {

    /**
     * 厂家中立的模型配置，由各厂家 Builder 组装后传入。
     *
     * @param modelName         模型名称（非空）
     * @param systemPrompt      系统提示词（可选）
     * @param contextWindow     上下文窗口（可选）
     * @param maxOutputTokens   最大输出 token（可选，null 表示由模型决定）
     * @param temperature       采样温度（可选）
     * @param reasoningEnabled  是否启用深度推理
     * @param reasoningTimeout  推理超时（非 null，默认语义 5 分钟）
     */
    protected record Config(
            String modelName,
            String systemPrompt,
            Integer contextWindow,
            Integer maxOutputTokens,
            Double temperature,
            boolean reasoningEnabled,
            Duration reasoningTimeout
    ) {
        public Config {
            if (modelName == null || modelName.isBlank()) {
                throw new IllegalArgumentException("modelName 不能为空");
            }
            if (reasoningTimeout == null || reasoningTimeout.isZero() || reasoningTimeout.isNegative()) {
                throw new IllegalArgumentException("reasoningTimeout 必须大于 0");
            }
            if (contextWindow != null && contextWindow <= 0) {
                throw new IllegalArgumentException("contextWindow 必须大于 0");
            }
            if (maxOutputTokens != null && maxOutputTokens <= 0) {
                throw new IllegalArgumentException("maxOutputTokens 必须大于 0");
            }
        }
    }

    /** 普通模式的应用层超时假设，与 Agent 默认 llmTimeout（60s）对齐。 */
    private static final Duration DEFAULT_APP_TIMEOUT = Duration.ofSeconds(60);

    private final Config config;

    /** 无参工厂使用的 HTTP 兜底超时（应用层假设 + 1 分钟余量）。 */
    private final Duration selfRequestTimeout;

    /** 自用适配器，懒初始化并复用（volatile + 同步避免重复建连）。 */
    private volatile LlmAdapter selfAdapter;

    /**
     * @param config 厂家中立配置
     */
    protected AbstractDuoModel(Config config) {
        this.config = Objects.requireNonNull(config, "config 不能为 null");
        this.selfRequestTimeout = appTimeout().plusMinutes(1);
    }

    /** 应用层最大超时假设：推理模式取 reasoningTimeout，普通模式固定 60s。 */
    private Duration appTimeout() {
        return config.reasoningEnabled() ? config.reasoningTimeout() : DEFAULT_APP_TIMEOUT;
    }

    /** 供厂家实现读取配置。 */
    protected final Config config() {
        return config;
    }

    @Override
    public String getModelName() {
        return config.modelName();
    }

    @Override
    public String getSystemPrompt() {
        return config.systemPrompt();
    }

    @Override
    public Integer getContextWindow() {
        return config.contextWindow();
    }

    @Override
    public Integer getMaxOutputTokens() {
        return config.maxOutputTokens();
    }

    @Override
    public boolean isReasoningEnabled() {
        return config.reasoningEnabled();
    }

    @Override
    public Duration getReasoningTimeout() {
        return config.reasoningTimeout();
    }

    @Override
    public final LlmAdapter createAdapter() {
        var adapter = selfAdapter;
        if (adapter == null) {
            synchronized (this) {
                adapter = selfAdapter;
                if (adapter == null) {
                    adapter = newAdapter(selfRequestTimeout);
                    selfAdapter = adapter;
                }
            }
        }
        return adapter;
    }

    @Override
    public final LlmAdapter createAdapter(Duration httpTimeout) {
        Objects.requireNonNull(httpTimeout, "httpTimeout 不能为 null");
        if (httpTimeout.isZero() || httpTimeout.isNegative()) {
            throw new IllegalArgumentException("httpTimeout 必须大于 0");
        }
        // 红线自校验：HTTP 兜底必须大于应用层最大超时，否则会先于
        // 应用层 barrier 掐断流式回复（组装方算错时立即暴露而非静默违约）
        if (httpTimeout.compareTo(appTimeout()) <= 0) {
            throw new IllegalArgumentException(
                    "httpTimeout 必须大于应用层最大超时 " + appTimeout() + "，当前: " + httpTimeout);
        }
        return newAdapter(httpTimeout);
    }

    /**
     * 厂家钩子：按给定 HTTP 兜底超时创建底层适配器。
     *
     * @param httpTimeout HTTP 兜底超时（必须大于应用层最大超时）
     * @return 新的适配器实例
     */
    protected abstract LlmAdapter newAdapter(Duration httpTimeout);

    @Override
    public String call(String prompt) {
        if (prompt == null || prompt.isBlank()) {
            throw new IllegalArgumentException("prompt 不能为空");
        }
        var adapter = createAdapter();
        var text = new StringBuilder();
        var failure = new AtomicReference<Throwable>();
        var done = new CountDownLatch(1);

        // 适配器为同步实现时回调在 stream() 返回前完成，latch 立即放行；
        // latch + 兜底超时同时覆盖异步适配器与回调丢失两种情况，防永久挂死
        adapter.stream(buildRequest(prompt), new StreamCallback() {
            @Override
            public void onChunk(StreamChunk chunk) {
                if (chunk instanceof StreamChunk.TextDelta delta) {
                    text.append(delta.text());
                }
            }

            @Override
            public void onComplete() {
                done.countDown();
            }

            @Override
            public void onError(Throwable error) {
                failure.set(error);
                done.countDown();
            }
        });

        try {
            // 应用层 barrier 按 appTimeout 切断（超时分层红线：应用层先于
            // HTTP 兜底生效）；健康适配器会在 HTTP 超时（appTimeout + 1min）
            // 处先行 onError，此 await 是回调丢失/适配器挂死时的最后防线
            if (!done.await(appTimeout().toMillis(), TimeUnit.MILLISECONDS)) {
                throw new IllegalStateException("模型调用超时（应用层上限 " + appTimeout() + "）");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("模型调用被中断", e);
        }

        var error = failure.get();
        if (error != null) {
            throw new RuntimeException("模型调用失败: " + error.getMessage(), error);
        }
        if (text.isEmpty()) {
            throw new IllegalStateException(
                    "模型响应不包含文本内容（可能是纯推理或纯工具调用响应）");
        }
        return text.toString();
    }

    @Override
    public Flow.Publisher<StreamChunk> stream(String prompt) {
        if (prompt == null || prompt.isBlank()) {
            throw new IllegalArgumentException("prompt 不能为空");
        }
        // 冷发布者：订阅时才在虚拟线程上驱动适配器，缓冲/背压/单订阅语义
        // 与 Agent 事件流共用同一实现
        return new BufferedPublisher<>("duo-model-stream", emitter -> {
            var adapter = createAdapter();
            adapter.stream(buildRequest(prompt), new StreamCallback() {
                @Override
                public void onChunk(StreamChunk chunk) {
                    emitter.emit(chunk);
                }

                @Override
                public void onComplete() {
                    emitter.complete();
                }

                @Override
                public void onError(Throwable error) {
                    emitter.fail(error);
                }
            });
        });
    }

    /**
     * 组装单次调用请求。
     * <p>
     * provider 路由键使用 API 格式——Model 直连适配器（不经 LlmRuntime），
     * 该字段仅为满足 GenerateOptions 契约；tools 恒为 null，工具执行是
     * Agent 的职责。
     * </p>
     */
    private GenerateOptions buildRequest(String prompt) {
        var message = MessageFactory.createUserMessage(
                List.of(new ContentBlock.Text(prompt)),
                new MessageSource.User()
        );
        return new GenerateOptions(
                getApiFormat(),
                config.modelName(),
                List.of(message),
                config.systemPrompt(),
                null,
                config.temperature(),
                config.maxOutputTokens(),
                null,
                null,
                config.reasoningEnabled()
        );
    }
}
