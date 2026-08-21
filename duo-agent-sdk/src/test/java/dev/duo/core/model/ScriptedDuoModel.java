package dev.duo.core.model;

import dev.duo.api.llm.LlmAdapter;

import java.time.Duration;
import java.util.function.Function;

/**
 * 测试用 DuoModel 固定桩：包装脚本适配器，供 Builder/Model 层测试复用。
 * <p>
 * 不发起真实网络调用；适配器经 {@link Function} 提供——工厂入参为组装方
 * 计算的 HTTP 兜底超时，可断言超时分层红线；固定实例可验证复用语义，
 * 可区分实例（如 {@code DistinctAdapter::new}）可验证组装隔离语义。
 * systemPrompt、reasoningTimeout 等配置可定制，以覆盖
 * {@code DuoAgentBuilder} 组装逻辑的各分支。
 * </p>
 *
 * @author zhangyl
 * @date 2026-08-20
 */
public final class ScriptedDuoModel extends AbstractDuoModel {

    private final Function<Duration, LlmAdapter> adapterFactory;

    private final String apiFormat;

    /**
     * 以固定适配器实例构建桩（{@link #createAdapter()} 与
     * {@link #createAdapter(Duration)} 返回同一实例）。
     *
     * @param adapter 脚本适配器
     */
    public ScriptedDuoModel(LlmAdapter adapter) {
        this(timeout -> adapter, null, false, Duration.ofMinutes(5));
    }

    /**
     * @param adapterFactory   适配器工厂（入参为组装方计算的 HTTP 兜底超时）
     * @param systemPrompt     模型级系统提示词（可 null）
     * @param reasoningEnabled 是否启用推理
     * @param reasoningTimeout 推理超时
     */
    public ScriptedDuoModel(Function<Duration, LlmAdapter> adapterFactory, String systemPrompt,
                            boolean reasoningEnabled, Duration reasoningTimeout) {
        this(adapterFactory, systemPrompt, reasoningEnabled, reasoningTimeout, "openai");
    }

    /**
     * 指定协议标识的构造（覆盖 responses 等新协议经 AgentOptions 白名单的组装路径）。
     */
    public ScriptedDuoModel(Function<Duration, LlmAdapter> adapterFactory, String systemPrompt,
                            boolean reasoningEnabled, Duration reasoningTimeout, String apiFormat) {
        super(new Config("mock-model", systemPrompt, null, null, null, reasoningEnabled, reasoningTimeout));
        this.adapterFactory = adapterFactory;
        this.apiFormat = apiFormat;
    }

    @Override
    public String getApiFormat() {
        return apiFormat;
    }

    @Override
    protected LlmAdapter newAdapter(Duration httpTimeout) {
        return adapterFactory.apply(httpTimeout);
    }
}
